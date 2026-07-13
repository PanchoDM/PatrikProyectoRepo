package com.example.backend.dto;

import jakarta.validation.constraints.NotNull;

/** Respuestas obligatorias del cuestionario NFD. */
public record NfdAnswerRequest(
        @NotNull Boolean anticipado,
        @NotNull Boolean correos
) {
}
