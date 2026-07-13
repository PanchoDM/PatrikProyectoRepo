package com.example.backend.security;

import com.example.backend.domain.entity.AppUser;
import com.example.backend.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Resuelve el principal de negocio (CurrentUser + rol RBAC) a partir del
 * claim `sub` de un JWT de Supabase cuya firma ya fue verificada por
 * Spring Security contra el JWKS del proyecto (ver SecurityConfig).
 */
@Component
@RequiredArgsConstructor
public class SupabaseJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final AppUserRepository appUserRepository;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        AppUser appUser = appUserRepository.findById(userId).orElse(null);
        if (appUser == null || !appUser.isActive()) {
            throw new InvalidBearerTokenException("Usuario no reconocido o inactivo en app_users");
        }

        CurrentUser currentUser = new CurrentUser(appUser.getId(), appUser.getEmail(), appUser.getRole());
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + appUser.getRole().name()));
        // El constructor de 3 argumentos ya marca el token como autenticado;
        // llamar a setAuthenticated(true) explicitamente lanza
        // IllegalArgumentException ("Cannot set this token to trusted").
        return new UsernamePasswordAuthenticationToken(currentUser, jwt, authorities);
    }
}
