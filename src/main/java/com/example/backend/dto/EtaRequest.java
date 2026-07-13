package com.example.backend.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/** Paso 2: hora estimada de llegada, habilitada solo tras validar el prefijo. */
public record EtaRequest(@NotNull Instant eta) {
}
