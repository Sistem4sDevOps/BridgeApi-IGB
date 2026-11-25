
package com.IGB.BridgeApi.controller;

import com.IGB.BridgeApi.service.NominaNovawebService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/femprobien/nomina")
public class NominaNovawebController {

    private static final int MAX_EMPLEADOS = 100;

    private final NominaNovawebService nominaService;

    // ==========================================
    // CONSTRUCTOR
    // ==========================================

    public NominaNovawebController(
            NominaNovawebService nominaService) {

        this.nominaService = nominaService;
    }

    // ==========================================
    // CONSULTAR ULTIMA NOMINA
    // ==========================================

    @PostMapping("/consultar")
    public ResponseEntity<Map<String, Object>> consultar(
            @RequestBody(required = false)
            Map<String, Object> request) {

        return procesarSolicitud(request, false);
    }

    // ==========================================
    // CONSULTAR DOS QUINCENAS Y CONSOLIDAR
    // ==========================================

    @PostMapping("/consolidar")
    public ResponseEntity<Map<String, Object>> consolidar(
            @RequestBody(required = false)
            Map<String, Object> request) {

        return procesarSolicitud(request, true);
    }

    // ==========================================
    // VALIDAR PETICION Y CONSULTAR
    // ==========================================

    private ResponseEntity<Map<String, Object>>
    procesarSolicitud(
            Map<String, Object> request,
            boolean consolidar) {

        if (request == null) {

            return error(
                    HttpStatus.BAD_REQUEST,
                    "El cuerpo de la petición es obligatorio."
            );
        }

        // ======================================
        // EMPRESA OPCIONAL
        // ======================================

        String empresa = null;

        Object valorEmpresa =
                request.get("empresa");

        if (valorEmpresa != null) {

            if (!(valorEmpresa instanceof String)) {

                return error(
                        HttpStatus.BAD_REQUEST,
                        "La empresa debe ser texto."
                );
            }

            empresa = ((String) valorEmpresa).trim();
        }

        // ======================================
        // IDENTIFICACION INDIVIDUAL
        // ======================================

        Set<String> ids = new LinkedHashSet<>();

        Object idUnico =
                request.get("identificacion");

        if (idUnico != null) {

            if (!(idUnico instanceof String)) {

                return error(
                        HttpStatus.BAD_REQUEST,
                        "La identificación debe ser texto."
                );
            }

            String id =
                    ((String) idUnico).trim();

            if (!id.isEmpty()) {
                ids.add(id);
            }
        }

        // ======================================
        // IDENTIFICACIONES MULTIPLES
        // ======================================

        Object multiples =
                request.get("identificaciones");

        if (multiples != null) {

            if (!(multiples instanceof List)) {

                return error(
                        HttpStatus.BAD_REQUEST,
                        "Identificaciones debe ser un arreglo."
                );
            }

            for (Object item : (List<?>) multiples) {

                if (!(item instanceof String)) {

                    return error(
                            HttpStatus.BAD_REQUEST,
                            "Todas las identificaciones " +
                                    "deben ser texto."
                    );
                }

                String id =
                        ((String) item).trim();

                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        }

        // ======================================
        // VALIDACIONES
        // ======================================

        if (ids.isEmpty()) {

            return error(
                    HttpStatus.BAD_REQUEST,
                    "Debe proporcionar una o varias " +
                            "identificaciones."
            );
        }

        if (ids.size() > MAX_EMPLEADOS) {

            return error(
                    HttpStatus.BAD_REQUEST,
                    "Máximo " + MAX_EMPLEADOS +
                            " empleados por petición."
            );
        }

        for (String id : ids) {

            if (id.length() > 30) {

                return error(
                        HttpStatus.BAD_REQUEST,
                        "La identificación supera " +
                                "los 30 caracteres permitidos."
                );
            }
        }

        // ======================================
        // EJECUTAR CONSULTA
        // ======================================

        try {

            List<String> identificaciones =
                    new ArrayList<>(ids);

            List<Map<String, Object>> resultados;

            if (consolidar) {

                resultados =
                        nominaService
                                .consultarNominaConsolidada(
                                        empresa,
                                        identificaciones
                                );

            } else {

                resultados =
                        nominaService.consultarEmpleados(
                                empresa,
                                identificaciones
                        );
            }

            // ==================================
            // CONTADORES
            // ==================================

            int encontrados = 0;
            int sinInformacion = 0;
            int errores = 0;
            int nominasCompletas = 0;
            int nominasIncompletas = 0;

            for (Map<String, Object> item : resultados) {

                String estado = String.valueOf(
                        item.get("estado"));

                if ("ENCONTRADO".equals(estado)) {

                    encontrados++;

                    if (consolidar) {

                        boolean completa =
                                Boolean.TRUE.equals(
                                        item.get(
                                                "nominaMensualCompleta")
                                );

                        if (completa) {
                            nominasCompletas++;
                        } else {
                            nominasIncompletas++;
                        }
                    }

                } else if ("ERROR_CONSULTA".equals(estado)) {

                    errores++;

                } else if ("SIN_INFORMACION".equals(estado)) {

                    sinInformacion++;

                } else if ("CONSULTA_INCOMPLETA".equals(estado)) {

                    errores++;
                }
            }

            // ==================================
            // RESPUESTA
            // ==================================

            Map<String, Object> respuesta =
                    new LinkedHashMap<>();

            respuesta.put("status", 200);

            respuesta.put(
                    "tipoConsulta",
                    consolidar
                            ? "NOMINA_CONSOLIDADA"
                            : "ULTIMA_NOMINA"
            );

            respuesta.put(
                    "empresaConsultada",
                    empresa == null || empresa.isEmpty()
                            ? "TODAS"
                            : empresa
            );

            respuesta.put(
                    "totalSolicitados",
                    ids.size()
            );

            respuesta.put(
                    "totalCoincidencias",
                    encontrados
            );

            respuesta.put(
                    "totalSinInformacion",
                    sinInformacion
            );

            respuesta.put(
                    "totalErrores",
                    errores
            );

            if (consolidar) {

                respuesta.put(
                        "totalNominasCompletas",
                        nominasCompletas
                );

                respuesta.put(
                        "totalNominasIncompletas",
                        nominasIncompletas
                );
            }

            respuesta.put("resultados", resultados);

            return ResponseEntity.ok(respuesta);

        } catch (IllegalArgumentException e) {

            return error(
                    HttpStatus.BAD_REQUEST,
                    e.getMessage()
            );

        } catch (Exception e) {

            System.err.println(
                    "[NOMINA] Error general en consulta."
            );

            e.printStackTrace();

            return error(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Error interno consultando nómina."
            );
        }
    }

    // ==========================================
    // RESPUESTAS DE ERROR
    // ==========================================

    private ResponseEntity<Map<String, Object>> error(
            HttpStatus estado,
            String mensaje) {

        Map<String, Object> respuesta =
                new LinkedHashMap<>();

        respuesta.put(
                "status",
                estado.value()
        );

        respuesta.put(
                "mensaje",
                mensaje
        );

        return ResponseEntity
                .status(estado)
                .body(respuesta);
    }
}
