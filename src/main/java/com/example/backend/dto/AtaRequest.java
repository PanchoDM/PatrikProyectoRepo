package com.example.backend.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/** Hora real de arribo (Timer 1), ingresada por el operador — no necesariamente "ahora". */
public record AtaRequest(@NotNull Instant ata) {
}
