package com.example.backend.exception;

import java.util.UUID;

public class FlightNotFoundException extends RuntimeException {
    public FlightNotFoundException(UUID id) {
        super("Vuelo no encontrado: " + id);
    }
}
