package com.example.backend.exception;

public class InvalidFlightStateException extends RuntimeException {
    public InvalidFlightStateException(String message) {
        super(message);
    }
}
