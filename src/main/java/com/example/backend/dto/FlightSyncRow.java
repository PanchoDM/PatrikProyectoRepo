package com.example.backend.dto;

import java.time.Instant;

/** Fila de CSV ya validada/parseada, lista para conciliar contra la base de datos en lote. */
public record FlightSyncRow(int fila, String flightNumber, String origen, Instant eta, boolean conCarga) {
}
