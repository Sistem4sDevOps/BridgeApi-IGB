package com.IGB.BridgeApi.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class FemprobienCreditoHistoricoService {

    private final JdbcTemplate jdbcTemplate;


    public FemprobienCreditoHistoricoService(
            @Qualifier("sqlServerJdbcTemplate")
            JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate =
                jdbcTemplate;
    }


    /* =========================================================
       CONSULTAR HISTÓRICO DE CRÉDITOS POR CÉDULA

       FUENTE PRINCIPAL:
       FEMPROBIEN.dbo.tblAporteCredito

       tblCredito NO es obligatoria para esta consulta.

       GET:
       /femprobien/creditos/historico/asociado/{cedula}
       ========================================================= */
    public Map<String, Object> consultarHistoricoPorCedula(
            String cedula) {

        String documento =
                limpiarTexto(
                        cedula
                );


        if (
                documento == null
        ) {

            throw new IllegalArgumentException(
                    "El número de documento es obligatorio."
            );
        }


        String sql =
                "SELECT " +

                        "ap.id, " +
                        "ap.cod_asp, " +
                        "ap.nom_aso, " +

                        "ap.val_desm, " +
                        "ap.fecha_desem, " +
                        "ap.inicio_dcto, " +
                        "ap.fec_fin, " +

                        "ap.plazo, " +
                        "ap.valor_cuota, " +
                        "ap.valor_pagar, " +

                        "ap.activo, " +
                        "ap.fecha_retiro " +

                        "FROM FEMPROBIEN.dbo.tblAporteCredito ap " +

                        "WHERE LTRIM(RTRIM(CAST(ap.cod_asp AS VARCHAR(100)))) = ? " +

                        "ORDER BY " +
                        "CASE " +
                        "   WHEN ap.fecha_desem IS NOT NULL THEN ap.fecha_desem " +
                        "   ELSE ap.inicio_dcto " +
                        "END DESC, " +
                        "ap.id DESC";


        List<Map<String, Object>> creditos =
                jdbcTemplate.queryForList(
                        sql,
                        documento
                );


        Map<String, Object> response =
                new HashMap<String, Object>();


        response.put(
                "documento",
                documento
        );


        response.put(
                "cantidad",
                creditos.size()
        );


        response.put(
                "encontrado",
                !creditos.isEmpty()
        );


        if (
                creditos.isEmpty()
        ) {

            response.put(
                    "nombre",
                    null
            );


            response.put(
                    "creditos",
                    new ArrayList<Map<String, Object>>()
            );


            response.put(
                    "message",
                    "No se encontraron créditos para el documento consultado."
            );


            return response;
        }


        Map<String, Object> primerCredito =
                creditos.get(0);


        response.put(
                "nombre",
                primerCredito.get(
                        "nom_aso"
                )
        );


        response.put(
                "creditos",
                creditos
        );


        return response;
    }


    /* =========================================================
       CONSULTAR UN CRÉDITO POR ID

       También se consulta DIRECTAMENTE desde tblAporteCredito.
       No depende de tblCredito.
       ========================================================= */
    public Map<String, Object> consultarCreditoPorId(
            Integer id) {

        if (
                id == null ||
                        id <= 0
        ) {

            throw new IllegalArgumentException(
                    "El id del crédito es obligatorio."
            );
        }


        List<Map<String, Object>> resultado =
                jdbcTemplate.queryForList(
                        "SELECT " +

                                "ap.id, " +
                                "ap.cod_asp, " +
                                "ap.nom_aso, " +

                                "ap.val_desm, " +
                                "ap.fecha_desem, " +
                                "ap.inicio_dcto, " +
                                "ap.fec_fin, " +

                                "ap.plazo, " +
                                "ap.valor_cuota, " +
                                "ap.valor_pagar, " +

                                "ap.activo, " +
                                "ap.fecha_retiro " +

                                "FROM FEMPROBIEN.dbo.tblAporteCredito ap " +

                                "WHERE ap.id = ?",
                        id
                );


        if (
                resultado == null ||
                        resultado.isEmpty()
        ) {

            throw new IllegalArgumentException(
                    "No se encontró el crédito #"
                            + id
                            + "."
            );
        }


        return resultado.get(0);
    }

    private String limpiarTexto(
            String value) {

        if (
                value == null
        ) {

            return null;
        }


        String text =
                value.trim();


        return text.isEmpty()
                ? null
                : text;
    }
}
