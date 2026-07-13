package com.example.backend.service;

import com.example.backend.dto.FlightStateDto;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WebSocketBroadcastService {

    private final SimpMessagingTemplate messagingTemplate;

    /** Actualiza a quien mira el detalle de este vuelo puntual. */
    public void broadcastFlight(FlightStateDto dto) {
        messagingTemplate.convertAndSend("/topic/flights/" + dto.id(), dto);
    }

    /** Actualiza el dashboard Torre de Control (vista global). */
    public void broadcastDashboardUpdate(FlightStateDto dto) {
        messagingTemplate.convertAndSend("/topic/torre-control", dto);
    }

    public void broadcastAll(FlightStateDto dto) {
        broadcastFlight(dto);
        broadcastDashboardUpdate(dto);
    }
}
