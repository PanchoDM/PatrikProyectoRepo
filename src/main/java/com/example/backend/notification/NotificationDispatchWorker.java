package com.example.backend.notification;

import com.example.backend.config.AppProperties;
import com.example.backend.domain.entity.NotificationLog;
import com.example.backend.domain.enums.NotificationChannel;
import com.example.backend.domain.enums.NotificationStatus;
import com.example.backend.repository.NotificationLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
// import org.springframework.stereotype.Component; // TEMPORAL: ver nota mas abajo sobre @Component

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Consumidor confiable de la cola de notificaciones (patron Reliable Queue de
 * Redis: BRPOPLPUSH ready -> processing). Corre en un hilo virtual dedicado.
 * Al arrancar, recupera cualquier job que haya quedado "en vuelo" en
 * `processing` por una caida previa del backend, garantizando que ningun
 * envio se pierda entre reinicios.
 */
// TEMPORAL: Redis no esta disponible en este entorno (Windows nativo, sin
// servidor Redis instalado). Se desactiva este worker comentando @Component
// para que Spring no lo instancie ni arranque el hilo de fondo, que si no
// reintentaba la conexion cada 3s en bucle infinito. Descomentar @Component
// para reactivarlo cuando haya un Redis disponible (local o gestionado).
// @Component
@Slf4j
public class NotificationDispatchWorker {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final StringRedisTemplate redisTemplate;
    private final AppProperties appProperties;
    private final NotificationLogRepository notificationLogRepository;
    private final Map<NotificationChannel, NotificationChannelClient> clientsByChannel;

    private volatile boolean running = true;

    public NotificationDispatchWorker(StringRedisTemplate redisTemplate,
                                       AppProperties appProperties,
                                       NotificationLogRepository notificationLogRepository,
                                       List<NotificationChannelClient> clients) {
        this.redisTemplate = redisTemplate;
        this.appProperties = appProperties;
        this.notificationLogRepository = notificationLogRepository;
        this.clientsByChannel = clients.stream()
                .collect(Collectors.toMap(NotificationChannelClient::channel, c -> c));
    }

    private String readyKey() {
        return appProperties.notifications().redisStreamKey() + ":ready";
    }

    private String processingKey() {
        return appProperties.notifications().redisStreamKey() + ":processing";
    }

    @PostConstruct
    public void start() {
        // Redis respalda la cola de notificaciones, no la logica de negocio de
        // vuelos: si no esta disponible al arrancar, no debe tumbar todo el
        // backend. El hilo de fondo reintenta solo hasta reconectar.
        try {
            recoverInFlightJobs();
        } catch (Exception ex) {
            log.warn("No se pudo recuperar la cola de notificaciones al arrancar (Redis no disponible aun): {}",
                    ex.getMessage());
        }
        Thread worker = new Thread(this::runLoop, "notification-dispatch-worker");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    public void stop() {
        running = false;
    }

    /** Jobs que quedaron en `processing` por un crash anterior vuelven a `ready`. */
    private void recoverInFlightJobs() {
        long recovered = 0;
        String raw;
        while ((raw = redisTemplate.opsForList().rightPopAndLeftPush(processingKey(), readyKey())) != null) {
            recovered++;
        }
        if (recovered > 0) {
            log.info("Recuperados {} jobs de notificacion pendientes tras reinicio", recovered);
        }
    }

    private void runLoop() {
        while (running) {
            try {
                String raw = redisTemplate.opsForList()
                        .rightPopAndLeftPush(readyKey(), processingKey(), Duration.ofSeconds(3));
                if (raw == null) {
                    continue;
                }
                process(raw);
            } catch (Exception ex) {
                log.error("Error inesperado en el worker de notificaciones (reintenta en 3s): {}", ex.getMessage());
                sleepQuietly(Duration.ofSeconds(3));
            }
        }
    }

    private void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void process(String raw) {
        NotificationJob job;
        try {
            job = OBJECT_MAPPER.readValue(raw, NotificationJob.class);
        } catch (Exception ex) {
            log.error("Job de notificacion corrupto, se descarta: {}", raw, ex);
            redisTemplate.opsForList().remove(processingKey(), 1, raw);
            return;
        }

        NotificationChannelClient client = clientsByChannel.get(job.channel());
        try {
            if (client == null) {
                throw new IllegalStateException("Sin cliente registrado para canal " + job.channel());
            }
            client.send(job);
            redisTemplate.opsForList().remove(processingKey(), 1, raw);
            persistLog(job, NotificationStatus.SENT, null);
        } catch (Exception ex) {
            redisTemplate.opsForList().remove(processingKey(), 1, raw);
            int maxRetry = appProperties.notifications().maxRetry();
            if (job.retryCount() < maxRetry) {
                NotificationJob retried = job.withRetryIncremented();
                try {
                    redisTemplate.opsForList().rightPush(readyKey(), OBJECT_MAPPER.writeValueAsString(retried));
                } catch (Exception serializationEx) {
                    log.error("No se pudo re-encolar job de notificacion", serializationEx);
                }
                log.warn("Reintento {}/{} para notificacion {} ({}): {}",
                        retried.retryCount(), maxRetry, job.channel(), job.recipient(), ex.getMessage());
            } else {
                log.error("Notificacion {} a {} fallo tras {} reintentos", job.channel(), job.recipient(), maxRetry, ex);
                persistLog(job, NotificationStatus.FAILED, ex.getMessage());
            }
        }
    }

    private void persistLog(NotificationJob job, NotificationStatus status, String errorMessage) {
        NotificationLog entity = new NotificationLog();
        entity.setFlightId(job.flightId());
        entity.setChannel(job.channel());
        entity.setRecipient(job.recipient());
        entity.setTemplate(job.template());
        entity.setStatus(status);
        entity.setPayload(job.payload());
        entity.setErrorMessage(errorMessage);
        entity.setCreatedAt(Instant.now());
        if (status == NotificationStatus.SENT) {
            entity.setDeliveredAt(Instant.now());
        }
        notificationLogRepository.save(entity);
    }
}
