package com.example.backend.service;

import com.example.backend.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Evita que Render suspenda el servicio por inactividad (el plan free apaga
 * el dyno tras ~15 min sin trafico HTTP entrante). Se autoconsulta su propio
 * /actuator/health antes de que se cumpla ese plazo, generando trafico real
 * que reinicia el contador de inactividad de Render.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KeepAliveService {

    private final AppProperties appProperties;
    private final RestClient restClient = RestClient.create();

    @Scheduled(
            initialDelayString = "${app.keep-alive.interval-ms:600000}",
            fixedRateString = "${app.keep-alive.interval-ms:600000}"
    )
    public void ping() {
        AppProperties.KeepAlive keepAlive = appProperties.keepAlive();
        if (keepAlive == null || !keepAlive.enabled()
                || keepAlive.url() == null || keepAlive.url().isBlank()) {
            return;
        }
        try {
            restClient.get()
                    .uri(keepAlive.url() + "/actuator/health")
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Keep-alive ping enviado a {}", keepAlive.url());
        } catch (Exception ex) {
            log.warn("Keep-alive ping fallo hacia {}: {}", keepAlive.url(), ex.getMessage());
        }
    }
}
