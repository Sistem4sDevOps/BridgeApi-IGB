package com.IGB.BridgeApi.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.util.Map;

@Service
public class MicrosoftGraphTokenService {

    private static final String GRAPH_SCOPE =
            "https://graph.microsoft.com/.default";

    private final RestTemplate restTemplate;

    @Value("${femprobien.microsoft.tenant-id}")
    private String tenantId;

    @Value("${femprobien.microsoft.client-id}")
    private String clientId;

    @Value("${femprobien.microsoft.client-secret}")
    private String clientSecret;

    private volatile String accessTokenCache;
    private volatile long accessTokenExpiraEnMillis;


    public MicrosoftGraphTokenService(
            @Qualifier("femprobienGraphRestTemplate")
            RestTemplate restTemplate) {

        this.restTemplate =
                restTemplate;
    }


    @PostConstruct
    public void validarConfiguracionInicial() {

        String secretWindows =
                System.getenv(
                        "FEMPROBIEN_MS_CLIENT_SECRET"
                );


        System.out.println(
                "[FEMPROBIEN-GRAPH] Tenant configurado: " +
                        tieneValor(
                                tenantId
                        )
        );


        System.out.println(
                "[FEMPROBIEN-GRAPH] Client ID configurado: " +
                        tieneValor(
                                clientId
                        )
        );


        System.out.println(
                "[FEMPROBIEN-GRAPH] Variable Windows FEMPROBIEN_MS_CLIENT_SECRET encontrada: " +
                        tieneValor(
                                secretWindows
                        )
        );


        System.out.println(
                "[FEMPROBIEN-GRAPH] Client Secret configurado en Spring: " +
                        tieneValor(
                                clientSecret
                        )
        );
    }


    public synchronized String obtenerAccessToken() {

        validarConfiguracion();


        long ahora =
                System.currentTimeMillis();


        if (
                accessTokenCache != null &&
                        !accessTokenCache.trim().isEmpty() &&
                        ahora < accessTokenExpiraEnMillis - 60000L
        ) {

            return accessTokenCache;
        }


        String tokenUrl =
                "https://login.microsoftonline.com/" +
                        tenantId.trim() +
                        "/oauth2/v2.0/token";


        HttpHeaders headers =
                new HttpHeaders();


        headers.setContentType(
                MediaType.APPLICATION_FORM_URLENCODED
        );


        MultiValueMap<String, String> form =
                new LinkedMultiValueMap<String, String>();


        form.add(
                "client_id",
                clientId.trim()
        );


        form.add(
                "client_secret",
                clientSecret
        );


        form.add(
                "scope",
                GRAPH_SCOPE
        );


        form.add(
                "grant_type",
                "client_credentials"
        );


        HttpEntity<MultiValueMap<String, String>> request =
                new HttpEntity<MultiValueMap<String, String>>(
                        form,
                        headers
                );


        ResponseEntity<Map> response;


        try {

            response =
                    restTemplate.postForEntity(
                            tokenUrl,
                            request,
                            Map.class
                    );

        } catch (HttpStatusCodeException e) {

            throw new IllegalStateException(
                    "Error solicitando token a Microsoft Entra. HTTP " +
                            e.getRawStatusCode() +
                            ". Respuesta: " +
                            limitarTexto(
                                    e.getResponseBodyAsString(),
                                    1200
                            )
            );
        }


        if (
                !response.getStatusCode().is2xxSuccessful() ||
                        response.getBody() == null
        ) {

            throw new IllegalStateException(
                    "Microsoft Entra no devolvió un token válido."
            );
        }


        Map body =
                response.getBody();


        Object tokenObj =
                body.get(
                        "access_token"
                );


        if (
                tokenObj == null ||
                        tokenObj.toString().trim().isEmpty()
        ) {

            throw new IllegalStateException(
                    "Microsoft Entra no devolvió access_token."
            );
        }


        long expiresInSeconds =
                obtenerLong(
                        body.get(
                                "expires_in"
                        ),
                        3600L
                );


        accessTokenCache =
                tokenObj
                        .toString()
                        .trim();


        accessTokenExpiraEnMillis =
                System.currentTimeMillis() +
                        expiresInSeconds * 1000L;


        System.out.println(
                "[FEMPROBIEN-GRAPH] Access token obtenido/renovado correctamente."
        );


        return accessTokenCache;
    }


    private void validarConfiguracion() {

        if (!tieneValor(tenantId)) {

            throw new IllegalStateException(
                    "No está configurado femprobien.microsoft.tenant-id."
            );
        }


        if (!tieneValor(clientId)) {

            throw new IllegalStateException(
                    "No está configurado femprobien.microsoft.client-id."
            );
        }


        if (!tieneValor(clientSecret)) {

            throw new IllegalStateException(
                    "No está configurado femprobien.microsoft.client-secret."
            );
        }
    }


    private boolean tieneValor(
            String valor) {

        return valor != null &&
                !valor.trim().isEmpty();
    }


    private long obtenerLong(
            Object valor,
            long valorDefault) {

        if (valor == null) {

            return valorDefault;
        }


        if (valor instanceof Number) {

            return ((Number) valor)
                    .longValue();
        }


        try {

            return Long.parseLong(
                    valor
                            .toString()
                            .trim()
            );

        } catch (Exception e) {

            return valorDefault;
        }
    }


    private String limitarTexto(
            String texto,
            int maximo) {

        if (texto == null) {

            return "";
        }


        String limpio =
                texto.trim();


        if (limpio.length() <= maximo) {

            return limpio;
        }


        return limpio.substring(
                0,
                maximo
        );
    }
}
