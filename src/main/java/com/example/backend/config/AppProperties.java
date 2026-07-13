package com.example.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Cors cors,
        Supabase supabase,
        Timers timers,
        Notifications notifications
) {

    public record Cors(List<String> allowedOrigins) {
    }

    public record Supabase(String url) {
    }

    public record Timers(
            int ataWindowMinutes,
            int descargaMinutesLa,
            int descargaMinutesUc,
            int rcfHoursLa,
            int rcfHoursUc,
            int rcfPreAlertMinutes,
            int tarjaMinutes,
            long schedulerTickMs
    ) {
    }

    public record Notifications(
            boolean enabled,
            String redisStreamKey,
            String consumerGroup,
            int maxRetry,
            WhatsApp whatsapp,
            Email email,
            Push push
    ) {
        public record WhatsApp(String provider, String accountSid, String authToken, String fromNumber) {
        }

        public record Email(String provider, String apiKey, String fromAddress, String supervisorEscalationList) {
            public List<String> escalationList() {
                if (supervisorEscalationList == null || supervisorEscalationList.isBlank()) {
                    return List.of();
                }
                return List.of(supervisorEscalationList.split("\\s*,\\s*"));
            }
        }

        public record Push(String vapidPublicKey, String vapidPrivateKey, String vapidSubject) {
        }
    }
}
