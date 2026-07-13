package com.example.backend.web;

import com.example.backend.dto.SyncFlightsRequest;
import com.example.backend.dto.SyncResultDto;
import com.example.backend.security.CurrentUser;
import com.example.backend.service.GoogleSheetsSyncService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sincronizacion masiva de vuelos desde un CSV publico (ej. Google Sheets). */
@RestController
@RequestMapping("/api/flights")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OPERADOR', 'SUPERVISOR', 'ADMIN')")
public class FlightSyncController {

    private final GoogleSheetsSyncService googleSheetsSyncService;

    @PostMapping("/sync")
    public SyncResultDto sync(@Valid @RequestBody SyncFlightsRequest body,
                               @AuthenticationPrincipal CurrentUser currentUser,
                               HttpServletRequest request) {
        return googleSheetsSyncService.syncFromCsvUrl(body.csvUrl(), currentUser.id(), request);
    }
}
