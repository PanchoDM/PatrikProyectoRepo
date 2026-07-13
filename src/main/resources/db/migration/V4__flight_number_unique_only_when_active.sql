-- ============================================================================
-- El mismo flight_number se reutiliza dia a dia en la operacion real (ej.
-- LA2454 vuela todos los dias). La unicidad global sobre flight_number
-- bloqueaba registrar un vuelo nuevo mientras existiera CUALQUIER fila
-- anterior con ese numero, incluso si ya estaba COMPLETADO de un dia previo.
-- Se reemplaza por un indice unico parcial: solo exige unicidad entre
-- vuelos que siguen en curso (no completados).
-- ============================================================================

alter table flights drop constraint if exists flights_flight_number_key;

create unique index if not exists idx_flights_flight_number_active
    on flights (flight_number)
    where status <> 'COMPLETADO';
