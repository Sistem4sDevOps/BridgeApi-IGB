package com.IGB.BridgeApi.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.Serializable;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Component
public class JwtUtil implements Serializable {

    private static final long serialVersionUID =
            -2550185165626007488L;


    /* =========================================================
       SECRET JWT
       ========================================================= */

    @Value("${jwt.secret}")
    private String secret;


    /* =========================================================
       DURACIÓN TOKEN
       ========================================================= */

    @Value("${jwt.expiration:28800}")
    private Long expiration;


    /* =========================================================
       EXTRAER USERNAME
       ========================================================= */

    public String extractUsername(
            String token
    ) {

        return extractClaim(
                token,
                Claims::getSubject
        );
    }


    /* =========================================================
       EXTRAER FECHA EXPIRACIÓN
       ========================================================= */

    public Date extractExpiration(
            String token
    ) {

        return extractClaim(
                token,
                Claims::getExpiration
        );
    }


    /* =========================================================
       EXTRAER CLAIM
       ========================================================= */

    public <T> T extractClaim(

            String token,

            Function<Claims, T> claimsResolver

    ) {

        final Claims claims =
                extractAllClaims(token);

        return claimsResolver.apply(
                claims
        );
    }


    /* =========================================================
       EXTRAER TODOS LOS CLAIMS
       ========================================================= */

    private Claims extractAllClaims(
            String token
    ) {

        return Jwts
                .parser()
                .setSigningKey(secret)
                .parseClaimsJws(token)
                .getBody();
    }


    /* =========================================================
       VALIDAR EXPIRACIÓN
       ========================================================= */

    private boolean isTokenExpired(
            String token
    ) {

        Date expirationDate =
                extractExpiration(token);

        return expirationDate.before(
                new Date()
        );
    }


    /* =========================================================
       GENERAR TOKEN
       ========================================================= */

    public String generateToken(
            String username
    ) {

        Map<String, Object> claims =
                new HashMap<>();

        return createToken(
                claims,
                username
        );
    }


    /* =========================================================
       CREAR TOKEN
       ========================================================= */

    private String createToken(

            Map<String, Object> claims,

            String subject

    ) {

        Date now =
                new Date();

        /*
         * jwt.expiration está en segundos.
         */

        Date expirationDate =
                new Date(
                        now.getTime()
                                +
                                expiration * 1000
                );


        return Jwts
                .builder()
                .setClaims(claims)
                .setSubject(subject)
                .setIssuedAt(now)
                .setExpiration(expirationDate)
                .signWith(
                        SignatureAlgorithm.HS512,
                        secret
                )
                .compact();
    }


    /* =========================================================
       VALIDAR TOKEN
       ========================================================= */

    public boolean validateToken(
            String token
    ) {

        try {

            String username =
                    extractUsername(token);

            return username != null
                    &&
                    !username.trim().isEmpty()
                    &&
                    !isTokenExpired(token);

        } catch (Exception e) {

            return false;
        }
    }
}