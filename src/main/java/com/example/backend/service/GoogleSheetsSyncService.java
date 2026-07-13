package com.example.backend.service;

import com.example.backend.domain.enums.FlightPrefix;
import com.example.backend.dto.FlightSyncRow;
import com.example.backend.dto.SyncResultDto;
import com.example.backend.dto.SyncRowResult;
import com.example.backend.dto.SyncRowResult.SyncRowStatus;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Importa/actualiza vuelos desde un CSV publico (tipicamente un Google
 * Sheet publicado via Archivo > Compartir > Publicar en la web > CSV).
 * Cada fila se procesa de forma independiente: un error en una fila no
 * detiene el resto del lote.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GoogleSheetsSyncService {

    private static final long MAX_CSV_BYTES = 2_000_000; // 2 MB
    private static final int SYNC_BATCH_SIZE = 500;

    private static final List<String> FLIGHT_COLUMN_ALIASES =
            List.of("numerodevuelo", "nvuelo", "nrovuelo", "vuelo", "flightnumber", "numero");
    private static final List<String> ORIGEN_COLUMN_ALIASES =
            List.of("origen", "origin", "procedencia", "aeropuertoorigen");
    private static final List<String> ETA_COLUMN_ALIASES =
            List.of("eta", "horaeta", "horaestimadadellegada", "horaestimada", "fechaeta", "fechayhoraeta", "fechahoraeta");
    private static final List<String> ESTADO_COLUMN_ALIASES =
            List.of("estado", "status", "estadovuelo");
    private static final List<String> TIPO_VUELO_COLUMN_ALIASES =
            List.of("tipodevuelo", "tipovuelo", "tipo", "tipocarga");

    private static final String ESTADO_CANCELADO = "cancelado";
    private static final String TIPO_CON_CARGA = "concarga";
    private static final String TIPO_SIN_CARGA = "sincarga";

    private static final List<DateTimeFormatter> ETA_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"),
            DateTimeFormatter.ofPattern("M/d/yyyy H:mm:ss"),
            DateTimeFormatter.ofPattern("M/d/yyyy H:mm")
    );

    private final FlightService flightService;
    private final AuditService auditService;

    private final RestClient restClient = buildRestClient();

    public SyncResultDto syncFromCsvUrl(String csvUrl, UUID actingUserId, HttpServletRequest request) {
        String csvBody = fetchCsv(csvUrl);
        List<SyncRowResult> results = new ArrayList<>();
        List<FlightSyncRow> pendingRows = new ArrayList<>();

        try (CSVParser parser = CSVParser.parse(new StringReader(csvBody), CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .build())) {

            Map<String, String> normalizedToOriginal = new LinkedHashMap<>();
            for (String original : parser.getHeaderNames()) {
                normalizedToOriginal.putIfAbsent(normalize(original), original);
            }

            String flightHeader = firstMatch(normalizedToOriginal, FLIGHT_COLUMN_ALIASES);
            String etaHeader = firstMatch(normalizedToOriginal, ETA_COLUMN_ALIASES);
            String origenHeader = firstMatch(normalizedToOriginal, ORIGEN_COLUMN_ALIASES);
            String estadoHeader = firstMatch(normalizedToOriginal, ESTADO_COLUMN_ALIASES);
            String tipoVueloHeader = firstMatch(normalizedToOriginal, TIPO_VUELO_COLUMN_ALIASES);

            if (flightHeader == null || etaHeader == null) {
                throw new IllegalArgumentException(
                        "El CSV debe tener una columna de numero de vuelo y una de ETA en la primera fila");
            }

            for (CSVRecord record : parser) {
                resolveRow(record, flightHeader, etaHeader, origenHeader, estadoHeader, tipoVueloHeader,
                        results, pendingRows);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("No se pudo leer el CSV: " + e.getMessage());
        }

        for (int i = 0; i < pendingRows.size(); i += SYNC_BATCH_SIZE) {
            List<FlightSyncRow> batch = pendingRows.subList(i, Math.min(i + SYNC_BATCH_SIZE, pendingRows.size()));
            try {
                results.addAll(flightService.bulkUpsertFromSync(batch, actingUserId, request));
            } catch (Exception e) {
                log.warn("Error procesando lote de sincronizacion CSV (filas {}-{}): {}",
                        batch.get(0).fila(), batch.get(batch.size() - 1).fila(), e.getMessage(), e);
                for (FlightSyncRow row : batch) {
                    results.add(new SyncRowResult(row.fila(), row.flightNumber(), SyncRowStatus.ERROR,
                            "Error de sincronizacion en lote: " + e.getMessage()));
                }
            }
        }
        results.sort(Comparator.comparingInt(SyncRowResult::fila));

        long creados = count(results, SyncRowStatus.CREADO);
        long actualizados = count(results, SyncRowStatus.ACTUALIZADO);
        long omitidos = count(results, SyncRowStatus.OMITIDO);
        long errores = count(results, SyncRowStatus.ERROR);

        auditService.record(request, null, "SYNC_GOOGLE_SHEETS", Map.of(
                "csvUrl", csvUrl, "filas", results.size(),
                "creados", creados, "actualizados", actualizados, "omitidos", omitidos, "errores", errores));

        return new SyncResultDto(results.size(), creados, actualizados, omitidos, errores, results);
    }

    /**
     * Primera pasada, sin tocar la base de datos: valida y parsea cada fila
     * del CSV. Las filas invalidas/canceladas resuelven su resultado aca
     * mismo; el resto se acumula en {@code pendingRows} para conciliarse
     * contra la base de datos en lote (ver {@link #syncFromCsvUrl}).
     */
    private void resolveRow(CSVRecord record, String flightHeader, String etaHeader, String origenHeader,
                             String estadoHeader, String tipoVueloHeader,
                             List<SyncRowResult> results, List<FlightSyncRow> pendingRows) {
        int rowNum = (int) record.getRecordNumber();
        String flightNumber = "";
        try {
            if (estadoHeader != null && record.isSet(estadoHeader)
                    && ESTADO_CANCELADO.equals(normalize(record.get(estadoHeader)))) {
                results.add(new SyncRowResult(rowNum, flightNumber, SyncRowStatus.OMITIDO, "Vuelo cancelado, fila ignorada"));
                return;
            }

            String rawFlightNumber = record.isSet(flightHeader) ? record.get(flightHeader) : null;
            if (rawFlightNumber == null || rawFlightNumber.isBlank()) {
                results.add(new SyncRowResult(rowNum, "", SyncRowStatus.ERROR, "Fila sin numero de vuelo"));
                return;
            }
            flightNumber = rawFlightNumber.trim().toUpperCase();

            if (!FlightPrefix.FLIGHT_NUMBER_PATTERN.matcher(flightNumber).matches()) {
                results.add(new SyncRowResult(rowNum, flightNumber, SyncRowStatus.ERROR,
                        "Numero de vuelo invalido (debe iniciar con LA o UC)"));
                return;
            }

            String origen = origenHeader != null && record.isSet(origenHeader)
                    ? trimToNull(record.get(origenHeader)) : null;
            String rawEta = record.isSet(etaHeader) ? record.get(etaHeader) : null;
            boolean conCarga = resolveConCarga(record, tipoVueloHeader);

            Instant eta = null;
            if (rawEta != null && !rawEta.isBlank()) {
                eta = parseEta(rawEta);
            }

            pendingRows.add(new FlightSyncRow(rowNum, flightNumber, origen, eta, conCarga));
        } catch (Exception e) {
            log.warn("Error procesando fila {} de sincronizacion CSV: {}", rowNum, e.getMessage());
            results.add(new SyncRowResult(rowNum, flightNumber, SyncRowStatus.ERROR, e.getMessage()));
        }
    }

    /**
     * "Tipo de Vuelo": "con carga" -> flujo completo (true), "sin carga" ->
     * flujo simplificado (false). Si la columna no existe o el valor no se
     * reconoce, se asume "con carga" (mas conservador: mejor rastrear de mas
     * que perder el seguimiento de descarga/RCF de un vuelo que si la traia).
     */
    private boolean resolveConCarga(CSVRecord record, String tipoVueloHeader) {
        if (tipoVueloHeader == null || !record.isSet(tipoVueloHeader)) {
            return true;
        }
        String normalized = normalize(record.get(tipoVueloHeader));
        if (TIPO_SIN_CARGA.equals(normalized)) {
            return false;
        }
        if (TIPO_CON_CARGA.equals(normalized)) {
            return true;
        }
        return true;
    }

    private Instant parseEta(String raw) {
        String trimmed = raw.trim();
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            // no era ISO instant, se prueba con los formatos locales de abajo
        }
        for (DateTimeFormatter formatter : ETA_FORMATTERS) {
            try {
                return LocalDateTime.parse(trimmed, formatter).atZone(ZoneId.systemDefault()).toInstant();
            } catch (DateTimeParseException ignored) {
                // se intenta con el siguiente formato soportado
            }
        }
        throw new IllegalArgumentException("Formato de fecha/hora ETA no reconocido: '" + raw + "'");
    }

    private String fetchCsv(String csvUrl) {
        if (csvUrl == null || !csvUrl.startsWith("https://")) {
            throw new IllegalArgumentException("La URL del CSV debe iniciar con https://");
        }
        return restClient.get()
                .uri(csvUrl)
                .exchange((req, resp) -> {
                    if (!resp.getStatusCode().is2xxSuccessful()) {
                        throw new IllegalArgumentException("El servidor de la URL respondio " + resp.getStatusCode());
                    }
                    try (InputStream in = resp.getBody()) {
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                        byte[] chunk = new byte[8192];
                        long total = 0;
                        int n;
                        while ((n = in.read(chunk)) != -1) {
                            total += n;
                            if (total > MAX_CSV_BYTES) {
                                throw new IllegalArgumentException("El CSV excede el tamano maximo permitido (2 MB)");
                            }
                            buffer.write(chunk, 0, n);
                        }
                        return buffer.toString(StandardCharsets.UTF_8);
                    }
                });
    }

    private static RestClient buildRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        return RestClient.builder().requestFactory(factory).build();
    }

    private static String firstMatch(Map<String, String> normalizedToOriginal, List<String> aliases) {
        for (String alias : aliases) {
            String original = normalizedToOriginal.get(alias);
            if (original != null) {
                return original;
            }
        }
        return null;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        return decomposed.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static long count(List<SyncRowResult> results, SyncRowStatus status) {
        return results.stream().filter(r -> r.status() == status).count();
    }
}
