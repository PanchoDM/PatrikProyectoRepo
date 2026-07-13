package com.example.backend.security;

import com.example.backend.service.AuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Hardening contra SQLi avanzada explotada via cabeceras HTTP (no solo
 * body/params, que ya estan a salvo por JPA/Hibernate con consultas
 * parametrizadas). Herramientas como sqlmap prueban payloads de inyeccion
 * ciega basada en tiempo (OR SLEEP(5), WAITFOR DELAY, BENCHMARK(...)) contra
 * cabeceras que muchas aplicaciones reflejan o registran sin sanitizar
 * (Referer, User-Agent, Cookie, X-Forwarded-For). Este filtro corre antes
 * que cualquier otro (incluida la autenticacion) y rechaza la solicitud si
 * detecta un patron de inyeccion conocido, dejando constancia en la
 * auditoria inmutable.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HeaderSanitizationFilter extends OncePerRequestFilter {

    private static final List<String> HEADERS_TO_INSPECT =
            List.of("Referer", "User-Agent", "Cookie", "X-Forwarded-For", "Origin");

    /**
     * Firmas especificas de SQLi (no simple puntuacion) para minimizar falsos
     * positivos contra cabeceras legitimas (los User-Agent reales traen
     * parentesis y punto y coma, por ejemplo).
     */
    private static final Pattern SQLI_PATTERN = Pattern.compile(
            "(?i)(\\bunion\\b\\s+(all\\s+)?\\bselect\\b" +
            "|\\bsleep\\s*\\(\\s*\\d" +
            "|\\bbenchmark\\s*\\(" +
            "|\\bwaitfor\\b\\s+\\bdelay\\b" +
            "|\\bpg_sleep\\s*\\(" +
            "|\\bxp_cmdshell\\b" +
            "|\\binformation_schema\\b" +
            "|\\b(or|and)\\b\\s+['\"]?\\d+['\"]?\\s*=\\s*['\"]?\\d+['\"]?" +
            "|\\bdrop\\b\\s+\\btable\\b" +
            "|\\binsert\\b\\s+\\binto\\b.+\\bvalues\\b" +
            "|;\\s*(drop|delete|update|insert)\\b" +
            "|--\\s|\\s#\\s" +
            "|\\bexec\\s*\\(\\s*['\"]" +
            "|\\bcast\\s*\\(.{0,30}\\bchar\\b" +
            "|' or '1'='1|\" or \"1\"=\"1)"
    );

    private final AuditService auditService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        for (String headerName : HEADERS_TO_INSPECT) {
            String value = request.getHeader(headerName);
            if (value != null && SQLI_PATTERN.matcher(value).find()) {
                blockRequest(request, response, headerName, value);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private void blockRequest(HttpServletRequest request, HttpServletResponse response,
                               String headerName, String headerValue) throws IOException {
        String truncated = headerValue.length() > 200 ? headerValue.substring(0, 200) + "..." : headerValue;
        String ip = resolveClientIp(request);

        log.warn("Posible SQLi bloqueada en cabecera '{}' desde IP {} hacia {}: {}",
                headerName, ip, request.getRequestURI(), truncated);

        try {
            auditService.record(request, null, "SQLI_HEADER_BLOQUEADO", Map.of(
                    "header", headerName,
                    "valor", truncated,
                    "path", request.getRequestURI(),
                    "ip", ip));
        } catch (Exception ex) {
            // La auditoria no debe impedir que la solicitud sospechosa se bloquee.
            log.error("No se pudo registrar el intento de SQLi en la auditoria", ex);
        }

        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Solicitud rechazada\"}");
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
