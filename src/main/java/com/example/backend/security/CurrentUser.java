package com.example.backend.security;

import com.example.backend.domain.enums.UserRole;

import java.util.UUID;

/** Principal autenticado, resuelto desde el JWT de Supabase + tabla app_users. */
public record CurrentUser(UUID id, String email, UserRole role) {
}
