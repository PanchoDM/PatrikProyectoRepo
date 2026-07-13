package com.example.backend.dto;

import com.example.backend.domain.enums.FlightPrefix;
import com.example.backend.domain.enums.FlightStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Foto completa del vuelo + sus temporizadores activos, para REST y WebSocket. */
public record FlightStateDto(
        UUID id,
        String flightNumber,
        String origen,
        boolean conCarga,
        FlightPrefix prefix,
        FlightStatus status,
        Instant eta,
        Instant ata,
        Instant descargaConfirmedAt,
        Boolean nfdAnticipado,
        Boolean nfdCorreos,
        Instant tarjaCompletedAt,
        List<TimerSnapshot> timers
) {
}
