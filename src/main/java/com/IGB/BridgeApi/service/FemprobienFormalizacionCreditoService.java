package com.IGB.BridgeApi.service;

import com.IGB.BridgeApi.dto.FormalizarCreditoDTO;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class FemprobienFormalizacionCreditoService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;


    public FemprobienFormalizacionCreditoService(
            @Qualifier("sqlServerJdbcTemplate")
            JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate =
                jdbcTemplate;


        DataSource dataSource =
                jdbcTemplate.getDataSource();


        if (
                dataSource == null
        ) {

            throw new IllegalStateException(
                    "No se encontró el DataSource de SQL Server."
            );
        }


        this.transactionTemplate =
                new TransactionTemplate(
                        new DataSourceTransactionManager(
                                dataSource
                        )
                );
    }

    public synchronized Map<String, Object> formalizarCredito(
            final Integer numeroSolicitud,
            final FormalizarCreditoDTO dto) {

        validarNumeroSolicitud(
                numeroSolicitud
        );

        validarDTO(
                dto
        );


        return transactionTemplate.execute(
                status -> {

                    Map<String, Object> solicitud =
                            obtenerSolicitudAprobada(
                                    numeroSolicitud
                            );


                    Integer idSolicitud =
                            toInteger(
                                    solicitud.get(
                                            "id"
                                    )
                            );


                    Integer idAso =
                            toInteger(
                                    solicitud.get(
                                            "id_aso"
                                    )
                            );


                    validarNoFormalizado(
                            numeroSolicitud,
                            idSolicitud
                    );


                    Map<String, Object> asociado =
                            obtenerAsociado(
                                    idAso
                            );


                    if (
                            asociado == null
                    ) {

                        throw new IllegalArgumentException(
                                "No se encontró el asociado relacionado con la solicitud #"
                                        + numeroSolicitud
                                        + "."
                        );
                    }


                    String codAsp =
                            toStringValue(
                                    asociado.get(
                                            "cod_asp"
                                    )
                            );


                    String nomAso =
                            toStringValue(
                                    asociado.get(
                                            "nom_aso"
                                    )
                            );


                    Object asociadoActivo =
                            asociado.get(
                                    "activo"
                            );


                    Object fechaRetiro =
                            asociado.get(
                                    "fecha_retiro"
                            );


                    BigDecimal montoSolicitado =
                            toBigDecimal(
                                    solicitud.get(
                                            "monto_solicitado"
                                    )
                            );


                    Integer plazoSolicitado =
                            toInteger(
                                    solicitud.get(
                                            "plazo_meses"
                                    )
                            );


                    Object fechaAprobacion =
                            solicitud.get(
                                    "fecha_estudio"
                            );


                    LocalDate fechaPrimeraCuota =
                            LocalDate.parse(
                                    dto.getFechaPrimeraCuota()
                                            .trim()
                            );


                    LocalDate fechaFin =
                            fechaPrimeraCuota.plusMonths(
                                    dto.getPlazoAprobado()
                            );


                    String periodicidad =
                            normalizarPeriodicidad(
                                    dto.getPeriodicidad()
                            );


                    String formaDescuento =
                            obtenerFormaDescuento(
                                    solicitud
                            );


                    Integer idCredito =
                            insertarTblCredito(
                                    idSolicitud,
                                    numeroSolicitud,
                                    idAso,

                                    montoSolicitado,
                                    dto.getMontoAprobado(),

                                    plazoSolicitado,
                                    dto.getPlazoAprobado(),
                                    dto.getNumeroCuotas(),

                                    dto.getValorCuota(),

                                    fechaAprobacion,
                                    fechaPrimeraCuota,

                                    periodicidad,
                                    formaDescuento,

                                    dto.getObservacion(),
                                    dto.getUsuario()
                            );


                    insertarTblAporteCredito(
                            idCredito,
                            idSolicitud,
                            numeroSolicitud,

                            codAsp,
                            nomAso,

                            dto,
                            fechaPrimeraCuota,
                            fechaFin,

                            periodicidad,
                            formaDescuento,

                            asociadoActivo,
                            fechaRetiro
                    );


                    Map<String, Object> credito =
                            obtenerCreditoFormalizado(
                                    numeroSolicitud
                            );


                    Map<String, Object> response =
                            new HashMap<String, Object>();


                    response.put(
                            "status",
                            201
                    );


                    response.put(
                            "message",
                            "Crédito formalizado correctamente."
                    );


                    response.put(
                            "formalizado",
                            true
                    );


                    response.put(
                            "credito",
                            credito
                    );


                    return response;
                }
        );
    }


    private Integer insertarTblCredito(
            Integer idSolicitud,
            Integer numeroSolicitud,
            Integer idAso,

            BigDecimal montoSolicitado,
            BigDecimal montoAprobado,

            Integer plazoSolicitado,
            Integer plazoAprobado,
            Integer numeroCuotas,

            BigDecimal valorCuota,

            Object fechaAprobacion,
            LocalDate fechaPrimeraCuota,

            String periodicidad,
            String formaDescuento,

            String observacion,
            String usuario) {


        String sql =
                "INSERT INTO FEMPROBIEN.dbo.tblCredito (" +

                        "id_solicitud, " +
                        "numero_solicitud, " +
                        "id_aso, " +

                        "monto_solicitado, " +
                        "monto_aprobado, " +

                        "plazo_solicitado, " +
                        "plazo_aprobado, " +
                        "numero_cuotas, " +

                        "valor_cuota, " +

                        "fecha_aprobacion, " +
                        "fecha_primera_cuota, " +

                        "periodicidad, " +
                        "forma_descuento, " +

                        "saldo_inicial, " +
                        "saldo_actual, " +

                        "estado, " +

                        "observacion, " +
                        "usuario_formaliza, " +

                        "fecha_formalizacion, " +
                        "fecha_creacion" +

                        ") " +

                        "OUTPUT INSERTED.id_credito " +

                        "VALUES (" +
                        "?, ?, ?, " +
                        "?, ?, " +
                        "?, ?, ?, " +
                        "?, " +
                        "?, ?, " +
                        "?, ?, " +
                        "?, ?, " +
                        "?, " +
                        "?, ?, " +
                        "SYSDATETIME(), " +
                        "SYSDATETIME()" +
                        ")";


        Integer idCredito =
                jdbcTemplate.queryForObject(
                        sql,

                        new Object[]{
                                idSolicitud,
                                numeroSolicitud,
                                idAso,

                                montoSolicitado,
                                montoAprobado,

                                plazoSolicitado,
                                plazoAprobado,
                                numeroCuotas,

                                valorCuota,

                                fechaAprobacion,
                                Date.valueOf(
                                        fechaPrimeraCuota
                                ),

                                periodicidad,
                                formaDescuento,

                                montoAprobado,
                                montoAprobado,

                                "ACTIVO",

                                limpiarTexto(
                                        observacion
                                ),

                                limpiarTexto(
                                        usuario
                                )
                        },

                        Integer.class
                );


        if (
                idCredito == null ||
                        idCredito <= 0
        ) {

            throw new IllegalStateException(
                    "No fue posible obtener el id del crédito formalizado."
            );
        }


        return idCredito;
    }


    private void insertarTblAporteCredito(
            Integer idCredito,
            Integer idSolicitud,
            Integer numeroSolicitud,

            String codAsp,
            String nomAso,

            FormalizarCreditoDTO dto,

            LocalDate fechaPrimeraCuota,
            LocalDate fechaFin,

            String periodicidad,
            String formaDescuento,

            Object asociadoActivo,
            Object fechaRetiro) {


        String sql =
                "INSERT INTO FEMPROBIEN.dbo.tblAporteCredito (" +

                        "id_credito, " +
                        "id_solicitud, " +
                        "numero_solicitud, " +

                        "cod_asp, " +
                        "nom_aso, " +

                        "val_desm, " +
                        "fecha_desem, " +

                        "inicio_dcto, " +
                        "fec_fin, " +

                        "plazo, " +
                        "numero_cuotas, " +
                        "periodicidad, " +
                        "forma_descuento, " +

                        "valor_cuota, " +

                        "total_pagado, " +
                        "valor_pagar, " +

                        "cuotas_pagadas, " +
                        "cuotas_pendientes, " +

                        "fecha_ultimo_pago, " +
                        "valor_ultimo_pago, " +
                        "fecha_proxima_cuota, " +

                        "estado, " +

                        "activo, " +
                        "fecha_retiro, " +

                        "observacion, " +
                        "usuario_formaliza, " +
                        "fecha_formalizacion, " +
                        "fecha_actualizacion" +

                        ") VALUES (" +

                        "?, ?, ?, " +

                        "?, ?, " +

                        "?, NULL, " +

                        "?, ?, " +

                        "?, ?, ?, ?, " +

                        "?, " +

                        "?, ?, " +

                        "?, ?, " +

                        "NULL, NULL, ?, " +

                        "?, " +

                        "?, ?, " +

                        "?, ?, SYSDATETIME(), NULL" +

                        ")";


        jdbcTemplate.update(
                sql,

                idCredito,
                idSolicitud,
                numeroSolicitud,

                codAsp,
                nomAso,

                dto.getMontoAprobado(),

                Date.valueOf(
                        fechaPrimeraCuota
                ),

                Date.valueOf(
                        fechaFin
                ),

                dto.getPlazoAprobado(),
                dto.getNumeroCuotas(),
                periodicidad,
                formaDescuento,

                dto.getValorCuota(),

                BigDecimal.ZERO,
                dto.getMontoAprobado(),

                0,
                dto.getNumeroCuotas(),

                Date.valueOf(
                        fechaPrimeraCuota
                ),

                "ACTIVO",

                asociadoActivo,
                fechaRetiro,

                limpiarTexto(
                        dto.getObservacion()
                ),

                limpiarTexto(
                        dto.getUsuario()
                )
        );
    }


    public Map<String, Object> consultarFormalizacion(
            Integer numeroSolicitud) {

        validarNumeroSolicitud(
                numeroSolicitud
        );


        List<Map<String, Object>> resultado =
                jdbcTemplate.queryForList(
                        sqlConsultaCreditoFormalizado() +
                                " WHERE c.numero_solicitud = ? " +
                                " ORDER BY c.id_credito DESC",
                        numeroSolicitud
                );


        Map<String, Object> response =
                new HashMap<String, Object>();


        response.put(
                "numero_solicitud",
                numeroSolicitud
        );


        if (
                resultado == null ||
                        resultado.isEmpty()
        ) {

            response.put(
                    "formalizado",
                    false
            );

            return response;
        }


        response.put(
                "formalizado",
                true
        );


        response.put(
                "credito",
                resultado.get(0)
        );


        return response;
    }


    private Map<String, Object> obtenerCreditoFormalizado(
            Integer numeroSolicitud) {

        List<Map<String, Object>> resultado =
                jdbcTemplate.queryForList(
                        sqlConsultaCreditoFormalizado() +
                                " WHERE c.numero_solicitud = ? " +
                                " ORDER BY c.id_credito DESC",
                        numeroSolicitud
                );


        if (
                resultado == null ||
                        resultado.isEmpty()
        ) {

            throw new IllegalStateException(
                    "El crédito fue formalizado, pero no fue posible consultarlo."
            );
        }


        return resultado.get(0);
    }


    private String sqlConsultaCreditoFormalizado() {

        return
                "SELECT " +

                        "c.id_credito, " +
                        "c.id_solicitud, " +
                        "c.numero_solicitud, " +
                        "c.id_aso, " +

                        "a.cod_asp, " +
                        "a.nom_aso, " +

                        "c.monto_solicitado, " +
                        "c.monto_aprobado, " +

                        "c.plazo_solicitado, " +
                        "c.plazo_aprobado, " +
                        "c.numero_cuotas, " +

                        "c.valor_cuota, " +

                        "c.fecha_aprobacion, " +
                        "c.fecha_formalizacion, " +
                        "c.fecha_primera_cuota, " +

                        "c.periodicidad, " +
                        "c.forma_descuento, " +

                        "c.saldo_inicial, " +

                        "COALESCE(ap.valor_pagar, c.saldo_actual) AS saldo_actual, " +
                        "COALESCE(ap.total_pagado, 0) AS total_pagado, " +

                        "COALESCE(ap.cuotas_pagadas, 0) AS cuotas_pagadas, " +
                        "COALESCE(ap.cuotas_pendientes, c.numero_cuotas) AS cuotas_pendientes, " +

                        "ap.fecha_ultimo_pago, " +
                        "ap.valor_ultimo_pago, " +
                        "ap.fecha_proxima_cuota, " +
                        "ap.fec_fin, " +

                        "COALESCE(ap.estado, c.estado) AS estado, " +

                        "c.observacion, " +
                        "c.usuario_formaliza, " +

                        "ap.id AS id_aporte_credito " +

                        "FROM FEMPROBIEN.dbo.tblCredito c " +

                        "INNER JOIN FEMPROBIEN.dbo.tblAsociado a " +
                        "ON a.id_aso = c.id_aso " +

                        "LEFT JOIN FEMPROBIEN.dbo.tblAporteCredito ap " +
                        "ON ap.id_credito = c.id_credito";
    }


    private Map<String, Object> obtenerSolicitudAprobada(
            Integer numeroSolicitud) {

        List<Map<String, Object>> resultado =
                jdbcTemplate.queryForList(
                        "SELECT TOP 1 ac.* " +
                                "FROM FEMPROBIEN.dbo.tblAsociadoCredito ac " +
                                "WHERE ac.numero_solicitud = ?",
                        numeroSolicitud
                );


        if (
                resultado == null ||
                        resultado.isEmpty()
        ) {

            throw new IllegalArgumentException(
                    "No se encontró la solicitud de crédito #"
                            + numeroSolicitud
                            + "."
            );
        }


        Map<String, Object> solicitud =
                resultado.get(0);


        String estado =
                toStringValue(
                        solicitud.get(
                                "estado_credito"
                        )
                )
                        .trim()
                        .toUpperCase();


        if (
                !"APROBADA".equals(
                        estado
                )
        ) {

            throw new IllegalArgumentException(
                    "Solo se pueden formalizar solicitudes en estado APROBADA."
            );
        }


        return solicitud;
    }


    /* =========================================================
       ASOCIADO
       ========================================================= */
    private Map<String, Object> obtenerAsociado(
            Integer idAso) {

        if (
                idAso == null
        ) {

            return null;
        }


        List<Map<String, Object>> resultado =
                jdbcTemplate.queryForList(
                        "SELECT TOP 1 " +
                                "id_aso, " +
                                "cod_asp, " +
                                "nom_aso, " +
                                "activo, " +
                                "fecha_retiro " +

                                "FROM FEMPROBIEN.dbo.tblAsociado " +

                                "WHERE id_aso = ?",
                        idAso
                );


        if (
                resultado == null ||
                        resultado.isEmpty()
        ) {

            return null;
        }


        return resultado.get(0);
    }


    private void validarNoFormalizado(
            Integer numeroSolicitud,
            Integer idSolicitud) {

        Integer cantidadCredito =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) " +
                                "FROM FEMPROBIEN.dbo.tblCredito " +
                                "WHERE numero_solicitud = ? " +
                                "OR id_solicitud = ?",

                        new Object[]{
                                numeroSolicitud,
                                idSolicitud
                        },

                        Integer.class
                );


        if (
                cantidadCredito != null &&
                        cantidadCredito > 0
        ) {

            throw new IllegalArgumentException(
                    "La solicitud de crédito #"
                            + numeroSolicitud
                            + " ya fue formalizada."
            );
        }


        Integer cantidadOperativa =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) " +
                                "FROM FEMPROBIEN.dbo.tblAporteCredito " +
                                "WHERE numero_solicitud = ? " +
                                "OR id_solicitud = ?",

                        new Object[]{
                                numeroSolicitud,
                                idSolicitud
                        },

                        Integer.class
                );


        if (
                cantidadOperativa != null &&
                        cantidadOperativa > 0
        ) {

            throw new IllegalArgumentException(
                    "La solicitud de crédito #"
                            + numeroSolicitud
                            + " ya tiene un registro en tblAporteCredito."
            );
        }
    }


    private void validarNumeroSolicitud(
            Integer numeroSolicitud) {

        if (
                numeroSolicitud == null ||
                        numeroSolicitud <= 0
        ) {

            throw new IllegalArgumentException(
                    "El número de solicitud es obligatorio."
            );
        }
    }


    private void validarDTO(
            FormalizarCreditoDTO dto) {

        if (
                dto == null
        ) {

            throw new IllegalArgumentException(
                    "No se recibió información para formalizar el crédito."
            );
        }


        if (
                dto.getMontoAprobado() == null ||
                        dto.getMontoAprobado()
                                .compareTo(
                                        BigDecimal.ZERO
                                ) <= 0
        ) {

            throw new IllegalArgumentException(
                    "El monto aprobado debe ser mayor que cero."
            );
        }


        if (
                dto.getPlazoAprobado() == null ||
                        dto.getPlazoAprobado() <= 0
        ) {

            throw new IllegalArgumentException(
                    "El plazo aprobado debe ser mayor que cero."
            );
        }


        if (
                dto.getNumeroCuotas() == null ||
                        dto.getNumeroCuotas() <= 0
        ) {

            throw new IllegalArgumentException(
                    "El número de cuotas debe ser mayor que cero."
            );
        }


        if (
                dto.getValorCuota() == null ||
                        dto.getValorCuota()
                                .compareTo(
                                        BigDecimal.ZERO
                                ) <= 0
        ) {

            throw new IllegalArgumentException(
                    "El valor de la cuota debe ser mayor que cero."
            );
        }


        if (
                dto.getFechaPrimeraCuota() == null ||
                        dto.getFechaPrimeraCuota()
                                .trim()
                                .isEmpty()
        ) {

            throw new IllegalArgumentException(
                    "La fecha de la primera cuota es obligatoria."
            );
        }


        try {

            LocalDate.parse(
                    dto.getFechaPrimeraCuota()
                            .trim()
            );

        } catch (Exception e) {

            throw new IllegalArgumentException(
                    "La fecha de la primera cuota debe tener formato yyyy-MM-dd."
            );
        }


        normalizarPeriodicidad(
                dto.getPeriodicidad()
        );
    }


    private String obtenerFormaDescuento(
            Map<String, Object> solicitud) {

        if (
                toBoolean(
                        solicitud.get(
                                "descuento_ambas_quincenas"
                        )
                )
        ) {

            return "AMBAS_QUINCENAS";
        }


        if (
                toBoolean(
                        solicitud.get(
                                "descuento_primera_quincena"
                        )
                )
        ) {

            return "QUINCENA_1";
        }


        if (
                toBoolean(
                        solicitud.get(
                                "descuento_segunda_quincena"
                        )
                )
        ) {

            return "QUINCENA_2";
        }


        return "NO_DEFINIDA";
    }


    private String normalizarPeriodicidad(
            String value) {

        String periodicidad =
                value == null
                        ? "QUINCENAL"
                        : value
                        .trim()
                        .toUpperCase();


        if (
                periodicidad.isEmpty()
        ) {

            periodicidad =
                    "QUINCENAL";
        }


        if (
                !"QUINCENAL".equals(
                        periodicidad
                ) &&
                        !"MENSUAL".equals(
                                periodicidad
                        )
        ) {

            throw new IllegalArgumentException(
                    "Periodicidad no permitida. Use QUINCENAL o MENSUAL."
            );
        }


        return periodicidad;
    }


    private Integer toInteger(
            Object value) {

        if (
                value == null ||
                        value.toString()
                                .trim()
                                .isEmpty()
        ) {

            return null;
        }


        if (
                value instanceof Integer
        ) {

            return (Integer) value;
        }


        if (
                value instanceof Number
        ) {

            return ((Number) value)
                    .intValue();
        }


        return Integer.valueOf(
                value.toString()
        );
    }


    private BigDecimal toBigDecimal(
            Object value) {

        if (
                value == null
        ) {

            return BigDecimal.ZERO;
        }


        if (
                value instanceof BigDecimal
        ) {

            return (BigDecimal) value;
        }


        return new BigDecimal(
                value.toString()
        );
    }


    private boolean toBoolean(
            Object value) {

        if (
                value == null
        ) {

            return false;
        }


        if (
                value instanceof Boolean
        ) {

            return (Boolean) value;
        }


        if (
                value instanceof Number
        ) {

            return ((Number) value)
                    .intValue() != 0;
        }


        String text =
                value.toString()
                        .trim();


        return "true".equalsIgnoreCase(
                text
        )
                || "1".equals(
                text
        )
                || "si".equalsIgnoreCase(
                text
        )
                || "sí".equalsIgnoreCase(
                text
        )
                || "y".equalsIgnoreCase(
                text
        );
    }


    private String toStringValue(
            Object value) {

        if (
                value == null
        ) {

            return "";
        }


        return value
                .toString();
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
