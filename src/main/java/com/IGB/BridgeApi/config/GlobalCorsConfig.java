package com.IGB.BridgeApi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

@Configuration
public class GlobalCorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {

        CorsConfiguration config =
                new CorsConfiguration();


        /* =========================================================
           ORÍGENES
           Permitir peticiones desde cualquier origen
           ========================================================= */

        config.setAllowedOrigins(
                Arrays.asList("*")
        );


        /* =========================================================
           MÉTODOS
           ========================================================= */

        config.setAllowedMethods(
                Arrays.asList(
                        "GET",
                        "POST",
                        "PUT",
                        "DELETE",
                        "PATCH",
                        "OPTIONS"
                )
        );


        /* =========================================================
           HEADERS PERMITIDOS
           ========================================================= */

        config.setAllowedHeaders(
                Arrays.asList("*")
        );


        /* =========================================================
           HEADERS EXPUESTOS
           ========================================================= */

        config.setExposedHeaders(
                Arrays.asList(
                        "Authorization",
                        "Content-Type"
                )
        );


        /* =========================================================
           CREDENCIALES

           IMPORTANTE:
           Si usamos "*" como origen, no debemos utilizar
           allowCredentials(true).

           El JWT puede seguir enviándose normalmente mediante:
           Authorization: Bearer <token>
           ========================================================= */

        config.setAllowCredentials(false);


        /* =========================================================
           CACHE DEL PREFLIGHT
           ========================================================= */

        config.setMaxAge(
                3600L
        );


        /* =========================================================
           APLICAR A TODA LA API
           ========================================================= */

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();


        source.registerCorsConfiguration(
                "/**",
                config
        );


        return source;
    }
}