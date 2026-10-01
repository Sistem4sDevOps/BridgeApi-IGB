package com.IGB.BridgeApi.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class FemprobienMisSolicitudesService {

    private final JdbcTemplate jdbcTemplate;

    public FemprobienMisSolicitudesService(
            @Qualifier("sqlServerJdbcTemplate") JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate = jdbcTemplate;
    }


    /* =========================================================
       CONSULTAR MIS SOLICITUDES

       Incluye:
       - Solicitudes de crédito.
       - Solicitudes de afiliación.
       - Solicitudes de actualización de datos.

       La identidad se valida con:
       - Número de documento.
       - Fecha de nacimiento.

       IMPORTANTE:
       Una persona que solicita afiliación todavía puede no existir
       en tblAsociado. Por eso la validación puede realizarse también
       contra tblSolicitudAfiliacion.
       ========================================================= */
    public Map<String, Object> consultarSolicitudes(
            String codAsp,
            String fechaNacimiento) {

        Map<String, Object> identidad =
                validarIdentidad(
                        codAsp,
                        fechaNacimiento
                );

        String documento =
                texto(
                        identidad.get("cod_asp")
                );

        List<Map<String, Object>> solicitudes =
                new ArrayList<Map<String, Object>>();


        /* =====================================================
           1. SOLICITUDES DE CRÉDITO
           ===================================================== */

        Object idAso =
                identidad.get("id_aso");

        if (idAso != null) {

            List<Map<String, Object>> creditos =
                    jdbcTemplate.queryForList(
                            "SELECT " +
                                    "ac.id, " +
                                    "ac.numero_solicitud, " +
                                    "ac.fecha_solicitud, " +
                                    "ac.monto_solicitado, " +
                                    "ac.plazo_meses, " +
                                    "ac.destino_credito, " +
                                    "ac.estado_credito, " +
                                    "ac.fecha_actualizacion, " +
                                    "ac.fecha_estudio " +
                                    "FROM FEMPROBIEN.dbo.tblAsociadoCredito ac " +
                                    "WHERE ac.id_aso = ?",
                            idAso
                    );

            for (Map<String, Object> credito : creditos) {

                Map<String, Object> item =
                        new LinkedHashMap<String, Object>();

                item.put(
                        "id",
                        credito.get("id")
                );

                item.put(
                        "tipo",
                        "CREDITO"
                );

                item.put(
                        "numero_solicitud",
                        credito.get("numero_solicitud")
                );

                item.put(
                        "titulo",
                        "Solicitud de crédito"
                );

                item.put(
                        "fecha_solicitud",
                        credito.get("fecha_solicitud")
                );

                item.put(
                        "fecha_actualizacion",
                        credito.get("fecha_actualizacion")
                );

                item.put(
                        "estado",
                        credito.get("estado_credito")
                );

                item.put(
                        "monto_solicitado",
                        credito.get("monto_solicitado")
                );

                item.put(
                        "plazo_meses",
                        credito.get("plazo_meses")
                );

                item.put(
                        "destino_credito",
                        credito.get("destino_credito")
                );

                item.put(
                        "fecha_estudio",
                        credito.get("fecha_estudio")
                );

                item.put(
                        "cantidad_cambios",
                        0
                );

                solicitudes.add(
                        item
                );
            }
        }


        /* =====================================================
           2. AFILIACIÓN / ACTUALIZACIÓN DE DATOS
           ===================================================== */

        List<Map<String, Object>> solicitudesAfiliacion =
                jdbcTemplate.queryForList(
                        "SELECT " +
                                "sa.id, " +
                                "sa.tipo_solicitud, " +
                                "sa.fecha_solicitud, " +
                                "sa.fecha_respuesta, " +
                                "sa.estado, " +
                                "sa.cod_asp, " +
                                "sa.nombres, " +
                                "sa.primer_apellido, " +
                                "sa.segundo_apellido, " +
                                "(SELECT COUNT(1) " +
                                " FROM FEMPROBIEN.dbo.tblSolicitudAfiliacionCambio c " +
                                " WHERE c.id_solicitud = sa.id) AS cantidad_cambios " +
                                "FROM FEMPROBIEN.dbo.tblSolicitudAfiliacion sa " +
                                "WHERE LTRIM(RTRIM(CAST(sa.cod_asp AS VARCHAR(100)))) = ?",
                        documento
                );

        for (Map<String, Object> solicitud : solicitudesAfiliacion) {

            String tipo =
                    normalizarTipoSolicitud(
                            solicitud.get("tipo_solicitud")
                    );

            Map<String, Object> item =
                    new LinkedHashMap<String, Object>();

            item.put(
                    "id",
                    solicitud.get("id")
            );

            item.put(
                    "tipo",
                    tipo
            );

            item.put(
                    "numero_solicitud",
                    solicitud.get("id")
            );

            item.put(
                    "titulo",
                    "ACTUALIZACION_DATOS".equals(tipo)
                            ? "Actualización de datos"
                            : "Solicitud de afiliación"
            );

            item.put(
                    "fecha_solicitud",
                    solicitud.get("fecha_solicitud")
            );

            item.put(
                    "fecha_actualizacion",
                    solicitud.get("fecha_respuesta")
            );

            item.put(
                    "estado",
                    solicitud.get("estado")
            );

            item.put(
                    "cantidad_cambios",
                    solicitud.get("cantidad_cambios")
            );

            solicitudes.add(
                    item
            );
        }


        /* =====================================================
           3. ORDENAR TODAS LAS SOLICITUDES POR FECHA
           ===================================================== */

        Collections.sort(
                solicitudes,
                new Comparator<Map<String, Object>>() {

                    @Override
                    public int compare(
                            Map<String, Object> a,
                            Map<String, Object> b) {

                        long fechaA =
                                obtenerMilisegundos(
                                        a.get("fecha_solicitud")
                                );

                        long fechaB =
                                obtenerMilisegundos(
                                        b.get("fecha_solicitud")
                                );

                        if (fechaA == fechaB) {

                            long idA =
                                    obtenerNumero(
                                            a.get("numero_solicitud")
                                    );

                            long idB =
                                    obtenerNumero(
                                            b.get("numero_solicitud")
                                    );

                            return idA < idB
                                    ? 1
                                    : idA == idB
                                    ? 0
                                    : -1;
                        }

                        return fechaA < fechaB
                                ? 1
                                : -1;
                    }
                }
        );


        /* =====================================================
           4. RESPUESTA
           ===================================================== */

        int totalCreditos = 0;
        int totalAfiliaciones = 0;
        int totalActualizaciones = 0;

        for (Map<String, Object> solicitud : solicitudes) {

            String tipo =
                    texto(
                            solicitud.get("tipo")
                    );

            if ("CREDITO".equals(tipo)) {
                totalCreditos++;
            }

            if ("AFILIACION".equals(tipo)) {
                totalAfiliaciones++;
            }

            if ("ACTUALIZACION_DATOS".equals(tipo)) {
                totalActualizaciones++;
            }
        }

        Map<String, Object> response =
                new HashMap<String, Object>();

        response.put(
                "documento",
                documento
        );

        response.put(
                "nombre",
                identidad.get("nom_aso")
        );

        response.put(
                "solicitudes",
                solicitudes
        );

        response.put(
                "total",
                solicitudes.size()
        );

        response.put(
                "total_creditos",
                totalCreditos
        );

        response.put(
                "total_afiliaciones",
                totalAfiliaciones
        );

        response.put(
                "total_actualizaciones",
                totalActualizaciones
        );

        return response;
    }


    /* =========================================================
       CONSULTAR SEGUIMIENTO DE UNA SOLICITUD DE CRÉDITO
       ========================================================= */
    public List<Map<String, Object>> consultarHistorialCredito(
            Integer numeroSolicitud,
            String codAsp,
            String fechaNacimiento) {

        if (numeroSolicitud == null || numeroSolicitud <= 0) {

            throw new IllegalArgumentException(
                    "El número de solicitud es obligatorio."
            );
        }

        Map<String, Object> identidad =
                validarIdentidad(
                        codAsp,
                        fechaNacimiento
                );

        Object idAso =
                identidad.get("id_aso");

        if (idAso == null) {

            throw new IllegalArgumentException(
                    "No se encontró una solicitud de crédito para los datos ingresados."
            );
        }

        List<Map<String, Object>> solicitudes =
                jdbcTemplate.queryForList(
                        "SELECT TOP 1 ac.id " +
                                "FROM FEMPROBIEN.dbo.tblAsociadoCredito ac " +
                                "WHERE ac.numero_solicitud = ? " +
                                "AND ac.id_aso = ?",
                        numeroSolicitud,
                        idAso
                );

        if (solicitudes.isEmpty()) {

            throw new IllegalArgumentException(
                    "No se encontró la solicitud de crédito para el asociado validado."
            );
        }

        return jdbcTemplate.queryForList(
                "SELECT " +
                        "id_estado, " +
                        "estado, " +
                        "fecha_estado, " +
                        "comentario, " +
                        "fecha_creacion " +
                        "FROM FEMPROBIEN.dbo.tblEstadoCredito " +
                        "WHERE id_solicitud = ? " +
                        "ORDER BY fecha_estado ASC, id_estado ASC",
                solicitudes.get(0).get("id")
        );
    }


    /* =========================================================
       CONSULTAR SEGUIMIENTO DE AFILIACIÓN / ACTUALIZACIÓN

       No existe actualmente una tabla histórica equivalente a
       tblEstadoCredito para estas solicitudes.

       Por eso se construye el seguimiento con la información que
       sí existe hoy:
       - fecha_solicitud -> PENDIENTE
       - fecha_respuesta -> estado final, cuando aplique

       No se inventan estados intermedios.
       ========================================================= */
    public List<Map<String, Object>> consultarHistorialAfiliacion(
            Integer idSolicitud,
            String codAsp,
            String fechaNacimiento) {

        if (idSolicitud == null || idSolicitud <= 0) {

            throw new IllegalArgumentException(
                    "El número de solicitud es obligatorio."
            );
        }

        Map<String, Object> identidad =
                validarIdentidad(
                        codAsp,
                        fechaNacimiento
                );

        String documento =
                texto(
                        identidad.get("cod_asp")
                );

        List<Map<String, Object>> solicitudes =
                jdbcTemplate.queryForList(
                        "SELECT TOP 1 " +
                                "id, " +
                                "tipo_solicitud, " +
                                "estado, " +
                                "fecha_solicitud, " +
                                "fecha_respuesta " +
                                "FROM FEMPROBIEN.dbo.tblSolicitudAfiliacion " +
                                "WHERE id = ? " +
                                "AND LTRIM(RTRIM(CAST(cod_asp AS VARCHAR(100)))) = ?",
                        idSolicitud,
                        documento
                );

        if (solicitudes.isEmpty()) {

            throw new IllegalArgumentException(
                    "No se encontró la solicitud para los datos ingresados."
            );
        }

        Map<String, Object> solicitud =
                solicitudes.get(0);

        String tipo =
                normalizarTipoSolicitud(
                        solicitud.get("tipo_solicitud")
                );

        String estadoActual =
                texto(
                        solicitud.get("estado")
                )
                        .toUpperCase();

        List<Map<String, Object>> historial =
                new ArrayList<Map<String, Object>>();


        Map<String, Object> creada =
                new LinkedHashMap<String, Object>();

        creada.put(
                "estado",
                "PENDIENTE"
        );

        creada.put(
                "fecha_estado",
                solicitud.get("fecha_solicitud")
        );

        creada.put(
                "comentario",
                null
        );

        creada.put(
                "tipo_solicitud",
                tipo
        );

        historial.add(
                creada
        );


        if (!estadoActual.isEmpty()
                && !"PENDIENTE".equals(estadoActual)) {

            Map<String, Object> respuesta =
                    new LinkedHashMap<String, Object>();

            respuesta.put(
                    "estado",
                    estadoActual
            );

            respuesta.put(
                    "fecha_estado",
                    solicitud.get("fecha_respuesta") != null
                            ? solicitud.get("fecha_respuesta")
                            : solicitud.get("fecha_solicitud")
            );

            respuesta.put(
                    "comentario",
                    null
            );

            respuesta.put(
                    "tipo_solicitud",
                    tipo
            );

            historial.add(
                    respuesta
            );
        }

        return historial;
    }


    /* =========================================================
       VALIDAR IDENTIDAD

       1. Se intenta validar contra tblAsociado.
       2. Si todavía no es asociado, se valida contra una solicitud
          de afiliación/actualización existente.

       Esto permite que una persona pueda consultar el estado de su
       solicitud de afiliación antes de ser aprobada.
       ========================================================= */
    private Map<String, Object> validarIdentidad(
            String codAsp,
            String fechaNacimiento) {

        String documento =
                codAsp == null
                        ? ""
                        : codAsp.trim();

        String fecha =
                fechaNacimiento == null
                        ? ""
                        : fechaNacimiento.trim();

        if (documento.isEmpty() || fecha.isEmpty()) {

            throw new IllegalArgumentException(
                    "El número de documento y la fecha de nacimiento son obligatorios."
            );
        }


        List<Map<String, Object>> asociados =
                jdbcTemplate.queryForList(
                        "SELECT TOP 1 " +
                                "id_aso, cod_asp, nom_aso, fec_nac " +
                                "FROM FEMPROBIEN.dbo.tblAsociado " +
                                "WHERE LTRIM(RTRIM(CAST(cod_asp AS VARCHAR(100)))) = ? " +
                                "AND CONVERT(date, fec_nac) = CONVERT(date, ?)",
                        documento,
                        fecha
                );

        if (!asociados.isEmpty()) {

            return asociados.get(0);
        }


        List<Map<String, Object>> solicitudes =
                jdbcTemplate.queryForList(
                        "SELECT TOP 1 " +
                                "NULL AS id_aso, " +
                                "cod_asp, " +
                                "LTRIM(RTRIM(" +
                                "COALESCE(nombres, '') + ' ' + " +
                                "COALESCE(primer_apellido, '') + ' ' + " +
                                "COALESCE(segundo_apellido, ''))) AS nom_aso, " +
                                "fec_nac " +
                                "FROM FEMPROBIEN.dbo.tblSolicitudAfiliacion " +
                                "WHERE LTRIM(RTRIM(CAST(cod_asp AS VARCHAR(100)))) = ? " +
                                "AND CONVERT(date, fec_nac) = CONVERT(date, ?) " +
                                "ORDER BY fecha_solicitud DESC, id DESC",
                        documento,
                        fecha
                );

        if (!solicitudes.isEmpty()) {

            return solicitudes.get(0);
        }


        throw new IllegalArgumentException(
                "No se encontró información de FEMPROBIEN con los datos ingresados."
        );
    }


    /* =========================================================
       HELPERS
       ========================================================= */

    private String normalizarTipoSolicitud(
            Object tipoSolicitud) {

        String tipo =
                texto(
                        tipoSolicitud
                )
                        .toUpperCase();

        if ("ACTUALIZACION_DATOS".equals(tipo)) {
            return "ACTUALIZACION_DATOS";
        }

        return "AFILIACION";
    }


    private String texto(
            Object value) {

        return value == null
                ? ""
                : value.toString().trim();
    }


    private long obtenerMilisegundos(
            Object value) {

        if (value instanceof Date) {

            return ((Date) value)
                    .getTime();
        }

        return 0L;
    }


    private long obtenerNumero(
            Object value) {

        if (value == null) {
            return 0L;
        }

        if (value instanceof Number) {
            return ((Number) value)
                    .longValue();
        }

        try {

            return Long.parseLong(
                    value.toString()
            );

        } catch (Exception e) {

            return 0L;
        }
    }
}
