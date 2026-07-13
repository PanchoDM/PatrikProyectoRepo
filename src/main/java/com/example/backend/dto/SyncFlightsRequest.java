package com.example.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** URL publica de un CSV (ej. Google Sheets publicado como CSV) a sincronizar. */
public record SyncFlightsRequest(
        @NotBlank
        @Pattern(regexp = "^https://.+", message = "La URL debe ser https://")
        String csvUrl
) {
}
