package com.example.backend.notification;

import com.example.backend.domain.enums.NotificationChannel;

import java.util.Map;
import java.util.UUID;

/** Job serializable encolado en Redis para envio omnicanal asincrono y persistente. */
public record NotificationJob(
        UUID jobId,
        UUID flightId,
        NotificationChannel channel,
        String recipient,
        String template,
        Map<String, Object> payload,
        int retryCount
) {
    public NotificationJob withRetryIncremented() {
        return new NotificationJob(jobId, flightId, channel, recipient, template, payload, retryCount + 1);
    }
}
