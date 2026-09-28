package com.IGB.BridgeApi.controller;

import com.IGB.BridgeApi.dto.CreditDecisionDTO;
import com.IGB.BridgeApi.service.FemprobienCreditService;
import com.IGB.BridgeApi.service.FemprobienCreditPdfService;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/femprobien/creditos")
@CrossOrigin(origins = "*")
public class FemprobienCreditController {

    private final FemprobienCreditService creditService;
    private final FemprobienCreditPdfService creditPdfService;

    public FemprobienCreditController(
            FemprobienCreditService creditService,
            FemprobienCreditPdfService creditPdfService) {

        this.creditService = creditService;
        this.creditPdfService = creditPdfService;
    }

    /* =========================================================
       CREAR SOLICITUD

       POST
       /femprobien/creditos/asociados/{codAsp}/solicitudes
       ========================================================= */
    @PostMapping("/asociados/{codAsp}/solicitudes")
    public ResponseEntity<?> crearSolicitud(
            @PathVariable String codAsp,
            @RequestBody Map<String, Object> datos,
            @RequestHeader(value = "X-Employee", required = false)
            String usuarioHeader) {

        try {

            String usuario = usuarioHeader;

            if ((usuario == null || usuario.trim().isEmpty())
                    && datos.get("usuario") != null) {

                usuario = datos.get("usuario").toString();
            }

            datos.remove("usuario");

            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(
                            creditService.crearSolicitud(
                                    codAsp,
                                    datos,
                                    usuario
                            )
                    );

        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return internalError(
                    "Error creando solicitud de crédito.",
                    e
            );
        }
    }


    @GetMapping("/solicitudes")
    public ResponseEntity<?> listarSolicitudes(
            @RequestParam(value = "estado", required = false)
            String estado) {

        try {
            return ResponseEntity.ok(
                    creditService.listarSolicitudes(estado)
            );
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return internalError(
                    "Error consultando solicitudes de crédito.",
                    e
            );
        }
    }


    @GetMapping("/asociados/{codAsp}/solicitudes")
    public ResponseEntity<?> listarSolicitudesAsociado(
            @PathVariable String codAsp) {

        try {
            return ResponseEntity.ok(
                    creditService.listarSolicitudesAsociado(codAsp)
            );
        } catch (Exception e) {
            return internalError(
                    "Error consultando solicitudes del asociado.",
                    e
            );
        }
    }


    @GetMapping("/solicitudes/{numeroSolicitud}")
    public ResponseEntity<?> consultarSolicitud(
            @PathVariable Integer numeroSolicitud) {

        try {
            return ResponseEntity.ok(
                    creditService.consultarSolicitud(numeroSolicitud)
            );
        } catch (IllegalArgumentException e) {
            return notFound(e.getMessage());
        } catch (Exception e) {
            return internalError(
                    "Error consultando solicitud de crédito.",
                    e
            );
        }
    }


    @PatchMapping("/solicitudes/{numeroSolicitud}/estado")
    public ResponseEntity<?> cambiarEstado(
            @PathVariable Integer numeroSolicitud,
            @RequestBody CreditDecisionDTO decision) {

        try {
            return ResponseEntity.ok(
                    creditService.cambiarEstado(
                            numeroSolicitud,
                            decision
                    )
            );
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return internalError(
                    "Error actualizando estado del crédito.",
                    e
            );
        }
    }


    @GetMapping("/solicitudes/{numeroSolicitud}/historial")
    public ResponseEntity<?> consultarHistorial(
            @PathVariable Integer numeroSolicitud) {

        try {
            return ResponseEntity.ok(
                    creditService.consultarHistorial(numeroSolicitud)
            );
        } catch (IllegalArgumentException e) {
            return notFound(e.getMessage());
        } catch (Exception e) {
            return internalError(
                    "Error consultando historial del crédito.",
                    e
            );
        }
    }

    /* =========================================================
       CARGAR FIRMA Y HUELLA

       POST multipart/form-data
       /femprobien/creditos/solicitudes/{numeroSolicitud}/archivos
       ========================================================= */
    @PostMapping(
            value = "/solicitudes/{numeroSolicitud}/archivos",
            consumes = "multipart/form-data"
    )
    public ResponseEntity<?> cargarFirmaHuella(
            @PathVariable Integer numeroSolicitud,

            @RequestParam(
                    value = "firmaSolicitante",
                    required = false
            )
            MultipartFile firmaSolicitante,

            @RequestParam(
                    value = "huellaSolicitante",
                    required = false
            )
            MultipartFile huellaSolicitante,

            @RequestParam(
                    value = "firmaDeudor1",
                    required = false
            )
            MultipartFile firmaDeudor1,

            @RequestParam(
                    value = "huellaDeudor1",
                    required = false
            )
            MultipartFile huellaDeudor1,

            /*
             * Compatibilidad histórica:
             * estos parámetros permanecen opcionales para solicitudes
             * antiguas creadas cuando se manejaban dos deudores.
             * Las solicitudes nuevas utilizan únicamente deudor1.
             */
            @RequestParam(
                    value = "firmaDeudor2",
                    required = false
            )
            MultipartFile firmaDeudor2,

            @RequestParam(
                    value = "huellaDeudor2",
                    required = false
            )
            MultipartFile huellaDeudor2,

            @RequestParam(
                    value = "usuario",
                    required = false
            )
            String usuarioParam,

            @RequestHeader(
                    value = "X-Employee",
                    required = false
            )
            String usuarioHeader) {

        try {

            String usuario = usuarioHeader;

            if (usuario == null || usuario.trim().isEmpty()) {
                usuario = usuarioParam;
            }


            logArchivosBiometricosRecibidos(
                    numeroSolicitud,
                    firmaSolicitante,
                    huellaSolicitante,
                    firmaDeudor1,
                    huellaDeudor1,
                    firmaDeudor2,
                    huellaDeudor2,
                    usuario
            );


            return ResponseEntity.ok(
                    creditService.guardarArchivosCredito(
                            numeroSolicitud,
                            firmaSolicitante,
                            huellaSolicitante,
                            firmaDeudor1,
                            huellaDeudor1,
                            firmaDeudor2,
                            huellaDeudor2,
                            usuario
                    )
            );

        } catch (IllegalArgumentException e) {


            logErrorBiometria(
                    numeroSolicitud,
                    "VALIDACION",
                    e
            );

            return badRequestBiometria(
                    numeroSolicitud,
                    e.getMessage()
            );

        } catch (IllegalStateException e) {

            logErrorBiometria(
                    numeroSolicitud,
                    "CONFIGURACION",
                    e
            );

            return internalError(
                    "La configuración de firma y huella está incompleta.",
                    e
            );

        } catch (Exception e) {

            logErrorBiometria(
                    numeroSolicitud,
                    "ERROR_NO_CONTROLADO",
                    e
            );

            return internalError(
                    "Error cargando firma y huella de la solicitud.",
                    e
            );
        }
    }


    @GetMapping("/solicitudes/{numeroSolicitud}/archivos/estado")
    public ResponseEntity<?> consultarEstadoArchivos(
            @PathVariable Integer numeroSolicitud) {

        try {
            return ResponseEntity.ok(
                    creditService.consultarEstadoArchivos(
                            numeroSolicitud
                    )
            );
        } catch (IllegalArgumentException e) {
            return notFound(e.getMessage());
        } catch (Exception e) {
            return internalError(
                    "Error consultando el estado de firma y huella.",
                    e
            );
        }
    }


    /* =========================================================
       GENERAR FORMATO PDF DE SOLICITUD DE CRÉDITO

       GET
       /femprobien/creditos/solicitudes/{numeroSolicitud}/formato

       IMPORTANTE:
       Este método se AGREGA al controller existente.
       NO se deben eliminar los endpoints de listar, consultar,
       historial, estado, archivos, etc.
       ========================================================= */
    @GetMapping(
            value = "/solicitudes/{numeroSolicitud}/formato"
    )
    public ResponseEntity<byte[]> generarFormatoCredito(
            @PathVariable Integer numeroSolicitud) {

        try {

            byte[] pdf =
                    creditPdfService.generarFormato(
                            numeroSolicitud
                    );

            return ResponseEntity
                    .ok()
                    .contentType(
                            MediaType.APPLICATION_PDF
                    )
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=\"Solicitud_Credito_" +
                                    numeroSolicitud +
                                    ".pdf\""
                    )
                    .contentLength(
                            pdf.length
                    )
                    .body(
                            pdf
                    );

        } catch (IllegalArgumentException e) {

            e.printStackTrace();

            return respuestaErrorPdf(
                    HttpStatus.BAD_REQUEST,
                    e.getMessage()
            );

        } catch (Exception e) {

            e.printStackTrace();

            return respuestaErrorPdf(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Error generando el formato de solicitud de crédito: " +
                            (
                                    e.getMessage() == null
                                            ? e.getClass().getName()
                                            : e.getMessage()
                            )
            );
        }
    }


    private ResponseEntity<byte[]> respuestaErrorPdf(
            HttpStatus status,
            String mensaje
    ) {

        String texto =
                mensaje == null ||
                        mensaje.trim().isEmpty()
                        ? "Error generando el formato de solicitud de crédito."
                        : mensaje.trim();

        byte[] contenido =
                texto.getBytes(
                        StandardCharsets.UTF_8
                );

        return ResponseEntity
                .status(
                        status
                )
                .contentType(
                        MediaType.TEXT_PLAIN
                )
                .contentLength(
                        contenido.length
                )
                .body(
                        contenido
                );
    }


    private void logArchivosBiometricosRecibidos(
            Integer numeroSolicitud,
            MultipartFile firmaSolicitante,
            MultipartFile huellaSolicitante,
            MultipartFile firmaDeudor1,
            MultipartFile huellaDeudor1,
            MultipartFile firmaDeudor2,
            MultipartFile huellaDeudor2,
            String usuario) {

        System.out.println(
                "============================================================"
        );

        System.out.println(
                "FEMPROBIEN - CARGA BIOMÉTRICA SOLICITUD #" +
                        numeroSolicitud
        );

        System.out.println(
                "Usuario: " +
                        (
                                usuario == null ||
                                        usuario.trim().isEmpty()
                                        ? "(sin usuario)"
                                        : usuario
                        )
        );

        imprimirInfoArchivo(
                "firmaSolicitante",
                firmaSolicitante
        );

        imprimirInfoArchivo(
                "huellaSolicitante",
                huellaSolicitante
        );

        imprimirInfoArchivo(
                "firmaDeudor1",
                firmaDeudor1
        );

        imprimirInfoArchivo(
                "huellaDeudor1",
                huellaDeudor1
        );

        imprimirInfoArchivo(
                "firmaDeudor2",
                firmaDeudor2
        );

        imprimirInfoArchivo(
                "huellaDeudor2",
                huellaDeudor2
        );

        System.out.println(
                "============================================================"
        );
    }


    private void imprimirInfoArchivo(
            String campo,
            MultipartFile archivo) {

        if (
                archivo == null
        ) {

            System.out.println(
                    campo +
                            ": NO RECIBIDO"
            );

            return;
        }

        System.out.println(
                campo +
                        ": nombre=" +
                        archivo.getOriginalFilename() +
                        ", contentType=" +
                        archivo.getContentType() +
                        ", size=" +
                        archivo.getSize() +
                        " bytes" +
                        ", empty=" +
                        archivo.isEmpty()
        );
    }


    private void logErrorBiometria(
            Integer numeroSolicitud,
            String tipoError,
            Exception e) {

        System.err.println(
                "============================================================"
        );

        System.err.println(
                "FEMPROBIEN - ERROR BIOMETRÍA"
        );

        System.err.println(
                "Solicitud: #" +
                        numeroSolicitud
        );

        System.err.println(
                "Tipo: " +
                        tipoError
        );

        System.err.println(
                "Mensaje: " +
                        (
                                e.getMessage() == null
                                        ? "(sin mensaje)"
                                        : e.getMessage()
                        )
        );


        e.printStackTrace();

        System.err.println(
                "============================================================"
        );
    }


    private ResponseEntity<?> badRequestBiometria(
            Integer numeroSolicitud,
            String message) {

        Map<String, Object> response =
                new HashMap<>();

        response.put(
                "status",
                400
        );

        response.put(
                "tipo",
                "VALIDACION_BIOMETRIA"
        );

        response.put(
                "numero_solicitud",
                numeroSolicitud
        );

        response.put(
                "message",
                message == null ||
                        message.trim().isEmpty()
                        ? "La firma o huella no superó la validación."
                        : message
        );

        return ResponseEntity
                .status(
                        HttpStatus.BAD_REQUEST
                )
                .body(
                        response
                );
    }


    private ResponseEntity<?> badRequest(
            String message) {

        Map<String, Object> response =
                new HashMap<>();

        response.put("status", 400);
        response.put("message", message);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    private ResponseEntity<?> notFound(
            String message) {

        Map<String, Object> response =
                new HashMap<>();

        response.put("status", 404);
        response.put("message", message);

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(response);
    }

    private ResponseEntity<?> internalError(
            String message,
            Exception e) {

        Map<String, Object> response =
                new HashMap<>();

        response.put("status", 500);
        response.put("message", message);
        response.put("error", e.getMessage());

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(response);
    }
}
