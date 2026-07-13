package com.example.backend.domain.enums;

/** Maquina de estados secuencial del vuelo. */
public enum FlightStatus {
    ESPERANDO_ETA,
    ESPERANDO_ATA,
    EN_DESCARGA,
    ESPERANDO_NFD,
    EN_TARJA,
    COMPLETADO,
    VENCIDO
}
