package com.IGB.BridgeApi.controller;

import com.IGB.BridgeApi.service.FemprobienCreditImportService;
import com.IGB.BridgeApi.service.FemprobienImportService;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/femprobien/importacion")
public class FemprobienImportController {


    private static final Set<String> USUARIOS_IMPORTACION =
            new HashSet<String>(
                    Arrays.asList(
                            "mmurillo",
                            "jguisao",
                            "jvelasquez",
                            "rzapata",
                            "rmoncada",
                            "ymoreno"
                    )
            );


    private final FemprobienImportService
            femprobienImportService;

    private final FemprobienCreditImportService
            femprobienCreditImportService;


    public FemprobienImportController(
            FemprobienImportService femprobienImportService,
            FemprobienCreditImportService femprobienCreditImportService) {

        this.femprobienImportService =
                femprobienImportService;

        this.femprobienCreditImportService =
                femprobienCreditImportService;
    }


    /* =========================================================
       VALIDAR ARCHIVO DE ASOCIADOS / APORTES

       POST /femprobien/importacion/validar

       NO modifica la base de datos.
       ========================================================= */
    @PostMapping(
            value = "/validar",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<?> validarArchivo(
            @RequestParam("archivo")
            MultipartFile archivo,

            @RequestParam(
                    value = "usuario",
                    required = false
            )
            String usuario) {

        try {

            validarUsuarioImportacion(
                    usuario
            );

            Map<String, Object> resultado =
                    femprobienImportService
                            .validarArchivo(
                                    archivo
                            );

            return ResponseEntity.ok(
                    resultado
            );

        } catch (SecurityException e) {

            return forbidden(
                    e.getMessage()
            );

        } catch (IllegalArgumentException e) {

            return badRequest(
                    e.getMessage()
            );

        } catch (Exception e) {

            return internalError(
                    "Ocurrió un error al validar el archivo de importación.",
                    e
            );
        }
    }


    /* =========================================================
       PROCESAR ASOCIADOS / APORTES

       POST /femprobien/importacion/procesar

       Sí modifica:
       - tblAsociado cuando el documento es nuevo.
       - tblAportes mediante INSERT o UPDATE.
       ========================================================= */
    @PostMapping(
            value = "/procesar",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<?> procesarArchivo(
            @RequestParam("archivo")
            MultipartFile archivo,

            @RequestParam(
                    value = "usuario",
                    required = false
            )
            String usuario) {

        try {

            String usuarioValidado =
                    validarUsuarioImportacion(
                            usuario
                    );

            Map<String, Object> resultado =
                    femprobienImportService
                            .procesarArchivo(
                                    archivo,
                                    usuarioValidado
                            );

            return ResponseEntity.ok(
                    resultado
            );

        } catch (SecurityException e) {

            return forbidden(
                    e.getMessage()
            );

        } catch (IllegalArgumentException e) {

            return badRequest(
                    e.getMessage()
            );

        } catch (Exception e) {

            return internalError(
                    "Ocurrió un error procesando el archivo de importación.",
                    e
            );
        }
    }


    /* =========================================================
       VALIDAR ARCHIVO DE CRÉDITOS

       POST /femprobien/importacion/creditos/validar

       - Detecta automáticamente el mes anterior.
       - NO modifica tblAporteCredito.
       - NO modifica la estructura SQL.
       ========================================================= */
    @PostMapping(
            value = "/creditos/validar",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<?> validarArchivoCreditos(
            @RequestParam("archivo")
            MultipartFile archivo,

            @RequestParam(
                    value = "usuario",
                    required = false
            )
            String usuario) {

        try {

            validarUsuarioImportacion(
                    usuario
            );

            Map<String, Object> resultado =
                    femprobienCreditImportService
                            .validarArchivo(
                                    archivo
                            );

            return ResponseEntity.ok(
                    resultado
            );

        } catch (SecurityException e) {

            return forbidden(
                    e.getMessage()
            );

        } catch (IllegalArgumentException e) {

            return badRequest(
                    e.getMessage()
            );

        } catch (Exception e) {

            return internalError(
                    "Ocurrió un error al validar el archivo de créditos.",
                    e
            );
        }
    }


    /* =========================================================
       PROCESAR ARCHIVO DE CRÉDITOS

       POST /femprobien/importacion/creditos/procesar

       Sí modifica tblAporteCredito:
       - INSERT para créditos nuevos.
       - UPDATE para créditos existentes.

       No agrega ninguna columna nueva a tblAporteCredito.
       ========================================================= */
    @PostMapping(
            value = "/creditos/procesar",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<?> procesarArchivoCreditos(
            @RequestParam("archivo")
            MultipartFile archivo,

            @RequestParam(
                    value = "usuario",
                    required = false
            )
            String usuario) {

        try {

            String usuarioValidado =
                    validarUsuarioImportacion(
                            usuario
                    );

            Map<String, Object> resultado =
                    femprobienCreditImportService
                            .procesarArchivo(
                                    archivo,
                                    usuarioValidado
                            );

            return ResponseEntity.ok(
                    resultado
            );

        } catch (SecurityException e) {

            return forbidden(
                    e.getMessage()
            );

        } catch (IllegalArgumentException e) {

            return badRequest(
                    e.getMessage()
            );

        } catch (Exception e) {

            return internalError(
                    "Ocurrió un error procesando el archivo de créditos.",
                    e
            );
        }
    }



    /* =========================================================
       SINCRONIZAR DATOS FALTANTES DE ASOCIADOS

       POST /femprobien/importacion/sincronizar-asociados

       Completa únicamente campos NULL o vacíos desde NOVAWEB.
       NO reemplaza información existente.
       ========================================================= */
    @PostMapping(
            value = "/sincronizar-asociados"
    )
    public ResponseEntity<?> sincronizarAsociados(
            @RequestParam(
                    value = "usuario",
                    required = false
            )
            String usuario) {

        try {

            String usuarioValidado =
                    validarUsuarioImportacion(
                            usuario
                    );


            Map<String, Object> resultado =
                    femprobienImportService
                            .sincronizarDatosFaltantesAsociados(
                                    usuarioValidado
                            );


            return ResponseEntity.ok(
                    resultado
            );


        } catch (SecurityException e) {

            return forbidden(
                    e.getMessage()
            );


        } catch (IllegalArgumentException e) {

            return badRequest(
                    e.getMessage()
            );


        } catch (Exception e) {

            return internalError(
                    "Ocurrió un error sincronizando los datos faltantes de los asociados.",
                    e
            );
        }
    }


    private String validarUsuarioImportacion(
            String usuario) {

        if (
                usuario == null ||
                        usuario.trim().isEmpty()
        ) {

            throw new SecurityException(
                    "No fue posible identificar el usuario."
            );
        }


        String usuarioNormalizado =
                usuario
                        .trim()
                        .toLowerCase(
                                Locale.ROOT
                        );


        if (
                !USUARIOS_IMPORTACION.contains(
                        usuarioNormalizado
                )
        ) {

            throw new SecurityException(
                    "El usuario " +
                            usuarioNormalizado +
                            " no tiene permisos para acceder " +
                            "al módulo de importación."
            );
        }


        return usuarioNormalizado;
    }


    private ResponseEntity<?> badRequest(
            String message) {

        Map<String, Object> response =
                new HashMap<String, Object>();

        response.put(
                "status",
                400
        );

        response.put(
                "message",
                message
        );

        return ResponseEntity
                .status(
                        HttpStatus.BAD_REQUEST
                )
                .body(
                        response
                );
    }


    private ResponseEntity<?> forbidden(
            String message) {

        Map<String, Object> response =
                new HashMap<String, Object>();

        response.put(
                "status",
                403
        );

        response.put(
                "message",
                message
        );

        return ResponseEntity
                .status(
                        HttpStatus.FORBIDDEN
                )
                .body(
                        response
                );
    }


    private ResponseEntity<?> internalError(
            String message,
            Exception e) {

        Map<String, Object> response =
                new HashMap<String, Object>();

        response.put(
                "status",
                500
        );

        response.put(
                "message",
                message
        );

        if (
                e != null &&
                        e.getMessage() != null
        ) {

            response.put(
                    "error",
                    e.getMessage()
            );
        }

        return ResponseEntity
                .status(
                        HttpStatus.INTERNAL_SERVER_ERROR
                )
                .body(
                        response
                );
    }
}
