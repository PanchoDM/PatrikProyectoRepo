package com.example.backend.dto;

/**
 * Solo el conteo del dia (requiere la BD porque incluye vuelos ya
 * COMPLETADO, que el frontend descarta de su lista de "activos" en tiempo
 * real). El resto de las metricas del dashboard (en proceso, criticos,
 * eficiencia) se derivan en el cliente a partir de los vuelos activos que
 * ya tiene en memoria via WebSocket, para no duplicar la logica de niveles
 * de severidad en dos lenguajes.
 */
public record FlightStatsDto(long flightsHoy) {
}
