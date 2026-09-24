package com.IGB.BridgeApi.controller;

import com.IGB.BridgeApi.service.FemprobienReportServices;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/femprobien")
public class FemprobienController {

    private final JdbcTemplate sqlServerJdbcTemplate;
    private final FemprobienReportServices femprobienReportServices;

    public FemprobienController(
            @Qualifier("sqlServerJdbcTemplate")
            JdbcTemplate sqlServerJdbcTemplate,
            FemprobienReportServices femprobienReportServices) {

        this.sqlServerJdbcTemplate = sqlServerJdbcTemplate;
        this.femprobienReportServices = femprobienReportServices;
    }


    /* =========================================================
       PROBAR CONEXIÓN SQL SERVER + FEMPROBIEN

       GET /femprobien/test-db
       ========================================================= */

    @GetMapping("/test-db")
    public ResponseEntity<?> testDatabase() {

        try {

            String sql =
                    "SELECT DB_NAME(database_id) " +
                            "FROM sys.databases " +
                            "WHERE name = 'FEMPROBIEN'";

            String database =
                    sqlServerJdbcTemplate.queryForObject(
                            sql,
                            String.class
                    );

            Map<String, Object> response =
                    new HashMap<>();

            response.put("status", 200);
            response.put(
                    "message",
                    "Conexión SQL Server correcta"
            );
            response.put("database", database);

            return ResponseEntity.ok(response);

        } catch (Exception e) {

            return internalError(
                    "Error conectando con SQL Server.",
                    e
            );
        }
    }



    /* =========================================================
       VALIDAR ASOCIADO PARA ACTUALIZACIÓN DE DATOS

       POST /femprobien/asociados/validar-actualizacion
       ========================================================= */

    @PostMapping("/asociados/validar-actualizacion")
    public ResponseEntity<?> validarAsociadoActualizacion(
            @RequestBody Map<String, Object> datos) {

        try {

            String codAsp =
                    texto(
                            datos.get(
                                    "cod_asp"
                            )
                    );

            String fechaNacimiento =
                    texto(
                            datos.get(
                                    "fec_nac"
                            )
                    );


            if (codAsp.isEmpty()
                    || fechaNacimiento.isEmpty()) {

                return badRequest(
                        "El número de documento y la fecha de nacimiento son obligatorios."
                );
            }


            String sql =
                    "SELECT * " +
                            "FROM FEMPROBIEN.dbo.tblAsociado " +
                            "WHERE LTRIM(RTRIM(CAST(cod_asp AS VARCHAR(100)))) = ? " +
                            "AND CONVERT(date, fec_nac) = CONVERT(date, ?)";


            List<Map<String, Object>> resultado =
                    sqlServerJdbcTemplate.queryForList(
                            sql,
                            codAsp,
                            fechaNacimiento
                    );


            if (resultado.isEmpty()) {

                return notFound(
                        "No se encontró un asociado con los datos ingresados."
                );
            }


            return ResponseEntity.ok(
                    resultado.get(0)
            );

        } catch (Exception e) {

            return internalError(
                    "Error validando los datos del asociado.",
                    e
            );
        }
    }


    /* =========================================================
       CONSULTAR ASOCIADOS SIN APORTES

       GET /femprobien/asociados/sin-aportes
       ========================================================= */

    @GetMapping("/asociados/sin-aportes")
    public ResponseEntity<?> consultarAsociadosSinAportes() {

        try {

            String sql =
                    "SELECT " +
                            "a.id_aso, " +
                            "a.cod_asp, " +
                            "a.nom_aso, " +
                            "a.nom_emp, " +
                            "a.cel, " +
                            "a.email_per, " +
                            "a.activo, " +
                            "a.fecha_retiro " +

                            "FROM FEMPROBIEN.dbo.tblAsociado a " +

                            "WHERE ISNULL(a.activo, 1) = 1 " +

                            "AND NOT EXISTS (" +

                            "    SELECT 1 " +
                            "    FROM FEMPROBIEN.dbo.tblAportes ap " +
                            "    WHERE ap.id_aso = a.id_aso " +
                            "       OR LTRIM(RTRIM(CAST(ap.cod_asp AS VARCHAR(100)))) = " +
                            "          LTRIM(RTRIM(CAST(a.cod_asp AS VARCHAR(100)))) " +

                            ") " +

                            "ORDER BY a.nom_aso";

            List<Map<String, Object>> asociados =
                    sqlServerJdbcTemplate.queryForList(
                            sql
                    );

            Map<String, Object> response =
                    new HashMap<>();

            response.put(
                    "status",
                    200
            );

            response.put(
                    "cantidad",
                    asociados.size()
            );

            response.put(
                    "asociados",
                    asociados
            );

            return ResponseEntity.ok(
                    response
            );

        } catch (Exception e) {

            return internalError(
                    "Error consultando asociados sin aportes.",
                    e
            );
        }
    }


    /* =========================================================
       CONSULTAR ASOCIADO POR cod_asp

       GET /femprobien/asociados/1052413815
       ========================================================= */

    @GetMapping("/asociados/{codAsp}")
    public ResponseEntity<?> consultarAsociado(
            @PathVariable String codAsp) {

        try {

            String sql =
                    "SELECT * " +
                            "FROM FEMPROBIEN.dbo.tblAsociado " +
                            "WHERE cod_asp = ?";

            List<Map<String, Object>> resultado =
                    sqlServerJdbcTemplate.queryForList(
                            sql,
                            codAsp
                    );

            if (resultado.isEmpty()) {

                return notFound(
                        "No se encontró el asociado con código: "
                                + codAsp
                );
            }

            return ResponseEntity.ok(
                    resultado.get(0)
            );

        } catch (Exception e) {

            return internalError(
                    "Error consultando asociado.",
                    e
            );
        }
    }


    /* =========================================================
       CONSULTAR APORTES

       GET /femprobien/asociados/1052413815/aportes
       ========================================================= */

    @GetMapping("/asociados/{codAsp}/aportes")
    public ResponseEntity<?> consultarAportes(
            @PathVariable String codAsp) {

        try {

            if (!existeAsociado(codAsp)) {

                return notFound(
                        "No se encontró el asociado con código: "
                                + codAsp
                );
            }

            String sql =
                    "SELECT " +

                            "ISNULL([SaldoAporteSocialConsolidado_3AniosNuevoCiclo], 0) " +
                            "AS [SaldoAporteSocialConsolidado_3AniosNuevoCiclo], " +

                            "ISNULL([SaldoAhorroPermanenteConsolidado_3AniosNuevoCiclo], 0) " +
                            "AS [SaldoAhorroPermanenteConsolidado_3AniosNuevoCiclo], " +

                            "ISNULL([SaldoRendimientoAhorroPermanenteConsolidado_3AniosNuevoCiclo], 0) " +
                            "AS [SaldoRendimientoAhorroPermanenteConsolidado_3AniosNuevoCiclo], " +

                            "ISNULL([SaldosAporteEmpresa], 0) " +
                            "AS [SaldosAporteEmpresa], " +

                            "ISNULL([SaldosRendimientosAporteEmpresa], 0) " +
                            "AS [SaldosRendimientosAporteEmpresa], " +

                            "ISNULL([SaldoAhorroNavideno], 0) " +
                            "AS [SaldoAhorroNavideno], " +

                            "ISNULL([TotalCrucesRetiros_2025_2026], 0) " +
                            "AS [TotalCrucesRetiros_2025_2026] " +

                            "FROM FEMPROBIEN.dbo.tblAportes " +
                            "WHERE cod_asp = ?";

            List<Map<String, Object>> resultado =
                    sqlServerJdbcTemplate.queryForList(
                            sql,
                            codAsp
                    );

            if (resultado.isEmpty()) {

                return notFound(
                        "El asociado no tiene información de aportes."
                );
            }

            return ResponseEntity.ok(
                    resultado.get(0)
            );

        } catch (Exception e) {

            return internalError(
                    "Error consultando aportes.",
                    e
            );
        }
    }


    /* =========================================================
       CONSULTAR CRÉDITOS

       GET /femprobien/asociados/1052413815/creditos
       ========================================================= */

    @GetMapping("/asociados/{codAsp}/creditos")
    public ResponseEntity<?> consultarCreditos(
            @PathVariable String codAsp) {

        try {

            if (!existeAsociado(codAsp)) {

                return notFound(
                        "No se encontró el asociado con código: "
                                + codAsp
                );
            }

            String sql =
                    "SELECT * " +
                            "FROM FEMPROBIEN.dbo.tblAporteCredito " +
                            "WHERE cod_asp = ? " +
                            "ORDER BY fecha_desem DESC";

            List<Map<String, Object>> resultado =
                    sqlServerJdbcTemplate.queryForList(
                            sql,
                            codAsp
                    );

            return ResponseEntity.ok(resultado);

        } catch (Exception e) {

            return internalError(
                    "Error consultando créditos.",
                    e
            );
        }
    }


    /* =========================================================
       CONSULTAR SOLICITUDES DE CRÉDITO

       GET /femprobien/asociados/1052413815/solicitudes
       ========================================================= */

    @GetMapping("/asociados/{codAsp}/solicitudes")
    public ResponseEntity<?> consultarSolicitudes(
            @PathVariable String codAsp) {

        try {

            if (!existeAsociado(codAsp)) {

                return notFound(
                        "No se encontró el asociado con código: "
                                + codAsp
                );
            }

            String sql =
                    "SELECT ac.* " +
                            "FROM FEMPROBIEN.dbo.tblAsociadoCredito ac " +
                            "INNER JOIN FEMPROBIEN.dbo.tblAsociado a " +
                            "ON a.id_aso = ac.id_aso " +
                            "WHERE a.cod_asp = ? " +
                            "ORDER BY ac.fecha_solicitud DESC";

            List<Map<String, Object>> resultado =
                    sqlServerJdbcTemplate.queryForList(
                            sql,
                            codAsp
                    );

            return ResponseEntity.ok(resultado);

        } catch (Exception e) {

            return internalError(
                    "Error consultando solicitudes de crédito.",
                    e
            );
        }
    }


    /* =========================================================
       CONSULTAR HISTORIAL DE ESTADOS

       GET /femprobien/asociados/1052413815/estados
       ========================================================= */

    @GetMapping("/asociados/{codAsp}/estados")
    public ResponseEntity<?> consultarEstados(
            @PathVariable String codAsp) {

        try {

            if (!existeAsociado(codAsp)) {

                return notFound(
                        "No se encontró el asociado con código: "
                                + codAsp
                );
            }

            String sql =
                    "SELECT " +
                            "ec.id_estado, " +
                            "ac.numero_solicitud, " +
                            "ec.estado, " +
                            "ec.fecha_estado, " +
                            "ec.comentario, " +
                            "ec.usuario, " +
                            "ec.fecha_creacion, " +
                            "ec.activo, " +
                            "ec.fecha_retiro " +

                            "FROM FEMPROBIEN.dbo.tblEstadoCredito ec " +

                            "INNER JOIN FEMPROBIEN.dbo.tblAsociadoCredito ac " +
                            "ON ac.id = ec.id_solicitud " +

                            "INNER JOIN FEMPROBIEN.dbo.tblAsociado a " +
                            "ON a.id_aso = ac.id_aso " +

                            "WHERE a.cod_asp = ? " +
                            "ORDER BY ec.fecha_estado DESC";

            List<Map<String, Object>> resultado =
                    sqlServerJdbcTemplate.queryForList(
                            sql,
                            codAsp
                    );

            return ResponseEntity.ok(resultado);

        } catch (Exception e) {

            return internalError(
                    "Error consultando historial de estados.",
                    e
            );
        }
    }


    /* =========================================================
       RESUMEN COMPLETO DEL ASOCIADO

       GET /femprobien/asociados/1052413815/resumen
       ========================================================= */

    @GetMapping("/asociados/{codAsp}/resumen")
    public ResponseEntity<?> consultarResumen(
            @PathVariable String codAsp) {

        try {


            String sqlAsociado =
                    "SELECT * " +
                            "FROM FEMPROBIEN.dbo.tblAsociado " +
                            "WHERE cod_asp = ?";

            List<Map<String, Object>> asociado =
                    sqlServerJdbcTemplate.queryForList(
                            sqlAsociado,
                            codAsp
                    );

            if (asociado.isEmpty()) {

                return notFound(
                        "No se encontró el asociado con código: "
                                + codAsp
                );
            }


            String sqlAportes =
                    "SELECT " +

                            "ISNULL([SaldoAporteSocialConsolidado_3AniosNuevoCiclo], 0) " +
                            "AS [SaldoAporteSocialConsolidado_3AniosNuevoCiclo], " +

                            "ISNULL([SaldoAhorroPermanenteConsolidado_3AniosNuevoCiclo], 0) " +
                            "AS [SaldoAhorroPermanenteConsolidado_3AniosNuevoCiclo], " +

                            "ISNULL([SaldoRendimientoAhorroPermanenteConsolidado_3AniosNuevoCiclo], 0) " +
                            "AS [SaldoRendimientoAhorroPermanenteConsolidado_3AniosNuevoCiclo], " +

                            "ISNULL([SaldosAporteEmpresa], 0) " +
                            "AS [SaldosAporteEmpresa], " +

                            "ISNULL([SaldosRendimientosAporteEmpresa], 0) " +
                            "AS [SaldosRendimientosAporteEmpresa], " +

                            "ISNULL([SaldoAhorroNavideno], 0) " +
                            "AS [SaldoAhorroNavideno], " +

                            "ISNULL([TotalCrucesRetiros_2025_2026], 0) " +
                            "AS [TotalCrucesRetiros_2025_2026] " +

                            "FROM FEMPROBIEN.dbo.tblAportes " +
                            "WHERE cod_asp = ?";

            List<Map<String, Object>> aportes =
                    sqlServerJdbcTemplate.queryForList(
                            sqlAportes,
                            codAsp
                    );


            String sqlCreditos =
                    "SELECT * " +
                            "FROM FEMPROBIEN.dbo.tblAporteCredito " +
                            "WHERE cod_asp = ? " +
                            "ORDER BY fecha_desem DESC";

            List<Map<String, Object>> creditos =
                    sqlServerJdbcTemplate.queryForList(
                            sqlCreditos,
                            codAsp
                    );


            String sqlSolicitudes =
                    "SELECT ac.* " +
                            "FROM FEMPROBIEN.dbo.tblAsociadoCredito ac " +
                            "INNER JOIN FEMPROBIEN.dbo.tblAsociado a " +
                            "ON a.id_aso = ac.id_aso " +
                            "WHERE a.cod_asp = ? " +
                            "ORDER BY ac.fecha_solicitud DESC";

            List<Map<String, Object>> solicitudes =
                    sqlServerJdbcTemplate.queryForList(
                            sqlSolicitudes,
                            codAsp
                    );


            String sqlEstados =
                    "SELECT " +
                            "ec.*, " +
                            "ac.numero_solicitud " +

                            "FROM FEMPROBIEN.dbo.tblEstadoCredito ec " +

                            "INNER JOIN FEMPROBIEN.dbo.tblAsociadoCredito ac " +
                            "ON ac.id = ec.id_solicitud " +

                            "INNER JOIN FEMPROBIEN.dbo.tblAsociado a " +
                            "ON a.id_aso = ac.id_aso " +

                            "WHERE a.cod_asp = ? " +
                            "ORDER BY ec.fecha_estado DESC";

            List<Map<String, Object>> estados =
                    sqlServerJdbcTemplate.queryForList(
                            sqlEstados,
                            codAsp
                    );


            Map<String, Object> response =
                    new HashMap<>();

            response.put(
                    "asociado",
                    asociado.get(0)
            );

            response.put(
                    "aportes",
                    aportes.isEmpty()
                            ? null
                            : aportes.get(0)
            );

            response.put(
                    "creditos",
                    creditos
            );

            response.put(
                    "solicitudes",
                    solicitudes
            );

            response.put(
                    "estados",
                    estados
            );

            response.put(
                    "cantidadCreditos",
                    creditos.size()
            );

            response.put(
                    "cantidadSolicitudes",
                    solicitudes.size()
            );

            return ResponseEntity.ok(response);

        } catch (Exception e) {

            return internalError(
                    "Error consultando resumen del asociado.",
                    e
            );
        }
    }


    /* =========================================================
       GENERAR ESTADO DE CUENTA

       POST /femprobien/asociados/{codAsp}/estado-cuenta
       ========================================================= */

    @PostMapping("/asociados/{codAsp}/estado-cuenta")
    public ResponseEntity<?> generarEstadoCuenta(
            @PathVariable String codAsp) {

        try {

            if (codAsp == null
                    || codAsp.trim().isEmpty()) {

                return badRequest(
                        "El código del asociado es obligatorio."
                );
            }

            codAsp = codAsp.trim();

            if (!existeAsociado(codAsp)) {

                return notFound(
                        "No se encontró el asociado con código: "
                                + codAsp
                );
            }

            Map<String, Object> resultado =
                    femprobienReportServices
                            .generarEstadoCuenta(codAsp);

            resultado.put(
                    "status",
                    200
            );

            return ResponseEntity.ok(resultado);

        } catch (IllegalArgumentException e) {

            return badRequest(
                    e.getMessage()
            );

        } catch (Exception e) {

            return internalError(
                    "Error generando el estado de cuenta.",
                    e
            );
        }
    }


    /* =========================================================
       OBTENER ESTADO DE CUENTA PDF

       GET /femprobien/asociados/{codAsp}/estado-cuenta/pdf
       ========================================================= */

    @GetMapping(
            value = "/asociados/{codAsp}/estado-cuenta/pdf",
            produces = MediaType.APPLICATION_PDF_VALUE
    )
    public ResponseEntity<?> obtenerEstadoCuentaPdf(
            @PathVariable String codAsp) {

        try {

            if (codAsp == null
                    || codAsp.trim().isEmpty()) {

                return badRequest(
                        "El código del asociado es obligatorio."
                );
            }

            codAsp = codAsp.trim();

            if (!existeAsociado(codAsp)) {

                return notFound(
                        "No se encontró el asociado con código: "
                                + codAsp
                );
            }

            File pdf =
                    femprobienReportServices
                            .obtenerEstadoCuentaPdf(
                                    codAsp
                            );

            byte[] contenido =
                    Files.readAllBytes(
                            pdf.toPath()
                    );

            HttpHeaders headers =
                    new HttpHeaders();

            headers.setContentType(
                    MediaType.APPLICATION_PDF
            );

            headers.add(
                    HttpHeaders.CONTENT_DISPOSITION,
                    "inline; filename=\"estado-cuenta-"
                            + codAsp
                            + ".pdf\""
            );

            headers.setContentLength(
                    contenido.length
            );

            return new ResponseEntity<byte[]>(
                    contenido,
                    headers,
                    HttpStatus.OK
            );

        } catch (IllegalArgumentException e) {

            return badRequest(
                    e.getMessage()
            );

        } catch (Exception e) {

            return internalError(
                    "Error consultando el estado de cuenta.",
                    e
            );
        }
    }


    /* =========================================================
       CREAR SOLICITUD DE AFILIACIÓN

       POST /femprobien/solicitudes-afiliacion
       ========================================================= */

    @PostMapping("/solicitudes-afiliacion")
    public ResponseEntity<?> crearSolicitudAfiliacion(
            @RequestBody Map<String, Object> datos) {

        try {

            Object codAspObj =
                    datos.get("cod_asp");

            if (codAspObj == null
                    || codAspObj.toString().trim().isEmpty()) {

                return badRequest(
                        "El número de documento es obligatorio."
                );
            }

            String codAsp =
                    codAspObj
                            .toString()
                            .trim();

            datos.put(
                    "cod_asp",
                    codAsp
            );

            String tipoSolicitud =
                    texto(
                            datos.get(
                                    "tipo_solicitud"
                            )
                    )
                            .toUpperCase();


            if (!"AFILIACION".equals(
                    tipoSolicitud
            )
                    && !"ACTUALIZACION_DATOS".equals(
                    tipoSolicitud
            )) {

                return badRequest(
                        "Tipo de solicitud no permitido."
                );
            }


            boolean asociadoExiste =
                    existeAsociado(
                            codAsp
                    );


            if ("AFILIACION".equals(
                    tipoSolicitud
            )
                    && asociadoExiste) {

                return conflict(
                        "El documento "
                                + codAsp
                                + " ya pertenece a un asociado."
                );
            }


            if ("ACTUALIZACION_DATOS".equals(
                    tipoSolicitud
            )
                    && !asociadoExiste) {

                return notFound(
                        "No existe un asociado registrado con documento: "
                                + codAsp
                );
            }


            List<Map<String, Object>> cambiosActualizacion =
                    new ArrayList<>();


            if ("ACTUALIZACION_DATOS".equals(
                    tipoSolicitud
            )) {

                cambiosActualizacion =
                        detectarCambiosSolicitudActualizacion(
                                datos,
                                codAsp
                        );


                if (cambiosActualizacion.isEmpty()) {

                    return badRequest(
                            "No se detectaron cambios en la información del asociado."
                    );
                }
            }


            String sqlExiste =
                    "SELECT COUNT(*) " +
                            "FROM FEMPROBIEN.dbo.tblSolicitudAfiliacion " +
                            "WHERE cod_asp = ? " +
                            "AND estado = 'PENDIENTE'";

            Integer cantidad =
                    sqlServerJdbcTemplate.queryForObject(
                            sqlExiste,
                            new Object[]{
                                    codAsp
                            },
                            Integer.class
                    );

            if (cantidad != null
                    && cantidad > 0) {

                return conflict(
                        "Ya existe una solicitud pendiente para este documento."
                );
            }


            boolean beneficiario1TieneDatos =
                    !texto(datos.get("tipo_doc_ben1")).isEmpty()
                            || !texto(datos.get("ben_doc1")).isEmpty()
                            || !texto(datos.get("ben_nom1")).isEmpty()
                            || !texto(datos.get("parentesco1")).isEmpty();

            boolean beneficiario1Completo =
                    !texto(datos.get("tipo_doc_ben1")).isEmpty()
                            && !texto(datos.get("ben_doc1")).isEmpty()
                            && !texto(datos.get("ben_nom1")).isEmpty()
                            && !texto(datos.get("parentesco1")).isEmpty();


            boolean beneficiario2TieneDatos =
                    !texto(datos.get("tipo_doc_ben2")).isEmpty()
                            || !texto(datos.get("ben_doc2")).isEmpty()
                            || !texto(datos.get("ben_nom2")).isEmpty()
                            || !texto(datos.get("parentesco2")).isEmpty();

            boolean beneficiario2Completo =
                    !texto(datos.get("tipo_doc_ben2")).isEmpty()
                            && !texto(datos.get("ben_doc2")).isEmpty()
                            && !texto(datos.get("ben_nom2")).isEmpty()
                            && !texto(datos.get("parentesco2")).isEmpty();


            boolean beneficiario3TieneDatos =
                    !texto(datos.get("tipo_doc_ben3")).isEmpty()
                            || !texto(datos.get("ben_doc3")).isEmpty()
                            || !texto(datos.get("ben_nom3")).isEmpty()
                            || !texto(datos.get("parentesco3")).isEmpty();

            boolean beneficiario3Completo =
                    !texto(datos.get("tipo_doc_ben3")).isEmpty()
                            && !texto(datos.get("ben_doc3")).isEmpty()
                            && !texto(datos.get("ben_nom3")).isEmpty()
                            && !texto(datos.get("parentesco3")).isEmpty();


            if (!beneficiario1Completo
                    && !beneficiario2Completo
                    && !beneficiario3Completo) {

                return badRequest(
                        "Debes diligenciar completamente por lo menos un beneficiario."
                );
            }


            if (beneficiario1TieneDatos
                    && !beneficiario1Completo) {

                return badRequest(
                        "La información del beneficiario 1 está incompleta."
                );
            }


            if (beneficiario2TieneDatos
                    && !beneficiario2Completo) {

                return badRequest(
                        "La información del beneficiario 2 está incompleta."
                );
            }


            if (beneficiario3TieneDatos
                    && !beneficiario3Completo) {

                return badRequest(
                        "La información del beneficiario 3 está incompleta."
                );
            }


            datos.remove("id");
            datos.remove("estado");
            datos.remove("fecha_solicitud");
            datos.remove("fecha_respuesta");
            datos.remove("usuario_respuesta");

            datos.put(
                    "estado",
                    "PENDIENTE"
            );

            datos.put(
                    "fecha_solicitud",
                    new java.sql.Timestamp(
                            System.currentTimeMillis()
                    )
            );

            datos.put(
                    "fecha_respuesta",
                    null
            );

            datos.put(
                    "usuario_respuesta",
                    null
            );

            insertarRegistro(
                    "tblSolicitudAfiliacion",
                    datos
            );

            Integer idSolicitud =
                    sqlServerJdbcTemplate.queryForObject(
                            "SELECT MAX(id) " +
                                    "FROM FEMPROBIEN.dbo.tblSolicitudAfiliacion " +
                                    "WHERE cod_asp = ?",
                            new Object[]{
                                    codAsp
                            },
                            Integer.class
                    );

            if ("ACTUALIZACION_DATOS".equals(
                    tipoSolicitud
            )) {

                guardarCambiosSolicitudActualizacion(
                        idSolicitud,
                        cambiosActualizacion
                );
            }


            Map<String, Object> response =
                    new HashMap<>();

            response.put(
                    "status",
                    201
            );

            response.put(
                    "message",
                    "ACTUALIZACION_DATOS".equals(
                            tipoSolicitud
                    )
                            ? "Solicitud de actualización registrada correctamente."
                            : "Solicitud de afiliación registrada correctamente."
            );

            response.put(
                    "id",
                    idSolicitud
            );

            response.put(
                    "cod_asp",
                    codAsp
            );

            response.put(
                    "estado",
                    "PENDIENTE"
            );

            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(response);

        } catch (IllegalArgumentException e) {

            return badRequest(
                    e.getMessage()
            );

        } catch (Exception e) {

            return internalError(
                    "Error registrando solicitud de afiliación.",
                    e
            );
        }
    }


    /* =========================================================
       LISTAR SOLICITUDES DE AFILIACIÓN

       GET /femprobien/solicitudes-afiliacion?estado=PENDIENTE
       ========================================================= */

    @GetMapping("/solicitudes-afiliacion")
    public ResponseEntity<?> listarSolicitudesAfiliacion(
            @RequestParam(
                    value = "estado",
                    required = false
            )
            String estado) {

        try {

            List<Map<String, Object>> resultado;

            if (estado == null
                    || estado.trim().isEmpty()) {

                resultado =
                        sqlServerJdbcTemplate.queryForList(
                                "SELECT s.* " +
                                        "FROM FEMPROBIEN.dbo.tblSolicitudAfiliacion s " +
                                        "ORDER BY s.fecha_solicitud DESC"
                        );

            } else {

                resultado =
                        sqlServerJdbcTemplate.queryForList(
                                "SELECT s.* " +
                                        "FROM FEMPROBIEN.dbo.tblSolicitudAfiliacion s " +
                                        "WHERE s.estado = ? " +
                                        "ORDER BY s.fecha_solicitud DESC",
                                estado.trim()
                        );
            }

            anexarCambiosSolicitudes(
                    resultado
            );


            return ResponseEntity.ok(
                    resultado
            );

        } catch (Exception e) {

            return internalError(
                    "Error consultando solicitudes de afiliación.",
                    e
            );
        }
    }


    /* =========================================================
       CONSULTAR SOLICITUD

       GET /femprobien/solicitudes-afiliacion/{id}
       ========================================================= */

    @GetMapping("/solicitudes-afiliacion/{id}")
    public ResponseEntity<?> consultarSolicitudAfiliacion(
            @PathVariable Integer id) {

        try {

            List<Map<String, Object>> resultado =
                    sqlServerJdbcTemplate.queryForList(
                            "SELECT s.* " +
                                    "FROM FEMPROBIEN.dbo.tblSolicitudAfiliacion s " +
                                    "WHERE s.id = ?",
                            id
                    );

            if (resultado.isEmpty()) {

                return notFound(
                        "No se encontró la solicitud de afiliación."
                );
            }

            anexarCambiosSolicitudes(
                    resultado
            );


            return ResponseEntity.ok(
                    resultado.get(0)
            );

        } catch (Exception e) {

            return internalError(
                    "Error consultando solicitud de afiliación.",
                    e
            );
        }
    }


    @PatchMapping("/solicitudes-afiliacion/{id}/estado")
    public ResponseEntity<?> cambiarEstadoSolicitudAfiliacion(
            @PathVariable Integer id,
            @RequestBody Map<String, Object> datos) {

        try {

            Object estadoObj =
                    datos.get("estado");

            if (estadoObj == null
                    || estadoObj.toString().trim().isEmpty()) {

                return badRequest(
                        "El estado es obligatorio."
                );
            }

            String estado =
                    estadoObj
                            .toString()
                            .trim()
                            .toUpperCase();

            if (!estado.equals("AFILIADO(A)")
                    && !estado.equals("RECHAZADO(A)")) {

                return badRequest(
                        "Estado no permitido. Use AFILIADO(A) o RECHAZADO(A)."
                );
            }

            List<Map<String, Object>> solicitudes =
                    sqlServerJdbcTemplate.queryForList(
                            "SELECT * " +
                                    "FROM FEMPROBIEN.dbo.tblSolicitudAfiliacion " +
                                    "WHERE id = ?",
                            id
                    );

            if (solicitudes.isEmpty()) {

                return notFound(
                        "No se encontró la solicitud de afiliación."
                );
            }

            Map<String, Object> solicitud =
                    solicitudes.get(0);

            String estadoActual =
                    solicitud.get("estado") == null
                            ? ""
                            : solicitud
                            .get("estado")
                            .toString();

            if (!"PENDIENTE".equalsIgnoreCase(
                    estadoActual
            )) {

                return conflict(
                        "La solicitud ya fue procesada con estado: "
                                + estadoActual
                );
            }

            String usuario =
                    datos.get("usuario") == null
                            ? ""
                            : datos
                            .get("usuario")
                            .toString();

            String observacion =
                    datos.get("observacion") == null
                            ? ""
                            : datos
                            .get("observacion")
                            .toString();

            if ("AFILIADO(A)".equals(
                    estado
            )) {

                String tipoSolicitud =
                        texto(
                                solicitud.get(
                                        "tipo_solicitud"
                                )
                        )
                                .toUpperCase();


                if ("ACTUALIZACION_DATOS".equals(
                        tipoSolicitud
                )) {

                    actualizarAsociadoDesdeSolicitud(
                            solicitud
                    );

                } else {

                    crearAsociadoDesdeSolicitud(
                            solicitud
                    );
                }
            }

            sqlServerJdbcTemplate.update(
                    "UPDATE FEMPROBIEN.dbo.tblSolicitudAfiliacion " +
                            "SET estado = ?, " +
                            "fecha_respuesta = GETDATE(), " +
                            "usuario_respuesta = ?, " +
                            "observacion = ? " +
                            "WHERE id = ?",
                    estado,
                    usuario,
                    observacion,
                    id
            );

            Map<String, Object> response =
                    new HashMap<>();

            response.put(
                    "status",
                    200
            );

            response.put(
                    "message",
                    "Solicitud actualizada correctamente."
            );

            response.put(
                    "id",
                    id
            );

            response.put(
                    "estado",
                    estado
            );

            return ResponseEntity.ok(
                    response
            );

        } catch (IllegalArgumentException e) {

            return badRequest(
                    e.getMessage()
            );

        } catch (Exception e) {

            return internalError(
                    "Error actualizando solicitud de afiliación.",
                    e
            );
        }
    }


    /* =========================================================
       POST
       CREAR ASOCIADO

       POST /femprobien/asociados
       ========================================================= */

    @PostMapping("/asociados")
    public ResponseEntity<?> crearAsociado(
            @RequestBody Map<String, Object> datos) {

        try {

            Object codAspObj =
                    datos.get("cod_asp");

            Object idAsoObj =
                    datos.get("id_aso");


            if (codAspObj == null
                    || codAspObj.toString().trim().isEmpty()) {

                return badRequest(
                        "El campo cod_asp es obligatorio."
                );
            }


            if (idAsoObj == null
                    || idAsoObj.toString().trim().isEmpty()) {

                return badRequest(
                        "El campo id_aso es obligatorio."
                );
            }


            String codAsp =
                    codAspObj
                            .toString()
                            .trim();


            if (existeAsociado(codAsp)) {

                return conflict(
                        "Ya existe un asociado con cod_asp: "
                                + codAsp
                );
            }


            Object fechaRetiro =
                    datos.get("fecha_retiro");

            if (fechaRetiro == null
                    || fechaRetiro.toString().trim().isEmpty()) {

                datos.put(
                        "fecha_retiro",
                        null
                );

                datos.put(
                        "activo",
                        true
                );

            } else {

                datos.put(
                        "activo",
                        false
                );
            }


            insertarRegistro(
                    "tblAsociado",
                    datos
            );


            Map<String, Object> response =
                    new HashMap<>();

            response.put(
                    "status",
                    201
            );

            response.put(
                    "message",
                    "Asociado creado correctamente."
            );

            response.put(
                    "cod_asp",
                    codAsp
            );

            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(response);

        } catch (IllegalArgumentException e) {

            return badRequest(
                    e.getMessage()
            );

        } catch (Exception e) {

            return internalError(
                    "Error creando asociado.",
                    e
            );
        }
    }


    /* =========================================================
       POST
       CREAR APORTES

       POST /femprobien/asociados/{codAsp}/aportes
       ========================================================= */

    @PostMapping("/asociados/{codAsp}/aportes")
    public ResponseEntity<?> crearAportes(
            @PathVariable String codAsp,
            @RequestBody Map<String, Object> datos) {

        try {

            Map<String, Object> asociado =
                    obtenerAsociado(codAsp);


            if (asociado == null) {

                return notFound(
                        "No se encontró el asociado con código: "
                                + codAsp
                );
            }


            String sqlExiste =
                    "SELECT COUNT(*) " +
                            "FROM FEMPROBIEN.dbo.tblAportes " +
                            "WHERE id_aso = ?";


            Integer cantidad =
                    sqlServerJdbcTemplate.queryForObject(
                            sqlExiste,
                            new Object[]{
                                    asociado.get("id_aso")
                            },
                            Integer.class
                    );


            if (cantidad != null
                    && cantidad > 0) {

                return conflict(
                        "El asociado ya tiene un registro de aportes."
                );
            }


            datos.put(
                    "id_aso",
                    asociado.get("id_aso")
            );

            datos.put(
                    "cod_asp",
                    codAsp
            );

            datos.put(
                    "activo",
                    asociado.get("activo")
            );

            datos.put(
                    "fecha_retiro",
                    asociado.get("fecha_retiro")
            );


            insertarRegistro(
                    "tblAportes",
                    datos
            );


            Map<String, Object> response =
                    new HashMap<>();

            response.put(
                    "status",
                    201
            );

            response.put(
                    "message",
                    "Aportes registrados correctamente."
            );

            response.put(
                    "cod_asp",
                    codAsp
            );

            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(response);

        } catch (IllegalArgumentException e) {

            return badRequest(
                    e.getMessage()
            );

        } catch (Exception e) {

            return internalError(
                    "Error registrando aportes.",
                    e
            );
        }
    }


    /* =========================================================
       POST
       CREAR CRÉDITO / DESEMBOLSO

       POST /femprobien/asociados/{codAsp}/creditos
       ========================================================= */

    @PostMapping("/asociados/{codAsp}/creditos")
    public ResponseEntity<?> crearCredito(
            @PathVariable String codAsp,
            @RequestBody Map<String, Object> datos) {

        try {

            Map<String, Object> asociado =
                    obtenerAsociado(codAsp);


            if (asociado == null) {

                return notFound(
                        "No se encontró el asociado con código: "
                                + codAsp
                );
            }


            datos.put(
                    "cod_asp",
                    codAsp
            );

            datos.put(
                    "nom_aso",
                    asociado.get("nom_aso")
            );

            datos.put(
                    "activo",
                    asociado.get("activo")
            );

            datos.put(
                    "fecha_retiro",
                    asociado.get("fecha_retiro")
            );


            insertarRegistro(
                    "tblAporteCredito",
                    datos
            );


            Map<String, Object> response =
                    new HashMap<>();

            response.put(
                    "status",
                    201
            );

            response.put(
                    "message",
                    "Crédito registrado correctamente."
            );

            response.put(
                    "cod_asp",
                    codAsp
            );

            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(response);

        } catch (IllegalArgumentException e) {

            return badRequest(
                    e.getMessage()
            );

        } catch (Exception e) {

            return internalError(
                    "Error registrando crédito.",
                    e
            );
        }
    }


    /* =========================================================
       POST
       CREAR SOLICITUD DE CRÉDITO

       POST /femprobien/asociados/{codAsp}/solicitudes
       ========================================================= */

    @PostMapping("/asociados/{codAsp}/solicitudes")
    public ResponseEntity<?> crearSolicitudCredito(
            @PathVariable String codAsp,
            @RequestBody Map<String, Object> datos) {

        try {

            Map<String, Object> asociado =
                    obtenerAsociado(codAsp);


            if (asociado == null) {

                return notFound(
                        "No se encontró el asociado con código: "
                                + codAsp
                );
            }


            if (datos.get("numero_solicitud") == null
                    || datos.get("numero_solicitud")
                    .toString()
                    .trim()
                    .isEmpty()) {

                return badRequest(
                        "El campo numero_solicitud es obligatorio."
                );
            }


            String sqlExiste =
                    "SELECT COUNT(*) " +
                            "FROM FEMPROBIEN.dbo.tblAsociadoCredito " +
                            "WHERE numero_solicitud = ?";


            Integer cantidad =
                    sqlServerJdbcTemplate.queryForObject(
                            sqlExiste,
                            new Object[]{
                                    datos.get("numero_solicitud")
                            },
                            Integer.class
                    );


            if (cantidad != null
                    && cantidad > 0) {

                return conflict(
                        "Ya existe la solicitud número: "
                                + datos.get("numero_solicitud")
                );
            }


            datos.put(
                    "id_aso",
                    asociado.get("id_aso")
            );

            datos.put(
                    "activo",
                    asociado.get("activo")
            );

            datos.put(
                    "fecha_retiro",
                    asociado.get("fecha_retiro")
            );


            if (datos.get("estado_credito") == null
                    || datos.get("estado_credito")
                    .toString()
                    .trim()
                    .isEmpty()) {

                datos.put(
                        "estado_credito",
                        "PENDIENTE"
                );
            }


            insertarRegistro(
                    "tblAsociadoCredito",
                    datos
            );


            Map<String, Object> response =
                    new HashMap<>();

            response.put(
                    "status",
                    201
            );

            response.put(
                    "message",
                    "Solicitud de crédito creada correctamente."
            );

            response.put(
                    "numero_solicitud",
                    datos.get("numero_solicitud")
            );

            response.put(
                    "cod_asp",
                    codAsp
            );

            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(response);

        } catch (IllegalArgumentException e) {

            return badRequest(
                    e.getMessage()
            );

        } catch (Exception e) {

            return internalError(
                    "Error creando solicitud de crédito.",
                    e
            );
        }
    }


    /* =========================================================
       POST
       CREAR ESTADO DE UNA SOLICITUD

       POST
       /femprobien/asociados/{codAsp}/solicitudes/{numeroSolicitud}/estados
       ========================================================= */

    @PostMapping(
            "/asociados/{codAsp}/solicitudes/{numeroSolicitud}/estados"
    )
    public ResponseEntity<?> crearEstadoCredito(
            @PathVariable String codAsp,
            @PathVariable Integer numeroSolicitud,
            @RequestBody Map<String, Object> datos) {

        try {

            Map<String, Object> asociado =
                    obtenerAsociado(codAsp);


            if (asociado == null) {

                return notFound(
                        "No se encontró el asociado con código: "
                                + codAsp
                );
            }


            Object estadoObj =
                    datos.get("estado");


            if (estadoObj == null
                    || estadoObj.toString().trim().isEmpty()) {

                return badRequest(
                        "El campo estado es obligatorio."
                );
            }


            String estado =
                    estadoObj
                            .toString()
                            .trim()
                            .toUpperCase();


            if (!estado.equals("PENDIENTE")
                    && !estado.equals("EN_ESTUDIO")
                    && !estado.equals("APROBADA")
                    && !estado.equals("NEGADA")
                    && !estado.equals("APLAZADA")) {

                return badRequest(
                        "Estado no válido. " +
                                "Estados permitidos: " +
                                "PENDIENTE, EN_ESTUDIO, APROBADA, " +
                                "NEGADA, APLAZADA."
                );
            }


            String sqlSolicitud =
                    "SELECT ac.id " +
                            "FROM FEMPROBIEN.dbo.tblAsociadoCredito ac " +

                            "INNER JOIN FEMPROBIEN.dbo.tblAsociado a " +
                            "ON a.id_aso = ac.id_aso " +

                            "WHERE a.cod_asp = ? " +
                            "AND ac.numero_solicitud = ?";


            List<Map<String, Object>> solicitud =
                    sqlServerJdbcTemplate.queryForList(
                            sqlSolicitud,
                            codAsp,
                            numeroSolicitud
                    );


            if (solicitud.isEmpty()) {

                return notFound(
                        "No se encontró la solicitud "
                                + numeroSolicitud
                                + " para el asociado "
                                + codAsp
                );
            }


            Object idSolicitud =
                    solicitud.get(0)
                            .get("id");


            datos.put(
                    "id_solicitud",
                    idSolicitud
            );

            datos.put(
                    "estado",
                    estado
            );

            datos.put(
                    "activo",
                    asociado.get("activo")
            );

            datos.put(
                    "fecha_retiro",
                    asociado.get("fecha_retiro")
            );


            datos.remove(
                    "fecha_estado"
            );

            datos.remove(
                    "fecha_creacion"
            );


            insertarRegistro(
                    "tblEstadoCredito",
                    datos
            );


            String sqlUpdate =
                    "UPDATE FEMPROBIEN.dbo.tblAsociadoCredito " +
                            "SET estado_credito = ? " +
                            "WHERE id = ?";


            sqlServerJdbcTemplate.update(
                    sqlUpdate,
                    estado,
                    idSolicitud
            );


            Map<String, Object> response =
                    new HashMap<>();

            response.put(
                    "status",
                    201
            );

            response.put(
                    "message",
                    "Estado registrado correctamente."
            );

            response.put(
                    "numero_solicitud",
                    numeroSolicitud
            );

            response.put(
                    "estado",
                    estado
            );

            response.put(
                    "cod_asp",
                    codAsp
            );

            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(response);

        } catch (IllegalArgumentException e) {

            return badRequest(
                    e.getMessage()
            );

        } catch (Exception e) {

            return internalError(
                    "Error registrando estado de crédito.",
                    e
            );
        }
    }


    private Map<String, Object> obtenerAsociado(
            String codAsp) {

        String sql =
                "SELECT " +
                        "id_aso, " +
                        "cod_asp, " +
                        "nom_aso, " +
                        "activo, " +
                        "fecha_retiro " +
                        "FROM FEMPROBIEN.dbo.tblAsociado " +
                        "WHERE cod_asp = ?";


        List<Map<String, Object>> resultado =
                sqlServerJdbcTemplate.queryForList(
                        sql,
                        codAsp
                );


        if (resultado.isEmpty()) {
            return null;
        }


        return resultado.get(0);
    }


    private boolean existeAsociado(
            String codAsp) {

        String sql =
                "SELECT COUNT(*) " +
                        "FROM FEMPROBIEN.dbo.tblAsociado " +
                        "WHERE cod_asp = ?";


        Integer cantidad =
                sqlServerJdbcTemplate.queryForObject(
                        sql,
                        new Object[]{
                                codAsp
                        },
                        Integer.class
                );


        return cantidad != null
                && cantidad > 0;
    }


    private void actualizarAsociadoDesdeSolicitud(
            Map<String, Object> solicitud) {


        String codAsp =
                texto(
                        solicitud.get(
                                "cod_asp"
                        )
                );


        if (codAsp.isEmpty()) {

            throw new IllegalArgumentException(
                    "La solicitud no contiene cod_asp."
            );
        }


        if (!existeAsociado(codAsp)) {

            throw new IllegalArgumentException(
                    "No existe el asociado con cod_asp: "
                            + codAsp
            );
        }


        Map<String, Object> datosSolicitados =
                construirDatosAsociadoDesdeSolicitud(
                        solicitud
                );


        Integer idSolicitud =
                solicitud.get("id") == null
                        ? null
                        : Integer.valueOf(
                        solicitud
                                .get("id")
                                .toString()
                );


        List<String> camposCambios =
                new ArrayList<>();


        if (idSolicitud != null) {

            camposCambios =
                    sqlServerJdbcTemplate.queryForList(
                            "SELECT campo " +
                                    "FROM FEMPROBIEN.dbo.tblSolicitudAfiliacionCambio " +
                                    "WHERE id_solicitud = ? " +
                                    "ORDER BY id",
                            new Object[]{
                                    idSolicitud
                            },
                            String.class
                    );
        }


        Map<String, Object> datosActualizar =
                new HashMap<>();


        /*
         * Solicitudes nuevas:
         * actualizamos exclusivamente los campos registrados
         * en tblSolicitudAfiliacionCambio.
         *
         * Solicitudes antiguas:
         * si todavía no poseen detalle histórico, se conserva
         * el comportamiento anterior para no bloquearlas.
         */
        if (!camposCambios.isEmpty()) {

            for (Map.Entry<String, Object> entry
                    : datosSolicitados.entrySet()) {

                if (contieneIgnoreCase(
                        camposCambios,
                        entry.getKey()
                )) {

                    datosActualizar.put(
                            entry.getKey(),
                            entry.getValue()
                    );
                }
            }

        } else {

            datosActualizar.putAll(
                    datosSolicitados
            );
        }


        if (datosActualizar.isEmpty()) {

            throw new IllegalArgumentException(
                    "La solicitud no contiene cambios válidos para aplicar."
            );
        }


        actualizarRegistro(
                "tblAsociado",
                datosActualizar,
                "cod_asp",
                codAsp
        );
    }


    private Map<String, Object> construirDatosAsociadoDesdeSolicitud(
            Map<String, Object> solicitud) {


        Map<String, Object> asociado =
                new HashMap<>();


        String nombres =
                texto(
                        solicitud.get(
                                "nombres"
                        )
                );

        String apellido1 =
                texto(
                        solicitud.get(
                                "primer_apellido"
                        )
                );

        String apellido2 =
                texto(
                        solicitud.get(
                                "segundo_apellido"
                        )
                );


        String nombreCompleto =
                (
                        nombres
                                + " "
                                + apellido1
                                + " "
                                + apellido2
                )
                        .replaceAll(
                                "\\s+",
                                " "
                        )
                        .trim();


        if (!nombreCompleto.isEmpty()) {

            asociado.put(
                    "nom_aso",
                    nombreCompleto
            );
        }


        asociado.put(
                "forma_desc",
                solicitud.get("forma_desc")
        );

        asociado.put(
                "cant_porcen",
                solicitud.get("cant_porcen")
        );

        asociado.put(
                "porcen_ahorro",
                solicitud.get("porcen_ahorro")
        );

        asociado.put(
                "est_civil",
                solicitud.get("est_civil")
        );

        asociado.put(
                "fec_nac",
                solicitud.get("fec_nac")
        );

        asociado.put(
                "fec_exp",
                solicitud.get("fec_exp")
        );

        asociado.put(
                "lugar_exp",
                solicitud.get("lugar_exp")
        );

        asociado.put(
                "email_per",
                solicitud.get("email_per")
        );

        asociado.put(
                "dir_res",
                solicitud.get("dir_res")
        );

        asociado.put(
                "nom_bar",
                solicitud.get("ciudad_residencia")
        );

        asociado.put(
                "tel",
                solicitud.get("tel")
        );

        asociado.put(
                "cel",
                solicitud.get("cel")
        );

        asociado.put(
                "nivel_estudios",
                solicitud.get("nivel_estudios")
        );

        asociado.put(
                "nom_emp",
                solicitud.get("nom_emp")
        );

        asociado.put(
                "fec_ing",
                solicitud.get("fec_ing")
        );

        asociado.put(
                "sal_bas",
                solicitud.get("sal_bas")
        );

        asociado.put(
                "prom_pres",
                solicitud.get("prom_pres")
        );

        asociado.put(
                "prom_boni",
                solicitud.get("prom_boni")
        );

        asociado.put(
                "cargo",
                solicitud.get("cargo")
        );

        asociado.put(
                "profesion",
                solicitud.get("profesion")
        );

        asociado.put(
                "tipo_cuenta",
                solicitud.get("tipo_cuenta")
        );

        asociado.put(
                "banco",
                solicitud.get("banco")
        );

        asociado.put(
                "n_cuenta",
                solicitud.get("n_cuenta")
        );

        asociado.put(
                "nom_conyuge",
                solicitud.get("nom_conyuge")
        );

        asociado.put(
                "ocu_conyuge",
                solicitud.get("ocu_conyuge")
        );

        asociado.put(
                "tel_conyuge",
                solicitud.get("tel_conyuge")
        );

        asociado.put(
                "tip_doc",
                solicitud.get("tipo_doc_conyuge")
        );

        asociado.put(
                "doc_conyuge",
                solicitud.get("doc_conyuge")
        );

        asociado.put(
                "bien_raices",
                solicitud.get("bien_raices")
        );

        asociado.put(
                "bienes_dic",
                solicitud.get("bienes_dir")
        );

        asociado.put(
                "vehiculo",
                solicitud.get("vehiculo")
        );

        asociado.put(
                "marca_veh",
                solicitud.get("marca_veh")
        );

        asociado.put(
                "modelo_veh",
                solicitud.get("modelo_veh")
        );

        asociado.put(
                "ben_nom1",
                solicitud.get("ben_nom1")
        );

        asociado.put(
                "ben_doc1",
                solicitud.get("ben_doc1")
        );

        asociado.put(
                "parentesco1",
                solicitud.get("parentesco1")
        );

        asociado.put(
                "ben_nom2",
                solicitud.get("ben_nom2")
        );

        asociado.put(
                "ben_doc2",
                solicitud.get("ben_doc2")
        );

        asociado.put(
                "parentesco2",
                solicitud.get("parentesco2")
        );

        asociado.put(
                "ben_nom3",
                solicitud.get("ben_nom3")
        );

        asociado.put(
                "ben_doc3",
                solicitud.get("ben_doc3")
        );

        asociado.put(
                "parentesco3",
                solicitud.get("parentesco3")
        );


        agregarSiExiste(
                "tblAsociado",
                asociado,
                "tipo_documento",
                solicitud.get("tipo_documento")
        );

        agregarSiExiste(
                "tblAsociado",
                asociado,
                "otro_tel",
                solicitud.get("otro_tel")
        );

        agregarSiExiste(
                "tblAsociado",
                asociado,
                "maneja_recursos_publicos",
                solicitud.get("maneja_recursos_publicos")
        );

        agregarSiExiste(
                "tblAsociado",
                asociado,
                "reconocimiento_publico",
                solicitud.get("reconocimiento_publico")
        );

        agregarSiExiste(
                "tblAsociado",
                asociado,
                "tipo_doc_ben1",
                solicitud.get("tipo_doc_ben1")
        );

        agregarSiExiste(
                "tblAsociado",
                asociado,
                "tipo_doc_ben2",
                solicitud.get("tipo_doc_ben2")
        );

        agregarSiExiste(
                "tblAsociado",
                asociado,
                "tipo_doc_ben3",
                solicitud.get("tipo_doc_ben3")
        );


        return asociado;
    }


    private void crearAsociadoDesdeSolicitud(
            Map<String, Object> solicitud) {


        String codAsp =
                solicitud.get("cod_asp") == null
                        ? ""
                        : solicitud
                        .get("cod_asp")
                        .toString()
                        .trim();


        if (codAsp.isEmpty()) {

            throw new IllegalArgumentException(
                    "La solicitud no contiene cod_asp."
            );
        }


        if (existeAsociado(codAsp)) {

            throw new IllegalArgumentException(
                    "El asociado ya existe con cod_asp: "
                            + codAsp
            );
        }


        Integer siguienteId =
                sqlServerJdbcTemplate.queryForObject(
                        "SELECT ISNULL(MAX(id_aso), 0) + 1 " +
                                "FROM FEMPROBIEN.dbo.tblAsociado",
                        Integer.class
                );


        String nombres =
                texto(
                        solicitud.get("nombres")
                );


        String apellido1 =
                texto(
                        solicitud.get("primer_apellido")
                );


        String apellido2 =
                texto(
                        solicitud.get("segundo_apellido")
                );


        String nombreCompleto =
                (
                        nombres
                                + " "
                                + apellido1
                                + " "
                                + apellido2
                )
                        .replaceAll(
                                "\\s+",
                                " "
                        )
                        .trim();


        Map<String, Object> asociado =
                new HashMap<>();


        asociado.put(
                "id_aso",
                siguienteId
        );

        asociado.put(
                "nom_aso",
                nombreCompleto
        );

        asociado.put(
                "cod_asp",
                codAsp
        );


        asociado.put(
                "forma_desc",
                solicitud.get("forma_desc")
        );

        asociado.put(
                "cant_porcen",
                solicitud.get("cant_porcen")
        );

        asociado.put(
                "porcen_ahorro",
                solicitud.get("porcen_ahorro")
        );


        asociado.put(
                "est_civil",
                solicitud.get("est_civil")
        );

        asociado.put(
                "fec_nac",
                solicitud.get("fec_nac")
        );

        asociado.put(
                "fec_exp",
                solicitud.get("fec_exp")
        );

        asociado.put(
                "lugar_exp",
                solicitud.get("lugar_exp")
        );

        asociado.put(
                "email_per",
                solicitud.get("email_per")
        );

        asociado.put(
                "dir_res",
                solicitud.get("dir_res")
        );

        asociado.put(
                "nom_bar",
                solicitud.get("ciudad_residencia")
        );

        asociado.put(
                "tel",
                solicitud.get("tel")
        );

        asociado.put(
                "cel",
                solicitud.get("cel")
        );

        asociado.put(
                "nivel_estudios",
                solicitud.get("nivel_estudios")
        );


        asociado.put(
                "nom_emp",
                solicitud.get("nom_emp")
        );

        asociado.put(
                "fec_ing",
                solicitud.get("fec_ing")
        );

        asociado.put(
                "sal_bas",
                solicitud.get("sal_bas")
        );

        asociado.put(
                "prom_pres",
                solicitud.get("prom_pres")
        );

        asociado.put(
                "prom_boni",
                solicitud.get("prom_boni")
        );

        asociado.put(
                "cargo",
                solicitud.get("cargo")
        );

        asociado.put(
                "profesion",
                solicitud.get("profesion")
        );


        asociado.put(
                "tipo_cuenta",
                solicitud.get("tipo_cuenta")
        );

        asociado.put(
                "banco",
                solicitud.get("banco")
        );

        asociado.put(
                "n_cuenta",
                solicitud.get("n_cuenta")
        );


        asociado.put(
                "nom_conyuge",
                solicitud.get("nom_conyuge")
        );

        asociado.put(
                "ocu_conyuge",
                solicitud.get("ocu_conyuge")
        );

        asociado.put(
                "tel_conyuge",
                solicitud.get("tel_conyuge")
        );


        asociado.put(
                "tip_doc",
                solicitud.get("tipo_doc_conyuge")
        );


        asociado.put(
                "doc_conyuge",
                solicitud.get("doc_conyuge")
        );


        asociado.put(
                "bien_raices",
                solicitud.get("bien_raices")
        );

        asociado.put(
                "bienes_dic",
                solicitud.get("bienes_dir")
        );

        asociado.put(
                "vehiculo",
                solicitud.get("vehiculo")
        );

        asociado.put(
                "marca_veh",
                solicitud.get("marca_veh")
        );

        asociado.put(
                "modelo_veh",
                solicitud.get("modelo_veh")
        );


        asociado.put(
                "ben_nom1",
                solicitud.get("ben_nom1")
        );

        asociado.put(
                "ben_doc1",
                solicitud.get("ben_doc1")
        );

        asociado.put(
                "parentesco1",
                solicitud.get("parentesco1")
        );


        asociado.put(
                "ben_nom2",
                solicitud.get("ben_nom2")
        );

        asociado.put(
                "ben_doc2",
                solicitud.get("ben_doc2")
        );

        asociado.put(
                "parentesco2",
                solicitud.get("parentesco2")
        );


        asociado.put(
                "ben_nom3",
                solicitud.get("ben_nom3")
        );

        asociado.put(
                "ben_doc3",
                solicitud.get("ben_doc3")
        );

        asociado.put(
                "parentesco3",
                solicitud.get("parentesco3")
        );


        agregarSiExiste(
                "tblAsociado",
                asociado,
                "tipo_solicitud",
                solicitud.get("tipo_solicitud")
        );

        agregarSiExiste(
                "tblAsociado",
                asociado,
                "tipo_ingreso",
                solicitud.get("tipo_ingreso")
        );

        agregarSiExiste(
                "tblAsociado",
                asociado,
                "fec_afi",
                solicitud.get("fecha_afiliacion")
        );

        agregarSiExiste(
                "tblAsociado",
                asociado,
                "tipo_documento",
                solicitud.get("tipo_documento")
        );

        agregarSiExiste(
                "tblAsociado",
                asociado,
                "otro_tel",
                solicitud.get("otro_tel")
        );

        agregarSiExiste(
                "tblAsociado",
                asociado,
                "maneja_recursos_publicos",
                solicitud.get("maneja_recursos_publicos")
        );

        agregarSiExiste(
                "tblAsociado",
                asociado,
                "reconocimiento_publico",
                solicitud.get("reconocimiento_publico")
        );

        agregarSiExiste(
                "tblAsociado",
                asociado,
                "tipo_doc_ben1",
                solicitud.get("tipo_doc_ben1")
        );

        agregarSiExiste(
                "tblAsociado",
                asociado,
                "tipo_doc_ben2",
                solicitud.get("tipo_doc_ben2")
        );

        agregarSiExiste(
                "tblAsociado",
                asociado,
                "tipo_doc_ben3",
                solicitud.get("tipo_doc_ben3")
        );


        agregarSiExiste(
                "tblAsociado",
                asociado,
                "activo",
                true
        );

        agregarSiExiste(
                "tblAsociado",
                asociado,
                "fecha_retiro",
                null
        );


        agregarSiExiste(
                "tblAsociado",
                asociado,
                "est_aso",
                "ACTIVO"
        );

        agregarSiExiste(
                "tblAsociado",
                asociado,
                "fec_ret",
                null
        );


        insertarRegistro(
                "tblAsociado",
                asociado
        );
    }


    private void agregarSiExiste(
            String tabla,
            Map<String, Object> datos,
            String columna,
            Object valor) {

        String sql =
                "SELECT COUNT(*) " +
                        "FROM FEMPROBIEN.INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_SCHEMA = 'dbo' " +
                        "AND TABLE_NAME = ? " +
                        "AND COLUMN_NAME = ?";


        Integer cantidad =
                sqlServerJdbcTemplate.queryForObject(
                        sql,
                        new Object[]{
                                tabla,
                                columna
                        },
                        Integer.class
                );


        if (cantidad != null
                && cantidad > 0) {

            datos.put(
                    columna,
                    valor
            );
        }
    }


    private List<Map<String, Object>> detectarCambiosSolicitudActualizacion(
            Map<String, Object> solicitud,
            String codAsp) {


        List<Map<String, Object>> asociados =
                sqlServerJdbcTemplate.queryForList(
                        "SELECT TOP 1 * " +
                                "FROM FEMPROBIEN.dbo.tblAsociado " +
                                "WHERE LTRIM(RTRIM(CAST(cod_asp AS VARCHAR(100)))) = ?",
                        codAsp
                );


        if (asociados.isEmpty()) {

            throw new IllegalArgumentException(
                    "No existe el asociado con cod_asp: "
                            + codAsp
            );
        }


        Map<String, Object> asociadoActual =
                asociados.get(0);


        Map<String, Object> datosSolicitados =
                construirDatosAsociadoDesdeSolicitud(
                        solicitud
                );


        List<Map<String, Object>> cambios =
                new ArrayList<>();


        for (Map.Entry<String, Object> entry
                : datosSolicitados.entrySet()) {


            String campo =
                    entry.getKey();

            Object valorAnterior =
                    obtenerValorMapaIgnoreCase(
                            asociadoActual,
                            campo
                    );

            Object valorNuevo =
                    entry.getValue();


            if (!valoresEquivalentes(
                    valorAnterior,
                    valorNuevo
            )) {

                Map<String, Object> cambio =
                        new HashMap<>();

                cambio.put(
                        "campo",
                        campo
                );

                cambio.put(
                        "valor_anterior",
                        valorAuditoria(
                                valorAnterior
                        )
                );

                cambio.put(
                        "valor_nuevo",
                        valorAuditoria(
                                valorNuevo
                        )
                );


                cambios.add(
                        cambio
                );
            }
        }


        return cambios;
    }


    private void guardarCambiosSolicitudActualizacion(
            Integer idSolicitud,
            List<Map<String, Object>> cambios) {


        if (idSolicitud == null
                || cambios == null
                || cambios.isEmpty()) {

            return;
        }


        for (Map<String, Object> cambio
                : cambios) {

            sqlServerJdbcTemplate.update(
                    "INSERT INTO FEMPROBIEN.dbo.tblSolicitudAfiliacionCambio " +
                            "(id_solicitud, campo, valor_anterior, valor_nuevo, fecha_registro) " +
                            "VALUES (?, ?, ?, ?, GETDATE())",
                    idSolicitud,
                    cambio.get("campo"),
                    cambio.get("valor_anterior"),
                    cambio.get("valor_nuevo")
            );
        }
    }


    private void anexarCambiosSolicitudes(
            List<Map<String, Object>> solicitudes) {


        if (solicitudes == null
                || solicitudes.isEmpty()) {

            return;
        }


        for (Map<String, Object> solicitud
                : solicitudes) {


            String tipoSolicitud =
                    texto(
                            solicitud.get(
                                    "tipo_solicitud"
                            )
                    )
                            .toUpperCase();


            if (!"ACTUALIZACION_DATOS".equals(
                    tipoSolicitud
            )) {

                solicitud.put(
                        "cambios",
                        new ArrayList<Map<String, Object>>()
                );

                continue;
            }


            Object idSolicitud =
                    solicitud.get("id");


            if (idSolicitud == null) {

                solicitud.put(
                        "cambios",
                        new ArrayList<Map<String, Object>>()
                );

                continue;
            }


            List<Map<String, Object>> cambios =
                    sqlServerJdbcTemplate.queryForList(
                            "SELECT id, id_solicitud, campo, " +
                                    "valor_anterior, valor_nuevo, fecha_registro " +
                                    "FROM FEMPROBIEN.dbo.tblSolicitudAfiliacionCambio " +
                                    "WHERE id_solicitud = ? " +
                                    "ORDER BY id",
                            idSolicitud
                    );


            solicitud.put(
                    "cambios",
                    cambios
            );
        }
    }


    private Object obtenerValorMapaIgnoreCase(
            Map<String, Object> datos,
            String campo) {


        if (datos == null
                || campo == null) {

            return null;
        }


        for (Map.Entry<String, Object> entry
                : datos.entrySet()) {

            if (entry.getKey().equalsIgnoreCase(
                    campo
            )) {

                return entry.getValue();
            }
        }


        return null;
    }


    private boolean contieneIgnoreCase(
            List<String> valores,
            String buscado) {


        if (valores == null
                || buscado == null) {

            return false;
        }


        for (String valor : valores) {

            if (valor != null
                    && valor.equalsIgnoreCase(
                    buscado
            )) {

                return true;
            }
        }


        return false;
    }


    private boolean valoresEquivalentes(
            Object valorAnterior,
            Object valorNuevo) {


        String anterior =
                normalizarValorComparacion(
                        valorAnterior
                );

        String nuevo =
                normalizarValorComparacion(
                        valorNuevo
                );


        return anterior.equals(
                nuevo
        );
    }


    private String normalizarValorComparacion(
            Object valor) {


        if (valor == null) {
            return "";
        }


        if (valor instanceof Boolean) {

            return ((Boolean) valor)
                    ? "1"
                    : "0";
        }


        if (valor instanceof Number) {

            try {

                return new java.math.BigDecimal(
                        valor.toString()
                )
                        .stripTrailingZeros()
                        .toPlainString();

            } catch (Exception ignored) {

                return valor
                        .toString()
                        .trim();
            }
        }


        if (valor instanceof java.util.Date) {

            return new java.text.SimpleDateFormat(
                    "yyyy-MM-dd"
            )
                    .format(
                            (java.util.Date) valor
                    );
        }


        String textoValor =
                valor
                        .toString()
                        .trim();


        if (textoValor.matches(
                "^\\d{4}-\\d{2}-\\d{2}.*$"
        )) {

            return textoValor.substring(
                    0,
                    10
            );
        }


        if ("TRUE".equalsIgnoreCase(
                textoValor
        )
                || "SI".equalsIgnoreCase(
                textoValor
        )
                || "SÍ".equalsIgnoreCase(
                textoValor
        )
                || "Y".equalsIgnoreCase(
                textoValor
        )) {

            return "1";
        }


        if ("FALSE".equalsIgnoreCase(
                textoValor
        )
                || "NO".equalsIgnoreCase(
                textoValor
        )
                || "N".equalsIgnoreCase(
                textoValor
        )) {

            return "0";
        }


        return textoValor;
    }


    private String valorAuditoria(
            Object valor) {


        if (valor == null) {
            return "";
        }


        if (valor instanceof Boolean) {

            return ((Boolean) valor)
                    ? "SI"
                    : "NO";
        }


        if (valor instanceof Number) {

            try {

                return new java.math.BigDecimal(
                        valor.toString()
                )
                        .stripTrailingZeros()
                        .toPlainString();

            } catch (Exception ignored) {

                return valor
                        .toString()
                        .trim();
            }
        }


        if (valor instanceof java.util.Date) {

            return new java.text.SimpleDateFormat(
                    "yyyy-MM-dd"
            )
                    .format(
                            (java.util.Date) valor
                    );
        }


        String textoValor =
                valor
                        .toString()
                        .trim();


        if ("TRUE".equalsIgnoreCase(
                textoValor
        )
                || "SI".equalsIgnoreCase(
                textoValor
        )
                || "SÍ".equalsIgnoreCase(
                textoValor
        )
                || "Y".equalsIgnoreCase(
                textoValor
        )) {

            return "SI";
        }


        if ("FALSE".equalsIgnoreCase(
                textoValor
        )
                || "NO".equalsIgnoreCase(
                textoValor
        )
                || "N".equalsIgnoreCase(
                textoValor
        )) {

            return "NO";
        }


        if (textoValor.matches(
                "^\\d{4}-\\d{2}-\\d{2}.*$"
        )) {

            return textoValor.substring(
                    0,
                    10
            );
        }


        return textoValor;
    }


    private String texto(
            Object valor) {

        if (valor == null) {
            return "";
        }

        return valor
                .toString()
                .trim();
    }


    private void actualizarRegistro(
            String tabla,
            Map<String, Object> datos,
            String columnaCondicion,
            Object valorCondicion) {


        if (!tabla.equals("tblAsociado")) {

            throw new IllegalArgumentException(
                    "Tabla no permitida para actualización: "
                            + tabla
            );
        }


        String sqlColumnas =
                "SELECT c.name " +
                        "FROM FEMPROBIEN.sys.columns c " +

                        "INNER JOIN FEMPROBIEN.sys.tables t " +
                        "ON t.object_id = c.object_id " +

                        "INNER JOIN FEMPROBIEN.sys.schemas s " +
                        "ON s.schema_id = t.schema_id " +

                        "WHERE t.name = ? " +
                        "AND s.name = 'dbo' " +
                        "AND c.is_identity = 0 " +
                        "AND c.is_computed = 0";


        List<String> columnasPermitidas =
                sqlServerJdbcTemplate.queryForList(
                        sqlColumnas,
                        new Object[]{
                                tabla
                        },
                        String.class
                );


        String columnaCondicionReal =
                buscarColumna(
                        columnasPermitidas,
                        columnaCondicion
                );


        if (columnaCondicionReal == null) {

            throw new IllegalArgumentException(
                    "La columna de condición '"
                            + columnaCondicion
                            + "' no existe en "
                            + tabla
            );
        }


        StringBuilder asignaciones =
                new StringBuilder();

        List<Object> parametros =
                new ArrayList<>();


        for (Map.Entry<String, Object> entry
                : datos.entrySet()) {


            String columnaSolicitada =
                    entry.getKey();


            String columnaReal =
                    buscarColumna(
                            columnasPermitidas,
                            columnaSolicitada
                    );


            if (columnaReal == null) {

                throw new IllegalArgumentException(
                        "El campo '"
                                + columnaSolicitada
                                + "' no existe en "
                                + tabla
                );
            }


            if (asignaciones.length() > 0) {

                asignaciones.append(
                        ", "
                );
            }


            asignaciones
                    .append("[")
                    .append(columnaReal)
                    .append("] = ?");


            parametros.add(
                    entry.getValue()
            );
        }


        if (parametros.isEmpty()) {

            throw new IllegalArgumentException(
                    "No se recibieron campos para actualizar."
            );
        }


        parametros.add(
                valorCondicion
        );


        String sql =
                "UPDATE FEMPROBIEN.dbo."
                        + tabla
                        + " SET "
                        + asignaciones
                        + " WHERE ["
                        + columnaCondicionReal
                        + "] = ?";


        int actualizados =
                sqlServerJdbcTemplate.update(
                        sql,
                        parametros.toArray()
                );


        if (actualizados <= 0) {

            throw new IllegalArgumentException(
                    "No se encontró el registro a actualizar en "
                            + tabla
            );
        }
    }


    private void insertarRegistro(
            String tabla,
            Map<String, Object> datos) {


        if (!tabla.equals("tblAsociado")
                && !tabla.equals("tblAportes")
                && !tabla.equals("tblAporteCredito")
                && !tabla.equals("tblAsociadoCredito")
                && !tabla.equals("tblEstadoCredito")
                && !tabla.equals("tblSolicitudAfiliacion")) {

            throw new IllegalArgumentException(
                    "Tabla no permitida: "
                            + tabla
            );
        }


        String sqlColumnas =
                "SELECT c.name " +
                        "FROM FEMPROBIEN.sys.columns c " +

                        "INNER JOIN FEMPROBIEN.sys.tables t " +
                        "ON t.object_id = c.object_id " +

                        "INNER JOIN FEMPROBIEN.sys.schemas s " +
                        "ON s.schema_id = t.schema_id " +

                        "WHERE t.name = ? " +
                        "AND s.name = 'dbo' " +
                        "AND c.is_identity = 0 " +
                        "AND c.is_computed = 0";


        List<String> columnasPermitidas =
                sqlServerJdbcTemplate.queryForList(
                        sqlColumnas,
                        new Object[]{
                                tabla
                        },
                        String.class
                );


        StringBuilder columnas =
                new StringBuilder();

        StringBuilder valores =
                new StringBuilder();

        List<Object> parametros =
                new ArrayList<>();


        for (Map.Entry<String, Object> entry
                : datos.entrySet()) {


            String columnaSolicitada =
                    entry.getKey();


            String columnaReal =
                    buscarColumna(
                            columnasPermitidas,
                            columnaSolicitada
                    );


            if (columnaReal == null) {

                throw new IllegalArgumentException(
                        "El campo '"
                                + columnaSolicitada
                                + "' no existe en "
                                + tabla
                );
            }


            if (columnas.length() > 0) {

                columnas.append(", ");
                valores.append(", ");
            }


            columnas
                    .append("[")
                    .append(columnaReal)
                    .append("]");


            valores.append("?");


            parametros.add(
                    entry.getValue()
            );
        }


        if (parametros.isEmpty()) {

            throw new IllegalArgumentException(
                    "No se recibieron campos para insertar."
            );
        }


        String sql =
                "INSERT INTO FEMPROBIEN.dbo."
                        + tabla
                        + " ("
                        + columnas
                        + ") VALUES ("
                        + valores
                        + ")";


        sqlServerJdbcTemplate.update(
                sql,
                parametros.toArray()
        );
    }


    private String buscarColumna(
            List<String> columnas,
            String columnaBuscada) {

        for (String columna : columnas) {

            if (columna.equalsIgnoreCase(
                    columnaBuscada
            )) {

                return columna;
            }
        }

        return null;
    }


    private ResponseEntity<?> badRequest(
            String message) {

        Map<String, Object> response =
                new HashMap<>();

        response.put(
                "status",
                400
        );

        response.put(
                "message",
                message
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }


    private ResponseEntity<?> notFound(
            String message) {

        Map<String, Object> response =
                new HashMap<>();

        response.put(
                "status",
                404
        );

        response.put(
                "message",
                message
        );

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(response);
    }


    private ResponseEntity<?> conflict(
            String message) {

        Map<String, Object> response =
                new HashMap<>();

        response.put(
                "status",
                409
        );

        response.put(
                "message",
                message
        );

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(response);
    }


    private ResponseEntity<?> internalError(
            String message,
            Exception e) {

        Map<String, Object> response =
                new HashMap<>();

        response.put(
                "status",
                500
        );

        response.put(
                "message",
                message
        );

        response.put(
                "error",
                e.getMessage()
        );

        return ResponseEntity
                .status(
                        HttpStatus.INTERNAL_SERVER_ERROR
                )
                .body(response);
    }
}
