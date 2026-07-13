package com.example.backend.service;

import com.example.backend.config.AppProperties;
import com.example.backend.domain.entity.Flight;
import com.example.backend.domain.enums.FlightPrefix;
import com.example.backend.domain.enums.FlightStatus;
import com.example.backend.domain.enums.TimerType;
import com.example.backend.dto.FlightStateDto;
import com.example.backend.dto.TimerSnapshot;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Traduce una entidad Flight + la hora actual a la foto de temporizadores que consume el frontend. */
@Component
public class FlightStateMapper {

    private static final Set<FlightStatus> TERMINAL = Set.of(FlightStatus.COMPLETADO, FlightStatus.VENCIDO);

    private final AppProperties.Timers timers;

    public FlightStateMapper(AppProperties appProperties) {
        this.timers = appProperties.timers();
    }

    public FlightStateDto toDto(Flight flight, Instant now) {
        return new FlightStateDto(
                flight.getId(),
                flight.getFlightNumber(),
                flight.getOrigen(),
                flight.isConCarga(),
                flight.getPrefix(),
                flight.getStatus(),
                flight.getEta(),
                flight.getAta(),
                flight.getDescargaConfirmedAt(),
                flight.getNfdAnticipado(),
                flight.getNfdCorreos(),
                flight.getTarjaCompletedAt(),
                buildTimers(flight, now)
        );
    }

    private List<TimerSnapshot> buildTimers(Flight flight, Instant now) {
        List<TimerSnapshot> snapshots = new ArrayList<>();
        if (TERMINAL.contains(flight.getStatus())) {
            return snapshots;
        }

        boolean isLa = flight.getPrefix() == FlightPrefix.LA;

        if (flight.getStatus() == FlightStatus.ESPERANDO_ATA && flight.getAtaDeadline() != null) {
            snapshots.add(TimerSnapshot.of(
                    TimerType.T1_REGISTRO_ATA, "Registro de ATA",
                    flight.getAtaDeadline(), timers.ataWindowMinutes() * 60L, now, true));
        }

        if (flight.getStatus() == FlightStatus.EN_DESCARGA && flight.getDescargaDeadline() != null) {
            long total = (isLa ? timers.descargaMinutesLa() : timers.descargaMinutesUc()) * 60L;
            snapshots.add(TimerSnapshot.of(
                    TimerType.T3_TERMINO_DESCARGA, "Termino de descarga",
                    flight.getDescargaDeadline(), total, now, true));
        }

        boolean rcfVisible = flight.getRcfDeadline() != null
                && Set.of(FlightStatus.EN_DESCARGA, FlightStatus.ESPERANDO_NFD, FlightStatus.EN_TARJA)
                        .contains(flight.getStatus());
        if (rcfVisible) {
            long total = (isLa ? timers.rcfHoursLa() : timers.rcfHoursUc()) * 3600L;
            snapshots.add(TimerSnapshot.of(
                    TimerType.T2_ALERTA_RCF, "Pendiente RCF",
                    flight.getRcfDeadline(), total, now, true));
        }

        if (flight.getStatus() == FlightStatus.EN_TARJA && flight.getTarjaDeadline() != null) {
            snapshots.add(TimerSnapshot.of(
                    TimerType.T5_TARJA, "Realizar la Tarja",
                    flight.getTarjaDeadline(), timers.tarjaMinutes() * 60L, now, true));
        }

        return snapshots;
    }
}
