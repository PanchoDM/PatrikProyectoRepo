package com.example.backend.service;

import com.example.backend.domain.entity.AuditLog;
import com.example.backend.repository.AuditLogRepository;
import com.example.backend.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Trazabilidad absoluta: cada boton de accion presionado por un operador
 * queda registrado de forma inmutable (usuario, timestamp exacto, IP,
 * user-agent y metadata de la accion).
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public void record(HttpServletRequest request, UUID flightId, String action, Map<String, Object> metadata) {
        auditLogRepository.save(build(request, flightId, action, metadata));
    }

    /**
     * Igual que {@link #record}, pero sin persistir: para llamadores que
     * acumulan muchas entradas (ej. sincronizacion masiva) y las guardan
     * juntas con {@link #recordAll} en una sola escritura por lote.
     */
    public AuditLog build(HttpServletRequest request, UUID flightId, String action, Map<String, Object> metadata) {
        CurrentUser user = currentUser();

        AuditLog auditLog = new AuditLog();
        auditLog.setFlightId(flightId);
        auditLog.setUserId(user == null ? null : user.id());
        auditLog.setAction(action);
        auditLog.setIpAddress(resolveClientIp(request));
        auditLog.setUserAgent(request.getHeader("User-Agent"));
        auditLog.setMetadata(metadata);
        auditLog.setCreatedAt(Instant.now());
        return auditLog;
    }

    public void recordAll(List<AuditLog> entries) {
        if (!entries.isEmpty()) {
            auditLogRepository.saveAll(entries);
        }
    }

    private CurrentUser currentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser cu)) {
            return null;
        }
        return cu;
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
