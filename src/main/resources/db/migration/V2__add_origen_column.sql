-- Origen (aeropuerto de procedencia), usado por la sincronizacion desde
-- Google Sheets. Opcional: no todos los vuelos ingresados manualmente lo traen.
alter table flights add column if not exists origen text;
