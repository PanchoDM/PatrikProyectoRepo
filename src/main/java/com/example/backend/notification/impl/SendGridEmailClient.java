package com.example.backend.notification.impl;

import com.example.backend.config.AppProperties;
import com.example.backend.domain.enums.NotificationChannel;
import com.example.backend.notification.NotificationChannelClient;
import com.example.backend.notification.NotificationJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Envio de correo via SendGrid API (https://docs.sendgrid.com/api-reference/mail-send).
 * Requiere SENDGRID_API_KEY. En dry-run (sin key) solo registra el intento.
 */
@Component
@Slf4j
public class SendGridEmailClient implements NotificationChannelClient {

    private final AppProperties.Notifications.Email config;
    private final RestClient restClient;

    public SendGridEmailClient(AppProperties appProperties) {
        this.config = appProperties.notifications().email();
        this.restClient = RestClient.builder()
                .baseUrl("https://api.sendgrid.com/v3")
                .build();
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public void send(NotificationJob job) {
        String subject = String.valueOf(job.payload().getOrDefault("subject", job.template()));
        String content = String.valueOf(job.payload().getOrDefault("message", ""));

        if (config.apiKey() == null || config.apiKey().isBlank()) {
            log.warn("[DRY-RUN] SendGrid no configurado. Email a {} omitido: {} - {}", job.recipient(), subject, content);
            return;
        }

        Map<String, Object> requestBody = Map.of(
                "personalizations", List.of(Map.of("to", List.of(Map.of("email", job.recipient())))),
                "from", Map.of("email", config.fromAddress()),
                "subject", subject,
                "content", List.of(Map.of("type", "text/plain", "value", content))
        );

        restClient.post()
                .uri("/mail/send")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                .body(requestBody)
                .retrieve()
                .toBodilessEntity();
    }
}
