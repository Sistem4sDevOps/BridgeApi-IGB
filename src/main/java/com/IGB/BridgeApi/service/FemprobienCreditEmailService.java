package com.IGB.BridgeApi.service;

import com.IGB.BridgeApi.dto.CreditStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriUtils;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class FemprobienCreditEmailService {

    private final RestTemplate restTemplate;
    private final MicrosoftGraphTokenService tokenService;
    private final JdbcTemplate jdbcTemplate;

    @Value("${femprobien.microsoft.sender}")
    private String sender;

    @Value("${femprobien.microsoft.graph-base-url:https://graph.microsoft.com/v1.0}")
    private String graphBaseUrl;

    @Value("${femprobien.creditos.correo.nombre:FEMPROBIEN}")
    private String nombreEntidad;

    @Value("${femprobien.creditos.correo.firma-resource:mail/femprobien/firma_correo_femprobien.png}")
    private String firmaResource;


    public FemprobienCreditEmailService(
            @Qualifier("femprobienGraphRestTemplate")
            RestTemplate restTemplate,
            MicrosoftGraphTokenService tokenService,
            @Qualifier("sqlServerJdbcTemplate")
            JdbcTemplate jdbcTemplate) {

        this.restTemplate =
                restTemplate;

        this.tokenService =
                tokenService;

        this.jdbcTemplate =
                jdbcTemplate;
    }

    public Map<String, Object> notificarCambioEstado(
            Map<String, Object> solicitud,
            CreditStatus estado,
            String comentario,
            String usuario) {

        Map<String, Object> resultado =
                new HashMap<String, Object>();


        Integer idSolicitud =
                toInteger(
                        solicitud == null
                                ? null
                                : solicitud.get(
                                "id"
                        )
                );


        Integer numeroSolicitud =
                toInteger(
                        solicitud == null
                                ? null
                                : solicitud.get(
                                "numero_solicitud"
                        )
                );


        String destinatario =
                obtenerTexto(
                        solicitud == null
                                ? null
                                : solicitud.get(
                                "email_personal_solicitante"
                        )
                );


        String asunto =
                construirAsunto(
                        estado,
                        numeroSolicitud
                );


        resultado.put(
                "enviado",
                false
        );

        resultado.put(
                "destinatario",
                destinatario
        );

        resultado.put(
                "asunto",
                asunto
        );


        try {

            validarDatosEnvio(
                    solicitud,
                    estado,
                    destinatario
            );


            byte[] firmaInstitucional =
                    cargarFirmaInstitucional();


            String html =
                    construirHtml(
                            solicitud,
                            estado,
                            comentario,
                            firmaInstitucional.length > 0
                    );


            enviarMicrosoftGraph(
                    destinatario,
                    asunto,
                    html,
                    firmaInstitucional
            );


            registrarAuditoriaSeguro(
                    idSolicitud,
                    numeroSolicitud,
                    estado,
                    destinatario,
                    asunto,
                    comentario,
                    true,
                    null,
                    usuario
            );


            resultado.put(
                    "enviado",
                    true
            );

            resultado.put(
                    "message",
                    "Correo enviado correctamente."
            );


            System.out.println(
                    "[FEMPROBIEN-CORREO] Solicitud #" +
                            numeroSolicitud +
                            " notificada a " +
                            destinatario +
                            " con estado " +
                            estado.name() +
                            "."
            );


            return resultado;


        } catch (Exception e) {

            String error =
                    obtenerMensajeSeguro(
                            e
                    );


            registrarAuditoriaSeguro(
                    idSolicitud,
                    numeroSolicitud,
                    estado,
                    destinatario,
                    asunto,
                    comentario,
                    false,
                    error,
                    usuario
            );


            resultado.put(
                    "enviado",
                    false
            );

            resultado.put(
                    "message",
                    error
            );


            System.err.println(
                    "[FEMPROBIEN-CORREO] No fue posible enviar correo " +
                            "de la solicitud #" +
                            numeroSolicitud +
                            ". Estado: " +
                            (
                                    estado == null
                                            ? "(sin estado)"
                                            : estado.name()
                            ) +
                            ". Destinatario: " +
                            destinatario +
                            ". Error: " +
                            error
            );


            return resultado;
        }
    }


    /* =========================================================
       MICROSOFT GRAPH
       ========================================================= */

    private void enviarMicrosoftGraph(
            String destinatario,
            String asunto,
            String html,
            byte[] firmaInstitucional) {

        validarConfiguracionMicrosoft();


        String token =
                tokenService
                        .obtenerAccessToken();


        String senderEncoded =
                UriUtils.encodePathSegment(
                        sender.trim(),
                        StandardCharsets.UTF_8
                );


        String url =
                quitarSlashFinal(
                        graphBaseUrl
                ) +
                        "/users/" +
                        senderEncoded +
                        "/sendMail";


        Map<String, Object> body =
                new HashMap<String, Object>();


        Map<String, Object> message =
                new HashMap<String, Object>();

        message.put(
                "subject",
                asunto
        );


        Map<String, Object> contenido =
                new HashMap<String, Object>();

        contenido.put(
                "contentType",
                "HTML"
        );

        contenido.put(
                "content",
                html
        );

        message.put(
                "body",
                contenido
        );


        List<Map<String, Object>> destinatarios =
                new ArrayList<Map<String, Object>>();


        Map<String, Object> recipient =
                new HashMap<String, Object>();


        Map<String, Object> emailAddress =
                new HashMap<String, Object>();

        emailAddress.put(
                "address",
                destinatario
        );


        recipient.put(
                "emailAddress",
                emailAddress
        );


        destinatarios.add(
                recipient
        );


        message.put(
                "toRecipients",
                destinatarios
        );

        if (
                firmaInstitucional != null &&
                        firmaInstitucional.length > 0
        ) {

            Map<String, Object> firmaAttachment =
                    new HashMap<String, Object>();

            firmaAttachment.put(
                    "@odata.type",
                    "#microsoft.graph.fileAttachment"
            );

            firmaAttachment.put(
                    "name",
                    "firma_correo_femprobien.png"
            );

            firmaAttachment.put(
                    "contentType",
                    "image/png"
            );

            firmaAttachment.put(
                    "isInline",
                    true
            );

            firmaAttachment.put(
                    "contentId",
                    "firmaFemprobien"
            );

            firmaAttachment.put(
                    "contentBytes",
                    Base64
                            .getEncoder()
                            .encodeToString(
                                    firmaInstitucional
                            )
            );

            List<Map<String, Object>> attachments =
                    new ArrayList<Map<String, Object>>();

            attachments.add(
                    firmaAttachment
            );

            message.put(
                    "attachments",
                    attachments
            );
        }


        body.put(
                "message",
                message
        );

        body.put(
                "saveToSentItems",
                true
        );


        HttpHeaders headers =
                new HttpHeaders();

        headers.setContentType(
                MediaType.APPLICATION_JSON
        );

        headers.setBearerAuth(
                token
        );


        HttpEntity<Map<String, Object>> entity =
                new HttpEntity<Map<String, Object>>(
                        body,
                        headers
                );


        ResponseEntity<String> response;


        try {

            response =
                    restTemplate.postForEntity(
                            url,
                            entity,
                            String.class
                    );

        } catch (org.springframework.web.client.HttpStatusCodeException e) {

            throw new IllegalStateException(
                    "Microsoft Graph respondió HTTP " +
                            e.getRawStatusCode() +
                            ". Respuesta: " +
                            limitarTexto(
                                    e.getResponseBodyAsString(),
                                    1500
                            )
            );
        }


        if (
                !response
                        .getStatusCode()
                        .is2xxSuccessful()
        ) {

            throw new IllegalStateException(
                    "Microsoft Graph respondió HTTP " +
                            response
                                    .getStatusCodeValue() +
                            "."
            );
        }
    }

    private String construirAsunto(
            CreditStatus estado,
            Integer numeroSolicitud) {

        String numero =
                numeroSolicitud == null
                        ? ""
                        : " #" +
                        numeroSolicitud;


        if (
                estado ==
                        CreditStatus.EN_ESTUDIO
        ) {

            return "FEMPROBIEN | Solicitud de crédito" +
                    numero +
                    " en estudio";
        }


        if (
                estado ==
                        CreditStatus.APROBADA
        ) {

            return "FEMPROBIEN | Solicitud de crédito" +
                    numero +
                    " aprobada";
        }


        if (
                estado ==
                        CreditStatus.APLAZADA
        ) {

            return "FEMPROBIEN | Solicitud de crédito" +
                    numero +
                    " aplazada";
        }


        if (
                estado ==
                        CreditStatus.NEGADA
        ) {

            return "FEMPROBIEN | Resultado solicitud de crédito" +
                    numero;
        }


        return "FEMPROBIEN | Actualización solicitud de crédito" +
                numero;
    }

    private String construirHtml(
            Map<String, Object> solicitud,
            CreditStatus estado,
            String comentario,
            boolean incluirImagenFirma) {

        String nombre =
                obtenerTexto(
                        solicitud.get(
                                "nombre_completo_solicitante"
                        )
                );


        Integer numeroSolicitud =
                toInteger(
                        solicitud.get(
                                "numero_solicitud"
                        )
                );


        String destinoCredito =
                obtenerTexto(
                        solicitud.get(
                                "destino_credito"
                        )
                );


        String monto =
                formatearMoneda(
                        solicitud.get(
                                "monto_solicitado"
                        )
                );


        String plazo =
                obtenerTexto(
                        solicitud.get(
                                "plazo_meses"
                        )
                );


        String titulo;
        String mensajePrincipal;
        String colorEstado;


        if (
                estado ==
                        CreditStatus.EN_ESTUDIO
        ) {

            titulo =
                    "Tu solicitud está en estudio";

            mensajePrincipal =
                    "Tu solicitud de crédito fue recibida por el equipo responsable " +
                            "y actualmente se encuentra en etapa de estudio.";

            colorEstado =
                    "#31708f";

        } else if (
                estado ==
                        CreditStatus.APROBADA
        ) {

            titulo =
                    "Tu solicitud de crédito fue aprobada";

            mensajePrincipal =
                    "Nos permitimos informarte que tu solicitud de crédito " +
                            "ha sido aprobada.";

            colorEstado =
                    "#3c763d";

        } else if (
                estado ==
                        CreditStatus.APLAZADA
        ) {

            titulo =
                    "Tu solicitud de crédito fue aplazada";

            mensajePrincipal =
                    "La solicitud requiere una revisión o gestión adicional antes " +
                            "de continuar con el proceso.";

            colorEstado =
                    "#8a6d3b";

        } else if (
                estado ==
                        CreditStatus.NEGADA
        ) {

            titulo =
                    "Actualización de tu solicitud de crédito";

            mensajePrincipal =
                    "Luego de la revisión correspondiente, tu solicitud de crédito " +
                            "no fue aprobada.";

            colorEstado =
                    "#a94442";

        } else {

            titulo =
                    "Actualización de tu solicitud de crédito";

            mensajePrincipal =
                    "El estado de tu solicitud de crédito fue actualizado.";

            colorEstado =
                    "#555555";
        }


        String bloqueComentario =
                "";


        if (
                comentario != null &&
                        !comentario.trim().isEmpty()
        ) {

            bloqueComentario =
                    "<div style=\"margin-top:20px;padding:14px 16px;" +
                            "background:#f7f7f7;border-left:4px solid #d93a37;" +
                            "border-radius:4px;\">" +
                            "<div style=\"font-size:12px;color:#666666;" +
                            "font-weight:bold;margin-bottom:5px;\">" +
                            "OBSERVACIÓN" +
                            "</div>" +
                            "<div style=\"font-size:14px;color:#333333;" +
                            "line-height:1.5;\">" +
                            escaparHtml(
                                    comentario.trim()
                            ) +
                            "</div>" +
                            "</div>";
        }


        String filaMonto =
                monto.isEmpty()
                        ? ""
                        : filaResumen(
                        "Monto solicitado",
                        monto
                );


        String filaPlazo =
                plazo.isEmpty()
                        ? ""
                        : filaResumen(
                        "Plazo",
                        escaparHtml(plazo) +
                                " meses"
                );


        String filaDestino =
                destinoCredito.isEmpty()
                        ? ""
                        : filaResumen(
                        "Destino",
                        escaparHtml(
                                destinoCredito
                        )
                );


        return "<!DOCTYPE html>" +
                "<html>" +
                "<head>" +
                "<meta charset=\"UTF-8\">" +
                "</head>" +
                "<body style=\"margin:0;padding:0;background:#f3f4f6;" +
                "font-family:Arial,Helvetica,sans-serif;color:#333333;\">" +

                "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" " +
                "style=\"background:#f3f4f6;padding:24px 10px;\">" +
                "<tr><td align=\"center\">" +

                "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" " +
                "style=\"max-width:620px;background:#ffffff;border:1px solid #e1e1e1;" +
                "border-radius:8px;overflow:hidden;\">" +

                "<tr><td style=\"background:#ffffff;border-top:4px solid #d93a37;" +
                "padding:22px 26px 14px 26px;\">" +

                "<div style=\"font-size:22px;font-weight:bold;color:#1e4f80;\">" +
                escaparHtml(
                        nombreEntidad
                ) +
                "</div>" +

                "<div style=\"font-size:12px;color:#777777;margin-top:3px;\">" +
                "Fondo de Empleados" +
                "</div>" +

                "</td></tr>" +

                "<tr><td style=\"padding:8px 26px 26px 26px;\">" +

                "<p style=\"font-size:14px;line-height:1.6;margin:8px 0 18px 0;\">" +
                "Hola <strong>" +
                escaparHtml(
                        nombre
                ) +
                "</strong>," +
                "</p>" +

                "<div style=\"font-size:20px;font-weight:bold;color:" +
                colorEstado +
                ";margin-bottom:10px;\">" +
                titulo +
                "</div>" +

                "<p style=\"font-size:14px;line-height:1.6;margin:0 0 18px 0;\">" +
                mensajePrincipal +
                "</p>" +

                "<div style=\"background:#fafafa;border:1px solid #e6e6e6;" +
                "border-radius:6px;padding:14px 16px;\">" +

                filaResumen(
                        "Solicitud",
                        "#" +
                                numeroSolicitud
                ) +

                filaResumen(
                        "Estado",
                        etiquetaEstado(
                                estado
                        )
                ) +

                filaMonto +
                filaPlazo +
                filaDestino +

                "</div>" +

                bloqueComentario +

                "<p style=\"font-size:13px;line-height:1.6;color:#666666;" +
                "margin:24px 0 0 0;\">" +
                "Este correo fue generado automáticamente por FEMPROBIEN. " +
                "Si necesitas ampliar la información, comunícate con el Fondo de Empleados." +
                "</p>" +

                "</td></tr>" +

                "<tr><td style=\"padding:0;\">" +
                construirFirmaInstitucionalHtml(
                        incluirImagenFirma
                ) +
                "</td></tr>" +

                "</table>" +

                "</td></tr>" +
                "</table>" +

                "</body>" +
                "</html>";
    }


    /* =========================================================
       FIRMA INSTITUCIONAL DEL CORREO
       ========================================================= */

    private byte[] cargarFirmaInstitucional() {

        String ruta =
                firmaResource == null
                        ? ""
                        : firmaResource.trim();


        if (ruta.startsWith("classpath:")) {
            ruta =
                    ruta.substring(
                            "classpath:".length()
                    );
        }


        while (ruta.startsWith("/")) {
            ruta =
                    ruta.substring(1);
        }


        if (ruta.isEmpty()) {
            throw new IllegalStateException(
                    "No está configurado femprobien.creditos.correo.firma-resource."
            );
        }


        ClassPathResource resource =
                new ClassPathResource(
                        ruta
                );


        if (!resource.exists()) {
            throw new IllegalStateException(
                    "No se encontró la firma institucional del correo en classpath:/" +
                            ruta +
                            "."
            );
        }


        try (InputStream inputStream =
                     resource.getInputStream()) {

            byte[] bytes =
                    StreamUtils
                            .copyToByteArray(
                                    inputStream
                            );


            if (bytes.length <= 0) {
                throw new IllegalStateException(
                        "La imagen de firma institucional está vacía."
                );
            }


            return bytes;

        } catch (Exception e) {

            if (e instanceof IllegalStateException) {
                throw (IllegalStateException) e;
            }

            throw new IllegalStateException(
                    "No fue posible cargar la firma institucional del correo.",
                    e
            );
        }
    }


    private String construirFirmaInstitucionalHtml(
            boolean incluirImagenFirma) {

        String imagen =
                incluirImagenFirma
                        ? "<tr><td align=\"center\" style=\"padding:14px 20px 12px 20px;\">" +
                        "<img src=\"cid:firmaFemprobien\" " +
                        "alt=\"FEMPROBIEN - Fondo de Empleados\" " +
                        "width=\"520\" " +
                        "style=\"display:block;width:100%;max-width:520px;height:auto;" +
                        "border:0;outline:none;text-decoration:none;margin:0 auto;\">" +
                        "</td></tr>"
                        : "";


        return "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" " +
                "role=\"presentation\" " +
                "style=\"width:100%;background:#ffffff;border-collapse:collapse;" +
                "border-top:1px solid #e2e6ea;\">" +

                "<tr><td style=\"padding:20px 26px 4px 26px;" +
                "font-family:Arial,Helvetica,sans-serif;\">" +

                "<div style=\"font-size:15px;line-height:20px;font-weight:bold;" +
                "color:#315986;\">" +
                "ADMINISTRACIÓN" +
                "</div>" +

                "<div style=\"font-size:12px;line-height:18px;color:#555f6b;" +
                "margin-top:3px;\">" +
                "Calle 98 Sur Nro. 48 - 225 | Centro Industrial Puerta del Sur | " +
                "La Estrella, Colombia" +
                "</div>" +

                "</td></tr>" +

                imagen +

                "<tr><td style=\"padding:4px 26px 22px 26px;" +
                "font-family:Arial,Helvetica,sans-serif;font-size:10px;" +
                "line-height:15px;color:#737b84;font-style:italic;" +
                "font-weight:400;text-align:justify;\">" +

                "La información enviada en este correo, es exclusiva para la(s) persona(s) " +
                "a la(s) cual(es) se dirige y pueden contener información confidencial y/o " +
                "material privilegiado de FONDO DE EMPLEADOS DEL GRUPO IGB. " +
                "Cualquier revisión, retransmisión o uso del mismo, así como cualquier acción " +
                "que se tome respecto a la información contenida por personas o entidades " +
                "diferentes al propósito original de la misma, es ilegal. " +
                "Si recibe este mensaje por error por favor elimínelo." +

                "</td></tr>" +
                "</table>";
    }


    private String filaResumen(
            String etiqueta,
            String valor) {

        return "<div style=\"display:block;padding:5px 0;" +
                "font-size:13px;line-height:1.4;\">" +
                "<span style=\"display:inline-block;min-width:125px;" +
                "font-weight:bold;color:#444444;\">" +
                etiqueta +
                ":</span>" +
                "<span style=\"color:#333333;\">" +
                valor +
                "</span>" +
                "</div>";
    }


    private String etiquetaEstado(
            CreditStatus estado) {

        if (
                estado ==
                        CreditStatus.EN_ESTUDIO
        ) {

            return "EN ESTUDIO";
        }


        if (
                estado ==
                        CreditStatus.APROBADA
        ) {

            return "APROBADA";
        }


        if (
                estado ==
                        CreditStatus.APLAZADA
        ) {

            return "APLAZADA";
        }


        if (
                estado ==
                        CreditStatus.NEGADA
        ) {

            return "NO APROBADA";
        }


        return estado == null
                ? ""
                : estado.name();
    }

    private void registrarAuditoriaSeguro(
            Integer idSolicitud,
            Integer numeroSolicitud,
            CreditStatus estado,
            String destinatario,
            String asunto,
            String comentario,
            boolean enviado,
            String error,
            String usuario) {

        try {

            jdbcTemplate.update(
                    "INSERT INTO FEMPROBIEN.dbo.tblNotificacionCredito " +
                            "(id_solicitud, numero_solicitud, estado, destinatario, " +
                            "asunto, comentario, enviado, fecha_envio, error, usuario) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, " +
                            "CASE WHEN ? = 1 THEN SYSDATETIME() ELSE NULL END, ?, ?)",
                    idSolicitud,
                    numeroSolicitud,
                    estado == null
                            ? null
                            : estado.name(),
                    destinatario,
                    asunto,
                    comentario,
                    enviado,
                    enviado,
                    error,
                    usuario
            );

        } catch (Exception e) {
            System.err.println(
                    "[FEMPROBIEN-CORREO] No fue posible registrar auditoría " +
                            "de notificación. Error: " +
                            e.getMessage()
            );
        }
    }

    private void validarDatosEnvio(
            Map<String, Object> solicitud,
            CreditStatus estado,
            String destinatario) {

        if (
                solicitud == null ||
                        solicitud.isEmpty()
        ) {

            throw new IllegalArgumentException(
                    "No se recibió información de la solicitud para enviar el correo."
            );
        }


        if (
                estado == null
        ) {

            throw new IllegalArgumentException(
                    "No se recibió el estado del crédito."
            );
        }


        if (
                estado != CreditStatus.EN_ESTUDIO &&
                        estado != CreditStatus.APROBADA &&
                        estado != CreditStatus.APLAZADA &&
                        estado != CreditStatus.NEGADA
        ) {

            throw new IllegalArgumentException(
                    "El estado " +
                            estado.name() +
                            " no tiene notificación de correo configurada."
            );
        }


        if (
                destinatario == null ||
                        destinatario.trim().isEmpty()
        ) {

            throw new IllegalArgumentException(
                    "La solicitud no tiene correo electrónico del solicitante."
            );
        }


        if (
                !destinatario.contains("@") ||
                        destinatario.startsWith("@") ||
                        destinatario.endsWith("@")
        ) {

            throw new IllegalArgumentException(
                    "El correo electrónico del solicitante no tiene un formato válido."
            );
        }
    }


    private void validarConfiguracionMicrosoft() {

        if (
                sender == null ||
                        sender.trim().isEmpty()
        ) {

            throw new IllegalStateException(
                    "No está configurado femprobien.microsoft.sender."
            );
        }


        if (
                graphBaseUrl == null ||
                        graphBaseUrl.trim().isEmpty()
        ) {

            throw new IllegalStateException(
                    "No está configurado femprobien.microsoft.graph-base-url."
            );
        }
    }


    private String quitarSlashFinal(
            String valor) {

        String salida =
                valor == null
                        ? ""
                        : valor.trim();


        while (
                salida.endsWith("/")
        ) {

            salida =
                    salida.substring(
                            0,
                            salida.length() - 1
                    );
        }


        return salida;
    }


    private String obtenerTexto(
            Object valor) {

        if (valor == null) {
            return "";
        }


        return valor
                .toString()
                .trim();
    }


    private Integer toInteger(
            Object valor) {

        if (
                valor == null ||
                        valor.toString().trim().isEmpty()
        ) {

            return null;
        }


        if (valor instanceof Integer) {
            return (Integer) valor;
        }


        if (valor instanceof Number) {

            return ((Number) valor)
                    .intValue();
        }


        return Integer.valueOf(
                valor.toString().trim()
        );
    }


    private String formatearMoneda(
            Object valor) {

        if (
                valor == null ||
                        valor.toString().trim().isEmpty()
        ) {

            return "";
        }


        try {

            BigDecimal numero =
                    valor instanceof BigDecimal
                            ? (BigDecimal) valor
                            : new BigDecimal(
                            valor.toString().trim()
                    );


            NumberFormat formato =
                    NumberFormat.getCurrencyInstance(
                            new Locale(
                                    "es",
                                    "CO"
                            )
                    );


            return formato.format(
                    numero
            );


        } catch (Exception e) {

            return obtenerTexto(
                    valor
            );
        }
    }


    private String escaparHtml(
            String texto) {

        if (texto == null) {
            return "";
        }


        return texto
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
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



    private String obtenerMensajeSeguro(
            Exception e) {

        if (e == null) {

            return "Error desconocido enviando el correo.";
        }


        String mensaje =
                e.getMessage();


        if (
                mensaje == null ||
                        mensaje.trim().isEmpty()
        ) {

            return e
                    .getClass()
                    .getSimpleName();
        }


        if (
                mensaje.length() > 1800
        ) {

            return mensaje.substring(
                    0,
                    1800
            );
        }


        return mensaje;
    }
}
