package com.example.backend.config;

import com.example.backend.security.HeaderSanitizationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registra el filtro anti-SQLi de cabeceras con la maxima precedencia
 * posible: debe ejecutarse antes que la cadena de filtros de Spring
 * Security (autenticacion, CORS, etc.), ya que el ataque se intenta a nivel
 * de transporte HTTP, independientemente de si la solicitud esta autenticada.
 */
@Configuration
@RequiredArgsConstructor
public class HeaderSanitizationFilterConfig {

    private final HeaderSanitizationFilter headerSanitizationFilter;

    @Bean
    public FilterRegistrationBean<HeaderSanitizationFilter> headerSanitizationFilterRegistration() {
        FilterRegistrationBean<HeaderSanitizationFilter> registration = new FilterRegistrationBean<>(headerSanitizationFilter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
