package com.example.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis como capa de cache de lectura entre Spring Boot y Supabase: reduce
 * la latencia y la carga sobre Postgres en picos de trafico para las
 * lecturas frecuentes (listado de vuelos activos, detalle por id/numero).
 * TTL corto (5s) porque el frontend ya recibe el estado real en vivo por
 * WebSocket; el cache solo amortigua rafagas de GET (carga inicial de
 * pantalla, reconexiones), no reemplaza la fuente de verdad.
 *
 * Igual que la cola de notificaciones, Redis aqui es "best-effort": si no
 * esta disponible, las lecturas deben seguir funcionando yendo directo a
 * Supabase en vez de tumbar la peticion (ver ResilientCacheErrorHandler).
 */
@Configuration
@EnableCaching
@Slf4j
public class CacheConfig implements CachingConfigurer {

    public static final String FLIGHTS_CACHE = "flights";

    @Override
    public CacheErrorHandler errorHandler() {
        return new ResilientCacheErrorHandler();
    }

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer() {
        RedisCacheConfiguration ttlConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(5))
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()));
        return builder -> builder.withCacheConfiguration(FLIGHTS_CACHE, ttlConfig);
    }

    @Slf4j
    static class ResilientCacheErrorHandler implements CacheErrorHandler {

        @Override
        public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
            log.warn("Cache Redis no disponible (GET {}/{}), se consulta la base de datos directo: {}",
                    cache.getName(), key, exception.getMessage());
        }

        @Override
        public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
            log.warn("Cache Redis no disponible (PUT {}/{}): {}", cache.getName(), key, exception.getMessage());
        }

        @Override
        public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
            log.warn("Cache Redis no disponible (EVICT {}/{}): {}", cache.getName(), key, exception.getMessage());
        }

        @Override
        public void handleCacheClearError(RuntimeException exception, Cache cache) {
            log.warn("Cache Redis no disponible (CLEAR {}): {}", cache.getName(), exception.getMessage());
        }
    }
}
