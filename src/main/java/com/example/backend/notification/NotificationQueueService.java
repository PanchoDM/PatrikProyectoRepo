package com.example.backend.notification;

import com.example.backend.config.AppProperties;
import com.example.backend.domain.enums.NotificationChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Productor de la cola persistente de notificaciones. Los jobs se guardan en
 * una lista de Redis (RPUSH) para que sobrevivan a un reinicio del backend;
 * NotificationDispatchWorker los consume de forma confiable (BRPOPLPUSH).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationQueueService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // TEMPORAL: Redis no esta disponible en este entorno (Windows nativo, sin
    // servidor Redis instalado). Se desactiva el push real a Redis mas abajo
    // (ver REDIS_ENABLED) para que el backend arranque y funcione solo con
    // Supabase/Postgres. Volver a poner REDIS_ENABLED = true (y reactivar
    // NotificationDispatchWorker) cuando haya un Redis disponible.
    private static final boolean REDIS_ENABLED = false;

    private final StringRedisTemplate redisTemplate;
    private final AppProperties appProperties;

    private String readyKey() {
        return appProperties.notifications().redisStreamKey() + ":ready";
    }

    @SneakyThrows
    public void enqueue(UUID flightId, NotificationChannel channel, String recipient, String template, Map<String, Object> payload) {
        if (recipient == null || recipient.isBlank()) {
            log.warn("Se omite notificacion {} para vuelo {}: sin destinatario configurado", channel, flightId);
            return;
        }
        if (!REDIS_ENABLED) {
            log.debug("Redis desactivado temporalmente: se omite el encolado de la notificacion {} para vuelo {}", channel, flightId);
            return;
        }
        NotificationJob job = new NotificationJob(UUID.randomUUID(), flightId, channel, recipient, template, payload, 0);
        redisTemplate.opsForList().rightPush(readyKey(), OBJECT_MAPPER.writeValueAsString(job));
    }
}
