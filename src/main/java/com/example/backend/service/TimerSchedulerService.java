package com.example.backend.service;

import com.example.backend.config.AppProperties;
import com.example.backend.domain.entity.AppUser;
import com.example.backend.domain.entity.Flight;
import com.example.backend.domain.enums.FlightStatus;
import com.example.backend.domain.enums.NotificationChannel;
import com.example.backend.domain.enums.UserRole;
import com.example.backend.notification.NotificationQueueService;
import com.example.backend.repository.AppUserRepository;
import com.example.backend.repository.FlightRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ticker central: en cada tick recalcula `deadline - now()` para todos los
 * vuelos activos (sin mantener contadores en memoria) y publica el estado
 * por WebSocket. Como los deadlines son timestamps absolutos guardados en
 * Supabase, un reinicio del backend no pierde precision: al arrancar
 * simplemente se retoma el calculo desde la base de datos.
 *
 * Tambien dispara las alertas omnicanal encolandolas en Redis
 * (NotificationQueueService), que persisten aunque el backend se reinicie
 * antes de que el worker las procese.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TimerSchedulerService {

    private static final List<FlightStatus> ACTIVE_STATUSES = List.of(
            FlightStatus.ESPERANDO_ATA, FlightStatus.EN_DESCARGA,
            FlightStatus.ESPERANDO_NFD, FlightStatus.EN_TARJA);

    private final FlightRepository flightRepository;
    private final AppUserRepository appUserRepository;
    private final FlightStateMapper flightStateMapper;
    private final WebSocketBroadcastService broadcastService;
    private final NotificationQueueService notificationQueueService;
    private final AppProperties appProperties;

    @Scheduled(fixedDelayString = "${app.timers.scheduler-tick-ms:1000}")
    @Transactional
    public void tick() {
        Instant now = Instant.now();
        List<Flight> activeFlights = flightRepository.findByStatusNotIn(
                List.of(FlightStatus.COMPLETADO, FlightStatus.VENCIDO, FlightStatus.ESPERANDO_ETA));

        if (activeFlights.isEmpty()) {
            return;
        }

        Set<UUID> operatorIds = new HashSet<>();
        for (Flight flight : activeFlights) {
            if (flight.getCreatedBy() != null) {
                operatorIds.add(flight.getCreatedBy());
            }
        }
        Map<UUID, AppUser> operators = appUserRepository.findAllById(operatorIds).stream()
                .collect(java.util.stream.Collectors.toMap(AppUser::getId, u -> u));

        List<AppUser> supervisors = appUserRepository.findByRoleInAndActiveTrue(List.of(UserRole.SUPERVISOR, UserRole.ADMIN));

        for (Flight flight : activeFlights) {
            evaluateRcfPreAlert(flight, now, operators.get(flight.getCreatedBy()));
            evaluateAtaEscalation(flight, now, supervisors);
            evaluateDescargaEscalation(flight, now, supervisors);
            evaluateTarjaExpiry(flight, now, operators.get(flight.getCreatedBy()), supervisors);

            flightRepository.save(flight);
            broadcastService.broadcastAll(flightStateMapper.toDto(flight, now));
        }
    }

    /** Timer 2: WhatsApp 5 minutos antes de que venza el plazo RCF. */
    private void evaluateRcfPreAlert(Flight flight, Instant now, AppUser operator) {
        if (flight.getRcfDeadline() == null || flight.getRcfAlertSentAt() != null) {
            return;
        }
        boolean rcfApplicable = flight.getStatus() == FlightStatus.EN_DESCARGA
                || flight.getStatus() == FlightStatus.ESPERANDO_NFD
                || flight.getStatus() == FlightStatus.EN_TARJA;
        if (!rcfApplicable) {
            return;
        }
        Instant preAlertAt = flight.getRcfDeadline().minus(appProperties.timers().rcfPreAlertMinutes(), ChronoUnit.MINUTES);
        if (now.isBefore(preAlertAt)) {
            return;
        }
        flight.setRcfAlertSentAt(now);
        String message = "Alerta de Pendiente RCF: el vuelo " + flight.getFlightNumber()
                + " vence en " + appProperties.timers().rcfPreAlertMinutes() + " minutos.";
        enqueueWhatsApp(flight, operator, "RCF_PRE_ALERT", message);
    }

    /** Timer 1 vencido sin registrar ATA: escalamiento por correo a supervisores. */
    private void evaluateAtaEscalation(Flight flight, Instant now, List<AppUser> supervisors) {
        if (flight.getStatus() != FlightStatus.ESPERANDO_ATA
                || flight.getAtaDeadline() == null
                || flight.getAtaEscalatedAt() != null
                || now.isBefore(flight.getAtaDeadline())) {
            return;
        }
        flight.setAtaEscalatedAt(now);
        enqueueEscalationEmail(flight, supervisors, "Registro de ATA vencido",
                "El vuelo " + flight.getFlightNumber() + " no registro su ATA dentro de la ventana de "
                        + appProperties.timers().ataWindowMinutes() + " minutos desde el ETA.");
    }

    /** Timer 3 vencido sin confirmar termino de descarga: escalamiento por correo. */
    private void evaluateDescargaEscalation(Flight flight, Instant now, List<AppUser> supervisors) {
        if (flight.getStatus() != FlightStatus.EN_DESCARGA
                || flight.getDescargaDeadline() == null
                || flight.getDescargaEscalatedAt() != null
                || now.isBefore(flight.getDescargaDeadline())) {
            return;
        }
        flight.setDescargaEscalatedAt(now);
        enqueueEscalationEmail(flight, supervisors, "Termino de descarga vencido",
                "El vuelo " + flight.getFlightNumber() + " no confirmo el termino de descarga dentro del plazo asignado.");
    }

    /** Timer 5 vencido sin completar la Tarja: WhatsApp al operador + escalamiento a supervisores. */
    private void evaluateTarjaExpiry(Flight flight, Instant now, AppUser operator, List<AppUser> supervisors) {
        if (flight.getStatus() != FlightStatus.EN_TARJA || flight.getTarjaDeadline() == null
                || now.isBefore(flight.getTarjaDeadline())) {
            return;
        }
        if (flight.getTarjaAlertSentAt() == null) {
            flight.setTarjaAlertSentAt(now);
            enqueueWhatsApp(flight, operator, "TARJA_VENCIDA",
                    "La Tarja del vuelo " + flight.getFlightNumber() + " vencio. Complete la tarea de inmediato.");
        }
        if (flight.getTarjaEscalatedAt() == null) {
            flight.setTarjaEscalatedAt(now);
            enqueueEscalationEmail(flight, supervisors, "Tarja vencida sin completar",
                    "El vuelo " + flight.getFlightNumber() + " vencio su temporizador de Tarja (15 min) sin que el operador la completara.");
        }
    }

    private void enqueueWhatsApp(Flight flight, AppUser operator, String template, String message) {
        if (operator == null || operator.getPhoneE164() == null || operator.getPhoneE164().isBlank()) {
            log.warn("Vuelo {}: no hay telefono de operador configurado para WhatsApp ({})", flight.getFlightNumber(), template);
            return;
        }
        notificationQueueService.enqueue(flight.getId(), NotificationChannel.WHATSAPP, operator.getPhoneE164(),
                template, Map.of("message", message));
    }

    private void enqueueEscalationEmail(Flight flight, List<AppUser> supervisors, String subject, String message) {
        List<String> recipients = new java.util.ArrayList<>();
        supervisors.forEach(s -> recipients.add(s.getEmail()));
        recipients.addAll(appProperties.notifications().email().escalationList());

        for (String recipient : recipients) {
            notificationQueueService.enqueue(flight.getId(), NotificationChannel.EMAIL, recipient,
                    "ESCALAMIENTO", Map.of("subject", subject, "message", message));
        }
    }
}
