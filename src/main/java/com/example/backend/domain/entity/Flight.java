package com.example.backend.domain.entity;

import com.example.backend.domain.enums.FlightPrefix;
import com.example.backend.domain.enums.FlightStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Vuelo con su maquina de estados y los timestamps absolutos (deadlines) de
 * cada temporizador. Usar deadlines absolutos -en vez de "segundos restantes"-
 * es lo que permite que el sistema sobreviva a un reinicio del backend sin
 * perder precision: en cada tick simplemente se recalcula `deadline - now()`.
 */
@Entity
@Table(name = "flights")
@Getter
@Setter
@NoArgsConstructor
public class Flight {

    @Id
    @GeneratedValue
    private UUID id;

    // Unico solo mientras el vuelo sigue en curso (indice parcial en DB,
    // ver V4__flight_number_unique_only_when_active.sql); un mismo numero
    // se reutiliza dia a dia una vez que el vuelo anterior esta COMPLETADO.
    @Column(name = "flight_number", nullable = false)
    private String flightNumber;

    private String origen;

    /**
     * "Tipo de Vuelo" de la hoja de origen: true = con carga (flujo completo,
     * con descarga/RCF/NFD/Tarja), false = sin carga (flujo simplificado, el
     * proceso termina al registrar el ATA). Default true para preservar el
     * flujo completo en los vuelos creados manualmente desde la UI.
     */
    @Column(name = "con_carga", nullable = false)
    private boolean conCarga = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FlightPrefix prefix;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FlightStatus status = FlightStatus.ESPERANDO_ETA;

    private Instant eta;
    private Instant ata;

    @Column(name = "ata_deadline")
    private Instant ataDeadline;

    @Column(name = "ata_escalated_at")
    private Instant ataEscalatedAt;

    @Column(name = "descarga_confirmed_at")
    private Instant descargaConfirmedAt;

    @Column(name = "descarga_deadline")
    private Instant descargaDeadline;

    @Column(name = "descarga_escalated_at")
    private Instant descargaEscalatedAt;

    @Column(name = "rcf_deadline")
    private Instant rcfDeadline;

    @Column(name = "rcf_alert_sent_at")
    private Instant rcfAlertSentAt;

    @Column(name = "nfd_anticipado")
    private Boolean nfdAnticipado;

    @Column(name = "nfd_correos")
    private Boolean nfdCorreos;

    @Column(name = "nfd_answered_at")
    private Instant nfdAnsweredAt;

    @Column(name = "tarja_deadline")
    private Instant tarjaDeadline;

    @Column(name = "tarja_completed_at")
    private Instant tarjaCompletedAt;

    @Column(name = "tarja_alert_sent_at")
    private Instant tarjaAlertSentAt;

    @Column(name = "tarja_escalated_at")
    private Instant tarjaEscalatedAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;
}
