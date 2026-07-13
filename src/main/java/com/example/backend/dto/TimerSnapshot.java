package com.example.backend.dto;

import com.example.backend.domain.enums.TimerType;

import java.time.Instant;

/**
 * Estado calculado de un temporizador en un instante dado. `remainingSeconds`
 * puede ser negativo cuando el temporizador ya vencio (para pintar el
 * contador en rojo parpadeante "0:00" / tiempo excedido en el frontend).
 */
public record TimerSnapshot(
        TimerType type,
        String label,
        Instant deadline,
        long totalSeconds,
        long remainingSeconds,
        TimerLevel level,
        boolean active
) {
    public enum TimerLevel {
        A_TIEMPO,   // verde
        PROXIMO,    // amarillo
        CRITICO,    // rojo
        VENCIDO     // rojo parpadeante
    }

    public static TimerSnapshot of(TimerType type, String label, Instant deadline, long totalSeconds, Instant now, boolean active) {
        long remaining = deadline == null ? 0 : deadline.getEpochSecond() - now.getEpochSecond();
        TimerLevel level;
        if (!active) {
            level = TimerLevel.A_TIEMPO;
        } else if (remaining <= 0) {
            level = TimerLevel.VENCIDO;
        } else if (totalSeconds > 0 && remaining <= totalSeconds * 0.20) {
            level = TimerLevel.CRITICO;
        } else if (totalSeconds > 0 && remaining <= totalSeconds * 0.50) {
            level = TimerLevel.PROXIMO;
        } else {
            level = TimerLevel.A_TIEMPO;
        }
        return new TimerSnapshot(type, label, deadline, totalSeconds, remaining, level, active);
    }
}
