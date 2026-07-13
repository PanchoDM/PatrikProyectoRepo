package com.example.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Paso 1: el operador ingresa el numero de vuelo. Debe iniciar con LA o UC. */
public record CreateFlightRequest(
        @NotBlank
        @Pattern(regexp = "^(LA|UC)\\d{2,4}$", message = "El numero de vuelo debe iniciar con LA o UC seguido de 2 a 4 digitos")
        String flightNumber
) {
}
