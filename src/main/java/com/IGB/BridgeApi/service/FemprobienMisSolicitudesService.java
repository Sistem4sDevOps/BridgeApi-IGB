package com.IGB.BridgeApi.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
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

       Primera etapa:
       - Solicitudes de crédito.

       La identidad del asociado se valida con:
       - Número de documento.
       - Fecha de nacimiento.

       Más adelante este mismo servicio puede agregar:
       - Afiliaciones.
       - Actualizaciones de datos.
       ========================================================= */
    public Map<String, Object> consultarSolicitudes(
            String codAsp,
            String fechaNacimiento) {

        Map<String, Object> asociado =
                validarIdentidad(
                        codAsp,
                        fechaNacimiento
                );

        List<Map<String, Object>> solicitudes =
                jdbcTemplate.queryForList(
                        "SELECT " +
                                "ac.numero_solicitud, " +
                                "ac.fecha_solicitud, " +
                                "ac.monto_solicitado, " +
                                "ac.plazo_meses, " +
                                "ac.destino_credito, " +
                                "ac.estado_credito, " +
                                "ac.fecha_actualizacion, " +
                                "ac.fecha_estudio " +
                                "FROM FEMPROBIEN.dbo.tblAsociadoCredito ac " +
                                "WHERE ac.id_aso = ? " +
                                "ORDER BY ac.fecha_solicitud DESC, ac.numero_solicitud DESC",
                        asociado.get("id_aso")
                );

        Map<String, Object> response =
                new HashMap<String, Object>();

        response.put(
                "documento",
                asociado.get("cod_asp")
        );

        response.put(
                "nombre",
                asociado.get("nom_aso")
        );

        /*
         * Se conserva una colección genérica llamada "solicitudes".
         * En esta primera etapa contiene únicamente créditos.
         * Esto permite que el frontend actual funcione sin mezclar
         * esta consulta con los controllers administrativos.
         */
        response.put(
                "solicitudes",
                solicitudes
        );

        return response;
    }

    /* =========================================================
       CONSULTAR SEGUIMIENTO DE UNA SOLICITUD DE CRÉDITO

       Antes de devolver el historial se valida:
       1. Documento + fecha de nacimiento.
       2. Que la solicitud realmente pertenezca al asociado.

       No se devuelve el usuario administrativo que realizó
       cada cambio de estado.
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

        Map<String, Object> asociado =
                validarIdentidad(
                        codAsp,
                        fechaNacimiento
                );

        List<Map<String, Object>> solicitudes =
                jdbcTemplate.queryForList(
                        "SELECT TOP 1 ac.id " +
                                "FROM FEMPROBIEN.dbo.tblAsociadoCredito ac " +
                                "WHERE ac.numero_solicitud = ? " +
                                "AND ac.id_aso = ?",
                        numeroSolicitud,
                        asociado.get("id_aso")
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
       VALIDAR IDENTIDAD DEL ASOCIADO
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

        List<Map<String, Object>> resultado =
                jdbcTemplate.queryForList(
                        "SELECT TOP 1 " +
                                "id_aso, cod_asp, nom_aso, fec_nac " +
                                "FROM FEMPROBIEN.dbo.tblAsociado " +
                                "WHERE LTRIM(RTRIM(CAST(cod_asp AS VARCHAR(100)))) = ? " +
                                "AND CONVERT(date, fec_nac) = CONVERT(date, ?)",
                        documento,
                        fecha
                );

        if (resultado.isEmpty()) {
            throw new IllegalArgumentException(
                    "No se encontró un asociado con los datos ingresados."
            );
        }

        return resultado.get(0);
    }
}
