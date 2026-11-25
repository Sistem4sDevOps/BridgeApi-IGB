package com.IGB.BridgeApi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Base64;

/**
 * Verifica ante el backend REAL de WMS un JWT emitido por WMS.
 * No comparte claves de firma, no autentica usando X-Femprobien-User
 * y no cambia SecurityConfig ni el filtro JWT de BridgeApi.
 *
 * REQUISITO DE SEGURIDAD: /user/validate en WMS debe rechazar con
 * HTTP 401/403 tokens con firma alterada. Se comprueba por cada descarga.
 */
@Service
public class FemprobienWmsIdentidadService {

    private static final int MAX_TOKEN = 8192;
    private final String wmsValidateUrl;
    private final RestTemplate http;
    private final ObjectMapper json;

    public FemprobienWmsIdentidadService(
            @Value("${femprobien.excel.wms.validate-url:}") String wmsValidateUrl) {
        this.wmsValidateUrl = wmsValidateUrl == null ? "" : wmsValidateUrl.trim();
        this.json = new ObjectMapper();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String method)
                    throws IOException {
                super.prepareConnection(connection, method);
                // No reenviar un JWT a una ubicacion de redireccion.
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(4000);
        factory.setReadTimeout(5000);
        this.http = new RestTemplate(factory);
    }

    public String usuarioVerificado(String authorization) {
        URI destino = urlConfigurada();
        String token = token(authorization);
        TokenDatos datos = leerToken(token);

        // PRUEBA CRITICA: mismo JWT y misma firma, pero username manipulado.
        // Si WMS acepta esa copia, /user/validate NO garantiza autenticacion.
        String falsificado = alterarUsuarioSinFirmar(token, datos);
        if (!rechazaTokenFalsificado(destino, falsificado)) {
            throw new IllegalStateException(
                    "WMS /user/validate no rechazo un JWT con firma alterada; exportacion bloqueada.");
        }

        ResponseEntity<String> respuesta;
        try {
            respuesta = llamarWms(destino, token);
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode() == HttpStatus.UNAUTHORIZED ||
                    ex.getStatusCode() == HttpStatus.FORBIDDEN) {
                throw new IllegalArgumentException("Token de sesion WMS invalido o vencido.");
            }
            throw new IllegalStateException("Error de WMS al validar identidad: HTTP "
                    + ex.getRawStatusCode());
        } catch (ResourceAccessException ex) {
            throw new IllegalStateException("BridgeApi no puede conectar de forma segura con WMS.");
        }

        if (respuesta.getStatusCode() != HttpStatus.OK ||
                !respuestaDeWmsValida(respuesta)) {
            throw new IllegalArgumentException("WMS no confirmo la autenticacion.");
        }

        // Username SOLO se lee del JWT DESPUES de que WMS comprobo
        // su firma y vencimiento. El navegador jamas decide el username.
        return datos.usuario;
    }

    private URI urlConfigurada() {
        if (wmsValidateUrl.isEmpty()) {
            throw new IllegalStateException(
                    "Falta propiedad femprobien.excel.wms.validate-url en BridgeApi.");
        }
        try {
            URI uri = URI.create(wmsValidateUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) ||
                    uri.getHost() == null || uri.getHost().isEmpty() ||
                    uri.getUserInfo() != null || uri.getFragment() != null ||
                    uri.getRawQuery() != null ||
                    !uri.getPath().endsWith("/user/validate")) {
                throw new IllegalArgumentException("WMS HTTPS URL invalida.");
            }
            return uri;
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "Configurar una URL HTTPS fija que termine en /user/validate.");
        }
    }

    private static String token(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Falta token WMS en Authorization: Bearer.");
        }
        String jwt = auth.substring(7).trim();
        if (jwt.length() < 20 || jwt.length() > MAX_TOKEN ||
                jwt.matches(".*\\s.*")) {
            throw new IllegalArgumentException("Formato de token WMS invalido.");
        }
        return jwt;
    }

    private TokenDatos leerToken(String jwt) {
        try {
            String[] partes = jwt.split("\\.", -1);
            if (partes.length != 3 || partes[2].isEmpty()) {
                throw new IllegalArgumentException("JWT WMS invalido.");
            }
            JsonNode encabezado = json.readTree(Base64.getUrlDecoder().decode(partes[0]));
            if (encabezado == null || !"HS256".equals(encabezado.path("alg").asText())) {
                throw new IllegalArgumentException("Algoritmo JWT WMS no reconocido.");
            }
            byte[] payload = Base64.getUrlDecoder().decode(partes[1]);
            JsonNode cuerpo = json.readTree(payload);
            if (cuerpo == null || !cuerpo.isObject()) {
                throw new IllegalArgumentException("Contenido JWT WMS invalido.");
            }
            String usuario = cuerpo.path("username").asText("").trim();
            if (!usuario.matches("[a-zA-Z0-9._-]{2,60}")) {
                throw new IllegalArgumentException("El token WMS no contiene username valido.");
            }
            JsonNode exp = cuerpo.path("exp");
            long ahora = System.currentTimeMillis() / 1000L;
            if (!exp.isNumber() || exp.asLong() <= ahora ||
                    exp.asLong() > ahora + 24L * 3600L) {
                throw new IllegalArgumentException("Token WMS vencido o vigencia inesperada.");
            }
            JsonNode nbf = cuerpo.path("nbf");
            if (nbf.isNumber() && nbf.asLong() > ahora + 30) {
                throw new IllegalArgumentException("Token WMS aun no vigente.");
            }
            JsonNode iat = cuerpo.path("iat");
            if (iat.isNumber() && iat.asLong() > ahora + 30) {
                throw new IllegalArgumentException("Emision de token WMS invalida.");
            }
            return new TokenDatos(usuario, cuerpo);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("No es posible leer el token WMS.");
        }
    }

    private String alterarUsuarioSinFirmar(String jwt, TokenDatos datos) {
        try {
            ObjectNode alterado = ((ObjectNode) datos.payload).deepCopy();
            alterado.put("username", datos.usuario + "_ALTERADO");
            String[] p = jwt.split("\\.", -1);
            String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    json.writeValueAsBytes(alterado));
            return p[0] + "." + payload + "." + p[2];
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo verificar proteccion de firma WMS.");
        }
    }

    private boolean rechazaTokenFalsificado(URI destino, String jwtFalso) {
        try {
            // Cualquier 2xx es INSEGURO, independientemente del cuerpo.
            llamarWms(destino, jwtFalso);
            return false;
        } catch (HttpStatusCodeException ex) {
            return ex.getStatusCode() == HttpStatus.UNAUTHORIZED ||
                    ex.getStatusCode() == HttpStatus.FORBIDDEN;
        } catch (ResourceAccessException ex) {
            throw new IllegalStateException("Sin conexion HTTPS a validacion de WMS.");
        }
    }

    private ResponseEntity<String> llamarWms(URI destino, String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + jwt);
        headers.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setCacheControl("no-store");
        return http.exchange(destino, HttpMethod.GET,
                new HttpEntity<Void>(headers), String.class);
    }

    private boolean respuestaDeWmsValida(ResponseEntity<String> respuesta) {
        String body = respuesta.getBody();
        if (body == null || body.trim().isEmpty()) return false;
        try {
            JsonNode root = json.readTree(body);
            if (root == null || !root.isObject()) return false;
            // ResponseDTO de WMS usa un codigo de resultado. Rechazar valores
            // no reconocidos; nunca tratar HTML o un proxy como autenticacion.
            JsonNode codigo = root.get("code");
            if (codigo == null) codigo = root.get("status");
            if (codigo == null) codigo = root.get("result");
            if (codigo == null) codigo = root.get("resultCode");
            if (codigo == null) return false;
            return (codigo.isNumber() && codigo.asInt() == 0) ||
                    (codigo.isTextual() && "0".equals(codigo.asText()));
        } catch (IOException ex) {
            return false;
        }
    }

    private static final class TokenDatos {
        final String usuario;
        final JsonNode payload;
        TokenDatos(String usuario, JsonNode payload) {
            this.usuario = usuario;
            this.payload = payload;
        }
    }
}
