-- "Tipo de Vuelo" (con carga / sin carga) desde Google Sheets. Determina si
-- el vuelo sigue el flujo completo (descarga/RCF/NFD/Tarja) o el flujo
-- simplificado (termina al registrar el ATA). Default true = flujo completo,
-- para no alterar el comportamiento de los vuelos ya existentes o creados
-- manualmente desde la UI (que no conocen este concepto).
alter table flights add column if not exists con_carga boolean not null default true;
