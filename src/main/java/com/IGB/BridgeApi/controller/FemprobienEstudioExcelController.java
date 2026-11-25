package com.IGB.BridgeApi.controller;

import com.IGB.BridgeApi.service.FemprobienEstudioExcelService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FEMPROBIEN: exportacion directa sin validacion adicional de sesion en BridgeApi.
 *
 * ATENCION SEGURIDAD: /femprobien/** esta en permitAll() y este controlador
 * NO autentica al solicitante. CUALQUIER PERSONA con acceso a esta URL puede
 * descargar nominas y datos crediticios de solicitudes cuyos IDs conozca.
 * Publicar esta ruta UNICAMENTE detras de un control de acceso confiable
 * (proxy de WMS con autenticacion, VPN o segmentacion de red controlada).
 * No considerar @ConditionalOnProperty una medida de autenticacion.
 */
@RestController
@RequestMapping("/femprobien/evaluacion")
@ConditionalOnProperty(name = "femprobien.evaluacion.test-habilitado", havingValue = "true")
public class FemprobienEstudioExcelController {

    private static final Logger LOG = LoggerFactory.getLogger(FemprobienEstudioExcelController.class);

    private static final String PLANTILLA =
            "femprobien/Plantilla_Estudio_Credito_FEMPROBIEN_Base.xlsx";

    private static final MediaType EXCEL = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final FemprobienEstudioExcelService servicio;

    public FemprobienEstudioExcelController(FemprobienEstudioExcelService servicio) {
        this.servicio = servicio;
    }

    /** Plantilla vacia. No contiene datos de asociados. */
    @GetMapping("/plantilla-estudio-excel")
    public ResponseEntity<?> obtenerPlantilla() {
        try {
            ClassPathResource archivo = new ClassPathResource(PLANTILLA);
            if (!archivo.exists()) {
                return error(HttpStatus.NOT_FOUND, "No se encontro la plantilla Excel.");
            }
            byte[] bytes;
            try (InputStream entrada = archivo.getInputStream()) {
                bytes = StreamUtils.copyToByteArray(entrada);
            }
            return respuestaExcel(bytes, "Plantilla_Estudio_Credito_FEMPROBIEN_Base.xlsx", false);
        } catch (Exception ex) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR,
                    "No fue posible cargar la plantilla de estudio.");
        }
    }

    /**
     * POST /femprobien/evaluacion/estudios-excel
     * Body: {"numeroSolicitudes":[7]} o {"numeroSolicitudes":[7,8,9]}.
     * Sin token, sin codigo y sin Principal. Solo se reciben numeros;
     * FemprobienEstudioExcelService consulta los datos desde la base oficial.
     */
    @PostMapping(value = "/estudios-excel", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> descargar(@RequestBody(required = false) Map<String, Object> datos) {
        try {
            Object lista = datos == null ? null : datos.get("numeroSolicitudes");
            if (!(lista instanceof List)) {
                return error(HttpStatus.BAD_REQUEST, "Debe enviar numeroSolicitudes como lista.");
            }

            List<Integer> numeros = new ArrayList<Integer>();
            for (Object valor : (List<?>) lista) {
                if (!(valor instanceof Number)) {
                    return error(HttpStatus.BAD_REQUEST, "Numero de solicitud invalido.");
                }
                Number numero = (Number) valor;
                double n = numero.doubleValue();
                if (Double.isNaN(n) || Double.isInfinite(n) || n <= 0 ||
                        n > Integer.MAX_VALUE || n != numero.intValue()) {
                    return error(HttpStatus.BAD_REQUEST, "Numero de solicitud invalido.");
                }
                numeros.add(numero.intValue());
            }

            numeros = FemprobienEstudioExcelService.validarNumeros(numeros);
            byte[] excel = servicio.generar(numeros);
            if (excel == null || excel.length == 0) {
                return error(HttpStatus.UNPROCESSABLE_ENTITY,
                        "BridgeApi no genero contenido Excel.");
            }

            String nombre = numeros.size() == 1
                    ? "Estudio_FEMPROBIEN_" + numeros.get(0) + ".xlsx"
                    : "Estudios_FEMPROBIEN_" +
                    new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".xlsx";
            return respuestaExcel(excel, nombre, true);
        } catch (IllegalArgumentException ex) {
            LOG.warn("Exportacion FEMPROBIEN rechazada por solicitud invalida: {}", ex.getMessage());
            return error(HttpStatus.BAD_REQUEST, "Las solicitudes indicadas no son validas.");
        } catch (Exception ex) {
            // El ID de solicitud es el unico detalle funcional devuelto al navegador.
            // Los errores SQL, stacktrace y datos de nomina permanecen en los logs.
            LOG.error("Error generando Excel FEMPROBIEN (individual/masivo)", ex);
            String mensaje = "No fue posible generar el Excel. Revisa los registros de BridgeApi.";
            String detalle = ex.getMessage() == null ? "" : ex.getMessage();
            java.util.regex.Matcher identificador = java.util.regex.Pattern
                    .compile("(?i)solicitud\\s*#\\s*(\\d+)")
                    .matcher(detalle);
            if (identificador.find()) {
                mensaje = "No se pudo procesar el estudio de la solicitud #" +
                        identificador.group(1) +
                        ". Revisa su informacion y el log de BridgeApi; el lote no se exporto.";
            }
            return error(HttpStatus.UNPROCESSABLE_ENTITY, mensaje);
        }
    }

    private ResponseEntity<byte[]> respuestaExcel(byte[] bytes, String nombre,
                                                  boolean descargar) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(EXCEL);
        headers.setContentLength(bytes.length);
        headers.setCacheControl("no-store, no-cache, must-revalidate");
        if (descargar) {
            headers.add(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + nombre + "\"");
        }
        return new ResponseEntity<byte[]>(bytes, headers, HttpStatus.OK);
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus estado, String mensaje) {
        Map<String, Object> respuesta = new LinkedHashMap<String, Object>();
        respuesta.put("status", estado.value());
        respuesta.put("mensaje", mensaje);
        return ResponseEntity.status(estado).body(respuesta);
    }
}
