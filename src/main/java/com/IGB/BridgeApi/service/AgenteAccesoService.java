package com.IGB.BridgeApi.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AgenteAccesoService {

    // Una sola instancia BridgeApi: los códigos y sesiones quedan en memoria.
    // Si se balancea entre servidores, migrar este almacenamiento a SQL Server o Redis.
    private static final long CODIGO_TTL = 5 * 60 * 1000L;
    private static final long SESION_TTL = 15 * 60 * 1000L;
    private static final long REENVIO_TTL = 60 * 1000L;
    private static final long VENTANA_LIMITE = 15 * 60 * 1000L;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, CodigoPendiente> pendientes = new ConcurrentHashMap<>();
    private final Map<String, Sesion> sesiones = new ConcurrentHashMap<>();
    private final Map<String, Ventana> limites = new ConcurrentHashMap<>();

    private final FemprobienRolesService roles;
    private final MicrosoftGraphTokenService graph;
    private final RestTemplate http;

    @Value("${femprobien.agente.usuarios-correo:}")
    private String usuariosCorreo;

    @Value("${femprobien.microsoft.sender:}")
    private String remitente;

    @Value("${femprobien.microsoft.graph-base-url:https://graph.microsoft.com/v1.0}")
    private String graphBase;

    public AgenteAccesoService(FemprobienRolesService roles,
                               MicrosoftGraphTokenService graph,
                               @Qualifier("femprobienGraphRestTemplate") RestTemplate http) {
        this.roles = roles;
        this.graph = graph;
        this.http = http;
    }

    public synchronized boolean permitirSolicitud(String ip) {
        return limitar("solicitud:" + ipSegura(ip), 120);
    }

    public synchronized boolean permitirVerificacion(String ip) {
        return limitar("verificacion:" + ipSegura(ip), 300);
    }

    private String ipSegura(String ip) {
        return ip == null ? "desconocida" : ip;
    }

    private boolean limitar(String clave, int maximo) {
        long ahora = System.currentTimeMillis();
        Ventana ventana = limites.get(clave);
        if (ventana == null || ahora - ventana.inicio > VENTANA_LIMITE) {
            ventana = new Ventana(ahora);
            limites.put(clave, ventana);
        }
        if (ventana.contador >= maximo) return false;
        ventana.contador++;
        // Limpieza oportunista para no dejar crecer la caché indefinidamente.
        if (limites.size() > 5000) {
            for (Map.Entry<String, Ventana> e : limites.entrySet()) {
                if (ahora - e.getValue().inicio > VENTANA_LIMITE) limites.remove(e.getKey());
            }
        }
        return true;
    }

    public synchronized void solicitar(String username) {
        String usuario = normalizar(username);
        if (!usuario.matches("[a-z0-9._-]{2,60}")) return;
        long ahora = System.currentTimeMillis();
        CodigoPendiente pendiente = pendientes.get(usuario);
        if (pendiente != null && ahora - pendiente.creado < REENVIO_TTL) return;
        if (!tieneAcceso(usuario)) return;

        String correo = obtenerCorreoPermitido(usuario);
        if (correo == null) return;

        int numero = random.nextInt(1000000);
        String codigo = String.format(Locale.ROOT, "%06d", numero);
        byte[] sal = new byte[16];
        random.nextBytes(sal);
        CodigoPendiente nuevo = new CodigoPendiente();
        nuevo.sal = sal;
        nuevo.resumen = hash(sal, codigo);
        nuevo.creado = ahora;
        nuevo.expira = ahora + CODIGO_TTL;
        nuevo.intentos = 0;
        // Almacenar solo después de confirmar el envío real.
        enviarCorreo(correo, codigo);
        pendientes.put(usuario, nuevo);
    }

    public synchronized String verificar(String username, String codigo) {
        String usuario = normalizar(username);
        if (!usuario.matches("[a-z0-9._-]{2,60}") || codigo == null || !codigo.matches("[0-9]{6}")) return null;
        CodigoPendiente pendiente = pendientes.get(usuario);
        if (pendiente == null) return null;
        long ahora = System.currentTimeMillis();
        if (ahora >= pendiente.expira || pendiente.intentos >= 5) {
            pendientes.remove(usuario);
            return null;
        }
        pendiente.intentos++;
        if (!MessageDigest.isEqual(pendiente.resumen, hash(pendiente.sal, codigo))) {
            if (pendiente.intentos >= 5) pendientes.remove(usuario);
            return null;
        }
        pendientes.remove(usuario);
        if (!tieneAcceso(usuario) || obtenerCorreoPermitido(usuario) == null) return null;

        byte[] secreto = new byte[32];
        random.nextBytes(secreto);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secreto);
        sesiones.put(hashToken(token), new Sesion(usuario, ahora + SESION_TTL));
        return token;
    }

    public synchronized String validarSesion(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        String llave = hashToken(token);
        Sesion sesion = sesiones.get(llave);
        if (sesion == null) return null;
        if (System.currentTimeMillis() >= sesion.expira || !tieneAcceso(sesion.usuario)
                || obtenerCorreoPermitido(sesion.usuario) == null) {
            sesiones.remove(llave);
            return null;
        }
        return sesion.usuario;
    }

    public synchronized void cerrar(String token) {
        if (token != null && token.matches("[A-Za-z0-9_-]{43}")) sesiones.remove(hashToken(token));
    }

    private boolean tieneAcceso(String usuario) {
        try {
            return roles.tienePermiso(usuario, "APROBAR_SOLICITUDES") ||
                    roles.consultarRoles(usuario).contains("ADMIN_FEMPROBIEN");
        } catch (Exception ex) {
            return false;
        }
    }

    // El correo solo se obtiene de una lista administrada en el SERVIDOR.
    // Formato: usuario1:correo1@dominio,usuario2:correo2@dominio
    private String obtenerCorreoPermitido(String usuario) {
        if (usuariosCorreo == null || usuariosCorreo.trim().isEmpty()) return null;
        for (String linea : usuariosCorreo.split(",")) {
            String[] partes = linea.trim().split(":", 2);
            if (partes.length != 2 || !normalizar(partes[0]).equals(usuario)) continue;
            String correo = partes[1].trim();
            if (correo.matches("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")) return correo;
        }
        return null;
    }

    private void enviarCorreo(String correo, String codigo) {
        if (remitente == null || remitente.trim().isEmpty())
            throw new IllegalStateException("El remitente de Graph no está configurado.");
        String tokenGraph = graph.obtenerAccessToken();
        String remitenteUrl = UriUtils.encodePathSegment(remitente.trim(), StandardCharsets.UTF_8);
        String url = graphBase.replaceAll("/+$", "") + "/users/" + remitenteUrl + "/sendMail";

        Map<String, Object> contenido = new LinkedHashMap<>();
        contenido.put("contentType", "Text");
        contenido.put("content", "Tu código de acceso al agente financiero FEMPROBIEN es " + codigo +
                ". Vence en 5 minutos. No lo compartas.");
        Map<String, Object> correoDestino = new HashMap<>();
        correoDestino.put("address", correo);
        Map<String, Object> destinatario = new HashMap<>();
        destinatario.put("emailAddress", correoDestino);
        List<Map<String, Object>> destinatarios = new ArrayList<>();
        destinatarios.add(destinatario);
        Map<String, Object> mensaje = new HashMap<>();
        mensaje.put("subject", "Código de acceso IA FEMPROBIEN");
        mensaje.put("body", contenido);
        mensaje.put("toRecipients", destinatarios);
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("message", mensaje);
        cuerpo.put("saveToSentItems", false);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokenGraph);
        headers.setContentType(MediaType.APPLICATION_JSON);
        http.postForEntity(url, new HttpEntity<>(cuerpo, headers), String.class);
    }

    private String normalizar(String texto) {
        return texto == null ? "" : texto.trim().toLowerCase(Locale.ROOT);
    }

    private byte[] hash(byte[] sal, String codigo) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(sal);
            return digest.digest(codigo.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo generar el hash de seguridad.");
        }
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo validar el token temporal.");
        }
    }

    private static class CodigoPendiente {
        byte[] sal;
        byte[] resumen;
        long creado;
        long expira;
        int intentos;
    }
    private static class Sesion {
        final String usuario;
        final long expira;
        Sesion(String usuario, long expira) { this.usuario = usuario; this.expira = expira; }
    }
    private static class Ventana {
        final long inicio;
        int contador;
        Ventana(long inicio) { this.inicio = inicio; this.contador = 0; }
    }
}
