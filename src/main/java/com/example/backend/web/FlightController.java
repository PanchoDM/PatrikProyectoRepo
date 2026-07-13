package com.example.backend.web;

import com.example.backend.dto.AtaRequest;
import com.example.backend.dto.CreateFlightRequest;
import com.example.backend.dto.EtaRequest;
import com.example.backend.dto.FlightStateDto;
import com.example.backend.dto.FlightStatsDto;
import com.example.backend.dto.NfdAnswerRequest;
import com.example.backend.security.CurrentUser;
import com.example.backend.service.FlightService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Botones de accion del operador (Torre de Control). Cada endpoint valida
 * la transicion de estado en FlightService y queda registrado en la
 * auditoria inmutable.
 */
@RestController
@RequestMapping("/api/flights")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OPERADOR', 'SUPERVISOR', 'ADMIN')")
public class FlightController {

    private final FlightService flightService;

    @GetMapping
    public List<FlightStateDto> listActive() {
        return flightService.listActive();
    }

    @GetMapping("/stats")
    public FlightStatsDto getStats() {
        return flightService.getStats();
    }

    /**
     * Vuelos por fecha para el calendario. [from, to) los calcula el
     * frontend en la zona horaria local del operador (limites del dia o del
     * mes visible), asi que aqui solo se valida un rango razonable para
     * evitar consultas abusivas.
     */
    @GetMapping("/by-range")
    public List<FlightStateDto> getByRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("'to' debe ser posterior a 'from'");
        }
        if (Duration.between(from, to).toDays() > 370) {
            throw new IllegalArgumentException("El rango de fechas no puede superar un año");
        }
        return flightService.getByRange(from, to);
    }

    @GetMapping("/{id}")
    public FlightStateDto getState(@PathVariable UUID id) {
        return flightService.getState(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FlightStateDto createFlight(@Valid @RequestBody CreateFlightRequest body,
                                        @AuthenticationPrincipal CurrentUser currentUser,
                                        HttpServletRequest request) {
        return flightService.createFlight(body.flightNumber(), currentUser.id(), request);
    }

    @PostMapping("/{id}/eta")
    public FlightStateDto setEta(@PathVariable UUID id, @Valid @RequestBody EtaRequest body, HttpServletRequest request) {
        return flightService.setEta(id, body.eta(), request);
    }

    @PostMapping("/{id}/ata")
    public FlightStateDto registerAta(@PathVariable UUID id, @Valid @RequestBody AtaRequest body, HttpServletRequest request) {
        return flightService.registerAta(id, body.ata(), request);
    }

    @PostMapping("/{id}/descarga")
    public FlightStateDto confirmDescarga(@PathVariable UUID id, HttpServletRequest request) {
        return flightService.confirmDescarga(id, request);
    }

    @PostMapping("/{id}/nfd")
    public FlightStateDto submitNfd(@PathVariable UUID id, @Valid @RequestBody NfdAnswerRequest body, HttpServletRequest request) {
        return flightService.submitNfd(id, body.anticipado(), body.correos(), request);
    }

    @PostMapping("/{id}/tarja")
    public FlightStateDto completeTarja(@PathVariable UUID id, HttpServletRequest request) {
        return flightService.completeTarja(id, request);
    }
}
