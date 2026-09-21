package com.IGB.BridgeApi.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import org.springframework.security.core.context.SecurityContextHolder;

import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;

import org.springframework.stereotype.Component;

import org.springframework.util.AntPathMatcher;

import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Component
public class JwtRequestFilter extends OncePerRequestFilter {

    private static final Logger log =
            LoggerFactory.getLogger(JwtRequestFilter.class);

    private static final AntPathMatcher PATH_MATCHER =
            new AntPathMatcher();


    /* =========================================================
       RUTAS PÚBLICAS
       ========================================================= */

    private static final List<String> PUBLIC_PATTERNS =
            Arrays.asList(

                    /* LOGIN / GENERACIÓN TOKEN */
                    "/authenticate/**",

                    /* WALI */
                    "/wali/**",

                    /* REDPLAS */
                    "/redplas/**",

                    /* TWILIO */
                    "/twilio/**",

                    /* FEMPROBIEN */
                    "/femprobien/**",

                    /* ACTUATOR */
                    "/actuator/health",
                    "/actuator/info"
            );


    /* =========================================================
       JWT UTIL
       ========================================================= */

    @Autowired
    private JwtUtil jwtUtil;


    /* =========================================================
       NORMALIZAR PATH
       ========================================================= */

    private String getNormalizedPath(
            HttpServletRequest request
    ) {

        String uri =
                request.getRequestURI();

        String contextPath =
                request.getContextPath();

        /*
         * Ejemplo:
         *
         * URI:
         * /BridgeApi/femprobien/test
         *
         * Context:
         * /BridgeApi
         *
         * Resultado:
         * /femprobien/test
         */

        if (
                contextPath != null
                        &&
                        !contextPath.isEmpty()
                        &&
                        uri.startsWith(contextPath)
        ) {

            uri =
                    uri.substring(
                            contextPath.length()
                    );
        }

        if (!uri.startsWith("/")) {

            uri =
                    "/" + uri;
        }

        return uri;
    }


    /* =========================================================
       VALIDAR RUTA PÚBLICA
       ========================================================= */

    private boolean isPublic(
            HttpServletRequest request
    ) {

        /*
         * Siempre permitir OPTIONS.
         *
         * Es necesario para CORS.
         */

        if (
                "OPTIONS".equalsIgnoreCase(
                        request.getMethod()
                )
        ) {

            return true;
        }


        String path =
                getNormalizedPath(request);


        for (
                String pattern :
                PUBLIC_PATTERNS
        ) {

            if (
                    PATH_MATCHER.match(
                            pattern,
                            path
                    )
            ) {

                if (log.isDebugEnabled()) {

                    log.debug(
                            "Ruta pública: {}",
                            path
                    );
                }

                return true;
            }
        }


        return false;
    }


    /* =========================================================
       OMITIR JWT EN RUTAS PÚBLICAS
       ========================================================= */

    @Override
    protected boolean shouldNotFilter(
            HttpServletRequest request
    ) {

        return isPublic(request);
    }


    /* =========================================================
       FILTRO JWT
       ========================================================= */

    @Override
    protected void doFilterInternal(

            HttpServletRequest request,

            HttpServletResponse response,

            FilterChain filterChain

    ) throws ServletException, IOException {


        final String authorizationHeader =
                request.getHeader(
                        "Authorization"
                );


        /* =====================================================
           NO EXISTE TOKEN
           ===================================================== */

        if (
                authorizationHeader == null
                        ||
                        !authorizationHeader.startsWith(
                                "Bearer "
                        )
        ) {

            filterChain.doFilter(
                    request,
                    response
            );

            return;
        }


        try {

            /* =================================================
               EXTRAER TOKEN
               ================================================= */

            String jwt =
                    authorizationHeader.substring(7);


            /* =================================================
               VALIDAR TOKEN
               ================================================= */

            if (!jwtUtil.validateToken(jwt)) {

                log.warn(
                        "JWT inválido o expirado"
                );

                filterChain.doFilter(
                        request,
                        response
                );

                return;
            }


            /* =================================================
               EXTRAER USUARIO
               ================================================= */

            String username =
                    jwtUtil.extractUsername(jwt);


            /* =================================================
               CREAR AUTENTICACIÓN
               ================================================= */

            if (
                    username != null
                            &&
                            SecurityContextHolder
                                    .getContext()
                                    .getAuthentication()
                                    == null
            ) {

                UsernamePasswordAuthenticationToken
                        authenticationToken =

                        new UsernamePasswordAuthenticationToken(

                                username,

                                null,

                                Collections.emptyList()
                        );


                /* =============================================
                   DETALLES DE LA PETICIÓN
                   ============================================= */

                authenticationToken.setDetails(

                        new WebAuthenticationDetailsSource()
                                .buildDetails(request)
                );


                /* =============================================
                   GUARDAR AUTENTICACIÓN
                   ============================================= */

                SecurityContextHolder
                        .getContext()
                        .setAuthentication(
                                authenticationToken
                        );


                if (log.isDebugEnabled()) {

                    log.debug(
                            "Usuario autenticado por JWT: {}",
                            username
                    );
                }
            }

        } catch (Exception e) {

            log.error(
                    "Error validando JWT: {}",
                    e.getMessage(),
                    e
            );
        }


        /* =====================================================
           CONTINUAR
           ===================================================== */

        filterChain.doFilter(
                request,
                response
        );
    }
}