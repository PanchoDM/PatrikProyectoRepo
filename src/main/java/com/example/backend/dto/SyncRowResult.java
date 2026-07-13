package com.example.backend.dto;

public record SyncRowResult(int fila, String flightNumber, SyncRowStatus status, String mensaje) {

    public enum SyncRowStatus {
        CREADO,
        ACTUALIZADO,
        OMITIDO,
        ERROR
    }
}
