package com.example.backend.domain.enums;

import java.util.regex.Pattern;

/**
 * Prefijos de aerolinea soportados. La regex exige que el numero de vuelo
 * empiece exactamente con uno de estos codigos.
 */
public enum FlightPrefix {
    LA,
    UC;

    public static final Pattern FLIGHT_NUMBER_PATTERN = Pattern.compile("^(LA|UC)\\d{2,4}$");

    public static FlightPrefix fromFlightNumber(String flightNumber) {
        if (flightNumber == null || !FLIGHT_NUMBER_PATTERN.matcher(flightNumber.trim().toUpperCase()).matches()) {
            throw new IllegalArgumentException(
                    "Numero de vuelo invalido: debe iniciar con LA o UC seguido de 2 a 4 digitos");
        }
        return valueOf(flightNumber.trim().substring(0, 2).toUpperCase());
    }
}
