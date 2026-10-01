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

    private static final Logger log = LoggerFactory.getLogger(JwtRequestFilter.class);
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private static final List<String> PUBLIC_PATTERNS = Arrays.asList(
            "/authenticate/**",
            "/wali/**",
            "/redplas/**",
            "/twilio/**",
            "/femprobien/**",
            "/actuator/health",
            "/actuator/info"
    );

    @Autowired
    private JwtUtil jwtUtil;

    private String getNormalizedPath(HttpServletRequest request) {

        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();

        if (contextPath != null
                && !contextPath.isEmpty()
                && uri.startsWith(contextPath)) {

            uri = uri.substring(contextPath.length());
        }

        if (!uri.startsWith("/")) {
            uri = "/" + uri;
        }

        return uri;
    }

    private boolean isPublic(HttpServletRequest request) {

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String path = getNormalizedPath(request);

        for (String pattern : PUBLIC_PATTERNS) {

            if (PATH_MATCHER.match(pattern, path)) {

                if (log.isDebugEnabled()) {
                    log.debug(
                            "Ruta pública JWT: {} -> {}",
                            request.getRequestURI(),
                            path
                    );
                }

                return true;
            }
        }

        return false;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return isPublic(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");

        if (authHeader == null
                || !authHeader.startsWith("Bearer ")) {

            chain.doFilter(request, response);
            return;
        }

        try {

            String jwt = authHeader.substring(7);
            String username = jwtUtil.extractUsername(jwt);

            if (username != null
                    && !username.trim().isEmpty()
                    && SecurityContextHolder.getContext().getAuthentication() == null) {

                if (jwtUtil.validateToken(jwt)) {

                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(
                                    username.trim().toLowerCase(),
                                    null,
                                    Collections.emptyList()
                            );

                    authToken.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request)
                    );

                    SecurityContextHolder
                            .getContext()
                            .setAuthentication(authToken);
                }
            }

        } catch (Exception e) {

            log.error(
                    "Error validando JWT: {}",
                    e.getMessage(),
                    e
            );
        }

        chain.doFilter(request, response);
    }
}
