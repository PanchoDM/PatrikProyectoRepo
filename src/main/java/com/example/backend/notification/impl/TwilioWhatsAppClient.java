package com.example.backend.notification.impl;

import com.example.backend.config.AppProperties;
import com.example.backend.domain.enums.NotificationChannel;
import com.example.backend.notification.NotificationChannelClient;
import com.example.backend.notification.NotificationJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Envio de WhatsApp via Twilio API (https://www.twilio.com/docs/whatsapp/api).
 * Requiere TWILIO_ACCOUNT_SID / TWILIO_AUTH_TOKEN / TWILIO_WHATSAPP_FROM.
 * Si no hay credenciales configuradas, solo registra el intento (modo dry-run)
 * para no romper el flujo en ambientes de desarrollo sin cuenta Twilio.
 */
@Component
@Slf4j
public class TwilioWhatsAppClient implements NotificationChannelClient {

    private final AppProperties.Notifications.WhatsApp config;
    private final RestClient restClient;

    public TwilioWhatsAppClient(AppProperties appProperties) {
        this.config = appProperties.notifications().whatsapp();
        this.restClient = RestClient.builder()
                .baseUrl("https://api.twilio.com/2010-04-01")
                .build();
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.WHATSAPP;
    }

    @Override
    public void send(NotificationJob job) {
        String body = String.valueOf(job.payload().getOrDefault("message", job.template()));

        if (config.accountSid() == null || config.accountSid().isBlank()) {
            log.warn("[DRY-RUN] Twilio no configurado. WhatsApp a {} omitido: {}", job.recipient(), body);
            return;
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", "whatsapp:" + job.recipient());
        form.add("From", config.fromNumber());
        form.add("Body", body);

        String basicAuth = Base64.getEncoder().encodeToString(
                (config.accountSid() + ":" + config.authToken()).getBytes(StandardCharsets.UTF_8));

        restClient.post()
                .uri("/Accounts/{sid}/Messages.json", config.accountSid())
                .header(HttpHeaders.AUTHORIZATION, "Basic " + basicAuth)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .toBodilessEntity();
    }
}
