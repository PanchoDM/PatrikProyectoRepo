-- ============================================================================
-- Torre de Control - Esquema inicial Supabase (PostgreSQL)
-- Ejecutar como migracion Flyway (Spring Boot la aplica automaticamente) o
-- pegar directamente en el SQL editor de Supabase.
-- ============================================================================

create extension if not exists "pgcrypto";

-- ----------------------------------------------------------------------------
-- Perfiles de usuario (extiende auth.users de Supabase con rol de negocio)
-- ----------------------------------------------------------------------------
create table if not exists app_users (
    id              uuid primary key references auth.users (id) on delete cascade,
    full_name       text not null,
    email           text not null,
    phone_e164      text,                         -- para WhatsApp (formato +51999999999)
    role            text not null default 'OPERADOR'
                    check (role in ('OPERADOR', 'SUPERVISOR', 'ADMIN')),
    active          boolean not null default true,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);

comment on table app_users is 'Perfil de negocio + RBAC para cada usuario autenticado en Supabase Auth';

-- ----------------------------------------------------------------------------
-- Vuelos y su maquina de estados / temporizadores
-- ----------------------------------------------------------------------------
create table if not exists flights (
    id                      uuid primary key default gen_random_uuid(),
    flight_number           text not null unique,
    prefix                  text not null check (prefix in ('LA', 'UC')),

    status                  text not null default 'ESPERANDO_ETA'
                            check (status in (
                                'ESPERANDO_ETA',
                                'ESPERANDO_ATA',
                                'EN_DESCARGA',
                                'ESPERANDO_NFD',
                                'EN_TARJA',
                                'COMPLETADO',
                                'VENCIDO'
                            )),

    eta                     timestamptz,
    ata                     timestamptz,
    ata_deadline            timestamptz,          -- eta + 40 min (Timer 1)
    ata_escalated_at        timestamptz,          -- email a supervisor si vence sin registrar ATA
    descarga_confirmed_at   timestamptz,
    descarga_deadline       timestamptz,          -- ata + 40/55 min segun prefijo (Timer 3)
    descarga_escalated_at   timestamptz,          -- email a supervisor si vence sin confirmar descarga
    rcf_deadline            timestamptz,          -- ata + 2h/3h segun prefijo (Timer 2)
    rcf_alert_sent_at       timestamptz,          -- WhatsApp 5 min antes de rcf_deadline

    nfd_anticipado          boolean,
    nfd_correos             boolean,
    nfd_answered_at         timestamptz,

    tarja_deadline          timestamptz,          -- nfd_answered_at + 15 min (Timer 5)
    tarja_completed_at      timestamptz,
    tarja_alert_sent_at     timestamptz,          -- WhatsApp al vencer la Tarja
    tarja_escalated_at      timestamptz,          -- email a supervisor si vence sin completar Tarja

    created_by              uuid references app_users (id),
    created_at              timestamptz not null default now(),
    updated_at              timestamptz not null default now(),
    version                 bigint not null default 0     -- optimistic locking (JPA @Version)
);

create index if not exists idx_flights_status on flights (status);
create index if not exists idx_flights_prefix on flights (prefix);

comment on table flights is 'Estado y temporizadores de arribo/descarga/internamiento de cada vuelo';

-- ----------------------------------------------------------------------------
-- Log de notificaciones omnicanal (WhatsApp / Email / Push)
-- ----------------------------------------------------------------------------
create table if not exists notification_logs (
    id              uuid primary key default gen_random_uuid(),
    flight_id       uuid references flights (id) on delete cascade,
    channel         text not null check (channel in ('WHATSAPP', 'EMAIL', 'PUSH')),
    recipient       text not null,
    template        text not null,
    status          text not null default 'QUEUED'
                    check (status in ('QUEUED', 'SENT', 'DELIVERED', 'FAILED')),
    payload         jsonb,
    error_message   text,
    created_at      timestamptz not null default now(),
    delivered_at    timestamptz
);

create index if not exists idx_notification_logs_flight on notification_logs (flight_id);

-- ----------------------------------------------------------------------------
-- Auditoria inmutable: toda accion de un operador queda registrada
-- ----------------------------------------------------------------------------
create table if not exists audit_logs (
    id              bigint generated always as identity primary key,
    flight_id       uuid references flights (id) on delete set null,
    user_id         uuid references app_users (id),
    action          text not null,
    ip_address      text,
    user_agent      text,
    metadata        jsonb,
    created_at      timestamptz not null default now()
);

create index if not exists idx_audit_logs_flight on audit_logs (flight_id);
create index if not exists idx_audit_logs_user on audit_logs (user_id);

-- ============================================================================
-- Row Level Security
--
-- El backend de Spring Boot se conecta con un rol de base de datos dedicado
-- (ver `app.db-role` en application.yml) que tiene BYPASSRLS y es el unico
-- que escribe en estas tablas (logica de negocio centralizada, ver
-- FlightService). El cliente Angular solo usa Supabase para:
--   1) Autenticacion (obtener el JWT que se envia al backend).
--   2) Suscripciones Realtime de solo lectura (opcional, ademas de WebSocket).
-- Por eso las policies de escritura para 'authenticated' son deliberadamente
-- restrictivas: solo lectura + las excepciones explicitas de abajo.
-- ============================================================================

alter table app_users enable row level security;
alter table flights enable row level security;
alter table notification_logs enable row level security;
alter table audit_logs enable row level security;

-- app_users: cada quien lee su propio perfil; supervisores/admin leen todos
create policy app_users_select_self on app_users
    for select using (
        id = auth.uid()
        or exists (
            select 1 from app_users u
            where u.id = auth.uid() and u.role in ('SUPERVISOR', 'ADMIN')
        )
    );

create policy app_users_update_self on app_users
    for update using (id = auth.uid())
    with check (id = auth.uid());

-- flights: cualquier usuario autenticado y activo puede leer (Torre de Control)
create policy flights_select_authenticated on flights
    for select using (
        exists (
            select 1 from app_users u
            where u.id = auth.uid() and u.active = true
        )
    );

-- Los INSERT/UPDATE reales los hace el backend con su rol privilegiado
-- (bypassa RLS). Se deja bloqueado para 'authenticated' por defecto:
-- no se crean policies de insert/update/delete para ese rol => denegado.

-- notification_logs: solo SUPERVISOR/ADMIN pueden leer (trazabilidad de envios)
create policy notification_logs_select_supervisor on notification_logs
    for select using (
        exists (
            select 1 from app_users u
            where u.id = auth.uid() and u.role in ('SUPERVISOR', 'ADMIN')
        )
    );

-- audit_logs: solo SUPERVISOR/ADMIN pueden leer; nadie via API puede
-- update/delete (inmutabilidad); el backend inserta con su rol privilegiado.
create policy audit_logs_select_supervisor on audit_logs
    for select using (
        exists (
            select 1 from app_users u
            where u.id = auth.uid() and u.role in ('SUPERVISOR', 'ADMIN')
        )
    );

-- ----------------------------------------------------------------------------
-- Realtime: publicar cambios de 'flights' para que el frontend pueda
-- suscribirse via Supabase Realtime ademas del WebSocket propio del backend.
-- ----------------------------------------------------------------------------
alter publication supabase_realtime add table flights;

-- ----------------------------------------------------------------------------
-- Trigger utilitario updated_at
-- ----------------------------------------------------------------------------
create or replace function set_updated_at() returns trigger as $$
begin
    new.updated_at = now();
    return new;
end;
$$ language plpgsql;

create trigger trg_flights_updated_at
    before update on flights
    for each row execute function set_updated_at();

create trigger trg_app_users_updated_at
    before update on app_users
    for each row execute function set_updated_at();
