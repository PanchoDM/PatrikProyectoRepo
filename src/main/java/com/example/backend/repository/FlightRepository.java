package com.example.backend.repository;

import com.example.backend.domain.entity.Flight;
import com.example.backend.domain.enums.FlightStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FlightRepository extends JpaRepository<Flight, UUID> {

    Optional<Flight> findByFlightNumber(String flightNumber);

    boolean existsByFlightNumber(String flightNumber);

    boolean existsByFlightNumberAndStatusNot(String flightNumber, FlightStatus status);

    List<Flight> findByFlightNumberIn(Collection<String> flightNumbers);

    List<Flight> findByStatusNotIn(List<FlightStatus> terminalStatuses);

    long countByCreatedAtGreaterThanEqual(Instant since);

    List<Flight> findByEtaBetweenOrderByEtaAsc(Instant from, Instant to);
}
