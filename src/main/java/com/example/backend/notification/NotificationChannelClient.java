package com.example.backend.notification;

import com.example.backend.domain.enums.NotificationChannel;

/** Puerto para cada canal omnicanal (WhatsApp, Email, Push). */
public interface NotificationChannelClient {

    NotificationChannel channel();

    /** Debe lanzar una excepcion si el envio falla, para que el worker reintente. */
    void send(NotificationJob job) throws Exception;
}
