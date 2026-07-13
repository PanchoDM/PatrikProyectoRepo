package com.example.backend.service;

import com.example.backend.config.AppProperties;
import com.example.backend.config.CacheConfig;
import com.example.backend.domain.entity.AuditLog;
import com.example.backend.domain.entity.Flight;
import com.example.backend.domain.enums.FlightPrefix;
import com.example.backend.domain.enums.FlightStatus;
import com.example.backend.dto.FlightStateDto;
import com.example.backend.dto.FlightStatsDto;
import com.example.backend.dto.FlightSyncRow;
import com.example.backend.dto.SyncRowResult;
import com.example.backend.dto.SyncRowResult.SyncRowStatus;
import com.example.backend.exception.DuplicateFlightException;
import com.example.backend.exception.FlightNotFoundException;
import com.example.backend.exception.InvalidFlightStateException;
import com.example.backend.repository.FlightRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Logica de negocio de la maquina de estados secuencial del vuelo. Cada
 * transicion valida el estado actual, calcula los deadlines absolutos de
 * los temporizadores derivados y deja constancia en la auditoria.
 */
@Service
@RequiredArgsConstructor
public class FlightService {

    private final FlightRepository flightRepository;
    private final FlightStateMapper flightStateMapper;
    private final WebSocketBroadcastService broadcastService;
    private final AuditService auditService;
    private final AppProperties appProperties;

    @Transactional
    @CacheEvict(value = CacheConfig.FLIGHTS_CACHE, allEntries = true)
    public FlightStateDto createFlight(String rawFlightNumber, UUID createdBy, HttpServletRequest request) {
        return createFlight(rawFlightNumber, null, true, createdBy, request);
    }

    @Transactional
    @CacheEvict(value = CacheConfig.FLIGHTS_CACHE, allEntries = true)
    public FlightStateDto createFlight(String rawFlightNumber, String origen, UUID createdBy, HttpServletRequest request) {
        return createFlight(rawFlightNumber, origen, true, createdBy, request);
    }

    /**
     * @param conCarga "Tipo de Vuelo" de origen (Google Sheets): true = con
     *                 carga (flujo completo), false = sin carga (flujo
     *                 simplificado, termina al registrar ATA). Los vuelos
     *                 creados manualmente desde la UI siempre son true.
     */
    @Transactional
    @CacheEvict(value = CacheConfig.FLIGHTS_CACHE, allEntries = true)
    public FlightStateDto createFlight(String rawFlightNumber, String origen, boolean conCarga, UUID createdBy, HttpServletRequest request) {
        String flightNumber = rawFlightNumber.trim().toUpperCase();
        FlightPrefix prefix = FlightPrefix.fromFlightNumber(flightNumber);

        if (flightRepository.existsByFlightNumber(flightNumber)) {
            throw new DuplicateFlightException(flightNumber);
        }

        Instant now = Instant.now();
        Flight flight = new Flight();
        flight.setFlightNumber(flightNumber);
        flight.setOrigen(origen);
        flight.setConCarga(conCarga);
        flight.setPrefix(prefix);
        flight.setStatus(FlightStatus.ESPERANDO_ETA);
        flight.setCreatedBy(createdBy);
        flight.setCreatedAt(now);
        flight.setUpdatedAt(now);
        flight = flightRepository.save(flight);

        auditService.record(request, flight.getId(), "VUELO_CREADO",
                Map.of("flightNumber", flightNumber, "conCarga", conCarga));
        return publish(flight);
    }

    @Transactional
    @CacheEvict(value = CacheConfig.FLIGHTS_CACHE, allEntries = true)
    public FlightStateDto updateOrigen(UUID flightId, String origen, HttpServletRequest request) {
        Flight flight = getOrThrow(flightId);
        flight.setOrigen(origen);
        flightRepository.save(flight);

        auditService.record(request, flightId, "ORIGEN_ACTUALIZADO", Map.of("origen", String.valueOf(origen)));
        return publish(flight);
    }

    @Transactional
    @CacheEvict(value = CacheConfig.FLIGHTS_CACHE, allEntries = true)
    public FlightStateDto setEta(UUID flightId, Instant eta, HttpServletRequest request) {
        Flight flight = getOrThrow(flightId);
        requireStatus(flight, FlightStatus.ESPERANDO_ETA);

        flight.setEta(eta);
        flight.setAtaDeadline(eta.plus(appProperties.timers().ataWindowMinutes(), ChronoUnit.MINUTES));
        flight.setStatus(FlightStatus.ESPERANDO_ATA);
        flightRepository.save(flight);

        auditService.record(request, flightId, "ETA_REGISTRADA", Map.of("eta", eta.toString()));
        return publish(flight);
    }

    /**
     * @param ata Hora real de arribo ingresada por el operador (no
     *            necesariamente el instante en que se presiona el boton: el
     *            operador puede corregirla si registra el ATA unos minutos
     *            despues de que ocurrio). Todos los temporizadores
     *            posteriores (descarga, RCF) se calculan a partir de esta
     *            hora, no de "ahora".
     */
    @Transactional
    @CacheEvict(value = CacheConfig.FLIGHTS_CACHE, allEntries = true)
    public FlightStateDto registerAta(UUID flightId, Instant ata, HttpServletRequest request) {
        Flight flight = getOrThrow(flightId);
        requireStatus(flight, FlightStatus.ESPERANDO_ATA);

        if (ata.isAfter(Instant.now().plusSeconds(60))) {
            throw new IllegalArgumentException("La hora de ATA no puede ser futura");
        }

        flight.setAta(ata);

        if (!flight.isConCarga()) {
            // CASO A - vuelo "sin carga": flujo simplificado, termina aqui
            // mismo. No hay descarga, RCF, NFD ni Tarja.
            flight.setStatus(FlightStatus.COMPLETADO);
            flightRepository.save(flight);
            auditService.record(request, flightId, "ATA_REGISTRADA_SIN_CARGA", Map.of("ata", ata.toString()));
            return publish(flight);
        }

        // CASO B - vuelo "con carga": flujo completo, se activan Timer 2 y Timer 3.
        boolean isLa = flight.getPrefix() == FlightPrefix.LA;
        int descargaMinutes = isLa ? appProperties.timers().descargaMinutesLa() : appProperties.timers().descargaMinutesUc();
        int rcfHours = isLa ? appProperties.timers().rcfHoursLa() : appProperties.timers().rcfHoursUc();

        flight.setDescargaDeadline(ata.plus(descargaMinutes, ChronoUnit.MINUTES));
        flight.setRcfDeadline(ata.plus(rcfHours, ChronoUnit.HOURS));
        flight.setStatus(FlightStatus.EN_DESCARGA);
        flightRepository.save(flight);

        auditService.record(request, flightId, "ATA_REGISTRADA", Map.of("ata", ata.toString()));
        return publish(flight);
    }

    @Transactional
    @CacheEvict(value = CacheConfig.FLIGHTS_CACHE, allEntries = true)
    public FlightStateDto confirmDescarga(UUID flightId, HttpServletRequest request) {
        Flight flight = getOrThrow(flightId);
        requireStatus(flight, FlightStatus.EN_DESCARGA);

        Instant now = Instant.now();
        flight.setDescargaConfirmedAt(now);
        flight.setStatus(FlightStatus.ESPERANDO_NFD);
        flightRepository.save(flight);

        auditService.record(request, flightId, "DESCARGA_CONFIRMADA", Map.of("descargaConfirmedAt", now.toString()));
        return publish(flight);
    }

    @Transactional
    @CacheEvict(value = CacheConfig.FLIGHTS_CACHE, allEntries = true)
    public FlightStateDto submitNfd(UUID flightId, boolean anticipado, boolean correos, HttpServletRequest request) {
        Flight flight = getOrThrow(flightId);
        requireStatus(flight, FlightStatus.ESPERANDO_NFD);

        Instant now = Instant.now();
        flight.setNfdAnticipado(anticipado);
        flight.setNfdCorreos(correos);
        flight.setNfdAnsweredAt(now);

        if (anticipado || correos) {
            flight.setTarjaDeadline(now.plus(appProperties.timers().tarjaMinutes(), ChronoUnit.MINUTES));
            flight.setStatus(FlightStatus.EN_TARJA);
        } else {
            flight.setStatus(FlightStatus.COMPLETADO);
        }
        flightRepository.save(flight);

        auditService.record(request, flightId, "NFD_RESPONDIDO",
                Map.of("anticipado", anticipado, "correos", correos));
        return publish(flight);
    }

    @Transactional
    @CacheEvict(value = CacheConfig.FLIGHTS_CACHE, allEntries = true)
    public FlightStateDto completeTarja(UUID flightId, HttpServletRequest request) {
        Flight flight = getOrThrow(flightId);
        requireStatus(flight, FlightStatus.EN_TARJA);

        Instant now = Instant.now();
        flight.setTarjaCompletedAt(now);
        flight.setStatus(FlightStatus.COMPLETADO);
        flightRepository.save(flight);

        auditService.record(request, flightId, "TARJA_COMPLETADA", Map.of("tarjaCompletedAt", now.toString()));
        return publish(flight);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = CacheConfig.FLIGHTS_CACHE, key = "#flightId")
    public FlightStateDto getState(UUID flightId) {
        return flightStateMapper.toDto(getOrThrow(flightId), Instant.now());
    }

    @Transactional(readOnly = true)
    @Cacheable(value = CacheConfig.FLIGHTS_CACHE, key = "#flightNumber.trim().toUpperCase()")
    public Optional<FlightStateDto> findByFlightNumber(String flightNumber) {
        return flightRepository.findByFlightNumber(flightNumber.trim().toUpperCase())
                .map(f -> flightStateMapper.toDto(f, Instant.now()));
    }

    /**
     * Lectura mas frecuente del sistema (carga inicial de la Torre de
     * Control). Cache de 5s: suficiente para amortiguar rafagas de
     * reconexion sin servir datos visiblemente desactualizados, ya que el
     * estado en vivo real llega por WebSocket independientemente del cache.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = CacheConfig.FLIGHTS_CACHE, key = "'active'")
    public List<FlightStateDto> listActive() {
        Instant now = Instant.now();
        return flightRepository.findByStatusNotIn(List.of(FlightStatus.COMPLETADO)).stream()
                .map(f -> flightStateMapper.toDto(f, now))
                .toList();
    }

    /**
     * Vuelos por fecha (calendario): a diferencia de {@link #listActive()},
     * incluye cualquier estado (tambien COMPLETADO) porque el calendario es
     * una vista historica, no solo operativa del dia. El rango [from, to)
     * lo calcula el frontend en la zona horaria local del operador, para que
     * el dia de calendario que ve coincida exactamente con el que pide.
     */
    @Transactional(readOnly = true)
    public List<FlightStateDto> getByRange(Instant from, Instant to) {
        Instant now = Instant.now();
        return flightRepository.findByEtaBetweenOrderByEtaAsc(from, to).stream()
                .map(f -> flightStateMapper.toDto(f, now))
                .toList();
    }

    @Transactional(readOnly = true)
    public FlightStatsDto getStats() {
        Instant startOfDay = Instant.now().truncatedTo(ChronoUnit.DAYS);
        return new FlightStatsDto(flightRepository.countByCreatedAtGreaterThanEqual(startOfDay));
    }

    @Transactional(readOnly = true)
    public boolean existsByFlightNumber(String flightNumber) {
        return flightRepository.existsByFlightNumber(flightNumber.trim().toUpperCase());
    }

    /**
     * Version en lote de crear/actualizar vuelos para la sincronizacion
     * masiva desde CSV (potencialmente miles de filas). A diferencia de
     * llamar createFlight/setEta/updateOrigen fila por fila -que abre una
     * transaccion, invalida el cache completo y hace un broadcast por
     * separado por cada fila-, aca se resuelve todo el lote en una sola
     * transaccion: una unica consulta para traer los vuelos existentes, un
     * unico saveAll para las filas nuevas (las filas existentes se
     * actualizan por dirty checking) y una unica invalidacion de cache al
     * terminar el metodo. El broadcast en vivo por WebSocket se omite en
     * este camino: para un lote de miles de filas no tiene sentido empujar
     * una actualizacion granular por cada una, y el dashboard ya vuelve a
     * quedar consistente con la proxima lectura (cache de 5s).
     */
    @Transactional
    @CacheEvict(value = CacheConfig.FLIGHTS_CACHE, allEntries = true)
    public List<SyncRowResult> bulkUpsertFromSync(List<FlightSyncRow> rows, UUID actingUserId, HttpServletRequest request) {
        if (rows.isEmpty()) {
            return List.of();
        }

        Set<String> flightNumbers = rows.stream().map(FlightSyncRow::flightNumber).collect(Collectors.toSet());
        Map<String, Flight> existingByNumber = new HashMap<>();
        flightRepository.findByFlightNumberIn(flightNumbers).forEach(f -> existingByNumber.put(f.getFlightNumber(), f));

        Instant now = Instant.now();
        List<Flight> toInsert = new ArrayList<>();
        List<AuditLog> auditLogs = new ArrayList<>();
        List<SyncRowResult> results = new ArrayList<>(rows.size());

        for (FlightSyncRow row : rows) {
            Flight existing = existingByNumber.get(row.flightNumber());

            if (existing == null) {
                if (row.eta() == null) {
                    results.add(new SyncRowResult(row.fila(), row.flightNumber(), SyncRowStatus.ERROR, "Vuelo nuevo sin ETA"));
                    continue;
                }
                Flight flight = new Flight();
                flight.setFlightNumber(row.flightNumber());
                flight.setOrigen(row.origen());
                flight.setConCarga(row.conCarga());
                flight.setPrefix(FlightPrefix.fromFlightNumber(row.flightNumber()));
                flight.setStatus(FlightStatus.ESPERANDO_ATA);
                flight.setCreatedBy(actingUserId);
                flight.setCreatedAt(now);
                flight.setUpdatedAt(now);
                flight.setEta(row.eta());
                flight.setAtaDeadline(row.eta().plus(appProperties.timers().ataWindowMinutes(), ChronoUnit.MINUTES));

                toInsert.add(flight);
                existingByNumber.put(row.flightNumber(), flight);

                String flujo = row.conCarga() ? "flujo completo" : "flujo simplificado (sin carga)";
                results.add(new SyncRowResult(row.fila(), row.flightNumber(), SyncRowStatus.CREADO,
                        "Vuelo creado, ETA registrada (" + flujo + ")"));
                continue;
            }

            StringBuilder msg = new StringBuilder();
            if (row.origen() != null && !row.origen().equals(existing.getOrigen())) {
                existing.setOrigen(row.origen());
                existing.setUpdatedAt(now);
                auditLogs.add(auditService.build(request, existing.getId(), "ORIGEN_ACTUALIZADO",
                        Map.of("origen", row.origen())));
                msg.append("origen actualizado; ");
            }

            if (row.eta() != null && existing.getStatus() == FlightStatus.ESPERANDO_ETA) {
                existing.setEta(row.eta());
                existing.setAtaDeadline(row.eta().plus(appProperties.timers().ataWindowMinutes(), ChronoUnit.MINUTES));
                existing.setStatus(FlightStatus.ESPERANDO_ATA);
                existing.setUpdatedAt(now);
                auditLogs.add(auditService.build(request, existing.getId(), "ETA_REGISTRADA",
                        Map.of("eta", row.eta().toString())));
                msg.append("ETA registrada; ");
            }

            if (msg.isEmpty()) {
                results.add(new SyncRowResult(row.fila(), row.flightNumber(), SyncRowStatus.OMITIDO,
                        "El vuelo ya esta en proceso (" + existing.getStatus() + "); sin cambios"));
                continue;
            }
            results.add(new SyncRowResult(row.fila(), row.flightNumber(), SyncRowStatus.ACTUALIZADO, msg.toString().trim()));
        }

        flightRepository.saveAll(toInsert);
        for (Flight flight : toInsert) {
            auditLogs.add(auditService.build(request, flight.getId(), "VUELO_CREADO",
                    Map.of("flightNumber", flight.getFlightNumber(), "conCarga", flight.isConCarga())));
        }
        auditService.recordAll(auditLogs);

        return results;
    }

    private FlightStateDto publish(Flight flight) {
        FlightStateDto dto = flightStateMapper.toDto(flight, Instant.now());
        broadcastService.broadcastAll(dto);
        return dto;
    }

    private Flight getOrThrow(UUID flightId) {
        return flightRepository.findById(flightId).orElseThrow(() -> new FlightNotFoundException(flightId));
    }

    private void requireStatus(Flight flight, FlightStatus expected) {
        if (flight.getStatus() != expected) {
            throw new InvalidFlightStateException(
                    "El vuelo " + flight.getFlightNumber() + " esta en estado " + flight.getStatus()
                            + ", se esperaba " + expected);
        }
    }
}
