-- ============================================================================
-- Usuarios de prueba para Torre de Control.
-- Ejecutar UNA VEZ en el SQL Editor de Supabase (no es una migracion Flyway,
-- es solo para pegar y correr manualmente).
--
-- Crea el usuario en auth.users + auth.identities (login email/password) y
-- su fila correspondiente en app_users (rol de negocio). El bloque de cada
-- usuario es idempotente: si el email ya existe, no hace nada.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- ADMIN
-- Login: admin@torre-control.local / Admin123!
-- ---------------------------------------------------------------------------
with new_user as (
    insert into auth.users (
        instance_id, id, aud, role, email, encrypted_password,
        email_confirmed_at, recovery_sent_at, last_sign_in_at,
        raw_app_meta_data, raw_user_meta_data, created_at, updated_at,
        confirmation_token, email_change, email_change_token_new, recovery_token
    )
    select
        '00000000-0000-0000-0000-000000000000', gen_random_uuid(), 'authenticated', 'authenticated',
        'admin@torre-control.local', crypt('Admin123!', gen_salt('bf')),
        now(), now(), now(),
        '{"provider":"email","providers":["email"]}', '{}', now(), now(),
        '', '', '', ''
    where not exists (select 1 from auth.users where email = 'admin@torre-control.local')
    returning id, email
),
new_identity as (
    insert into auth.identities (id, user_id, provider_id, identity_data, provider, last_sign_in_at, created_at, updated_at)
    select gen_random_uuid(), id, id::text, jsonb_build_object('sub', id::text, 'email', email), 'email', now(), now(), now()
    from new_user
    returning user_id
)
insert into app_users (id, full_name, email, phone_e164, role, active, created_at, updated_at)
select id, 'Administrador Demo', email, null, 'ADMIN', true, now(), now()
from new_user;

-- ---------------------------------------------------------------------------
-- SUPERVISOR
-- Login: supervisor@torre-control.local / Supervisor123!
-- ---------------------------------------------------------------------------
with new_user as (
    insert into auth.users (
        instance_id, id, aud, role, email, encrypted_password,
        email_confirmed_at, recovery_sent_at, last_sign_in_at,
        raw_app_meta_data, raw_user_meta_data, created_at, updated_at,
        confirmation_token, email_change, email_change_token_new, recovery_token
    )
    select
        '00000000-0000-0000-0000-000000000000', gen_random_uuid(), 'authenticated', 'authenticated',
        'supervisor@torre-control.local', crypt('Supervisor123!', gen_salt('bf')),
        now(), now(), now(),
        '{"provider":"email","providers":["email"]}', '{}', now(), now(),
        '', '', '', ''
    where not exists (select 1 from auth.users where email = 'supervisor@torre-control.local')
    returning id, email
),
new_identity as (
    insert into auth.identities (id, user_id, provider_id, identity_data, provider, last_sign_in_at, created_at, updated_at)
    select gen_random_uuid(), id, id::text, jsonb_build_object('sub', id::text, 'email', email), 'email', now(), now(), now()
    from new_user
    returning user_id
)
insert into app_users (id, full_name, email, phone_e164, role, active, created_at, updated_at)
select id, 'Supervisor Demo', email, null, 'SUPERVISOR', true, now(), now()
from new_user;

-- ---------------------------------------------------------------------------
-- OPERADOR
-- Login: operador@torre-control.local / Operador123!
-- Cambia phone_e164 por tu numero real (formato +51999999999) para probar WhatsApp.
-- ---------------------------------------------------------------------------
with new_user as (
    insert into auth.users (
        instance_id, id, aud, role, email, encrypted_password,
        email_confirmed_at, recovery_sent_at, last_sign_in_at,
        raw_app_meta_data, raw_user_meta_data, created_at, updated_at,
        confirmation_token, email_change, email_change_token_new, recovery_token
    )
    select
        '00000000-0000-0000-0000-000000000000', gen_random_uuid(), 'authenticated', 'authenticated',
        'operador@torre-control.local', crypt('Operador123!', gen_salt('bf')),
        now(), now(), now(),
        '{"provider":"email","providers":["email"]}', '{}', now(), now(),
        '', '', '', ''
    where not exists (select 1 from auth.users where email = 'operador@torre-control.local')
    returning id, email
),
new_identity as (
    insert into auth.identities (id, user_id, provider_id, identity_data, provider, last_sign_in_at, created_at, updated_at)
    select gen_random_uuid(), id, id::text, jsonb_build_object('sub', id::text, 'email', email), 'email', now(), now(), now()
    from new_user
    returning user_id
)
insert into app_users (id, full_name, email, phone_e164, role, active, created_at, updated_at)
select id, 'Operador Demo', email, null, 'OPERADOR', true, now(), now()
from new_user;

-- Verificacion
select id, email, role, active from app_users order by role;
