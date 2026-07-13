package com.example.backend.dto;

import java.util.List;

public record SyncResultDto(
        int totalFilas,
        long creados,
        long actualizados,
        long omitidos,
        long errores,
        List<SyncRowResult> detalle
) {
}
