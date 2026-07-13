package com.example.backend.notification.impl;

import com.example.backend.domain.enums.NotificationChannel;
import com.example.backend.notification.NotificationChannelClient;
import com.example.backend.notification.NotificationJob;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Push web en tiempo real: se emite por el mismo canal STOMP que ya
 * mantiene sincronizados los cronometros. El frontend (con permiso del
 * usuario) dispara la Web Notification API al recibir el mensaje en
 * /topic/push/{recipient}. `recipient` es el id de usuario (operador o
 * supervisor) destinatario de la alerta.
 *
 * Extension futura: para push nativo offline (con el navegador cerrado) se
 * puede sustituir/complementar esta implementacion con Web Push + VAPID
 * (claves ya parametrizadas en application.yml: app.notifications.push.*)
 * usando una libreria como nl.martijndwars:webpush contra las suscripciones
 * guardadas por el Service Worker de Angular.
 */
@Component
@RequiredArgsConstructor
public class WebPushClient implements NotificationChannelClient {

    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.PUSH;
    }

    @Override
    public void send(NotificationJob job) {
        messagingTemplate.convertAndSend("/topic/push/" + job.recipient(), (Object) job.payload());
    }
}
