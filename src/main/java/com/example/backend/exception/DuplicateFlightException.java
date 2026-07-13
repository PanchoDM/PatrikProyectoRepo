package com.example.backend.exception;

public class DuplicateFlightException extends RuntimeException {
    public DuplicateFlightException(String flightNumber) {
        super("El vuelo " + flightNumber + " ya esta registrado y activo");
    }
}
