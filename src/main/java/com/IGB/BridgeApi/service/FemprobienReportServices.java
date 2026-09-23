package com.IGB.BridgeApi.service;

import com.IGB.BridgeApi.util.ReportPathUtil;
import net.sf.jasperreports.engine.JREmptyDataSource;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.math.BigDecimal;
import java.sql.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class FemprobienReportServices {

    private final JdbcTemplate sqlServerJdbcTemplate;

    public FemprobienReportServices(
            @Qualifier("sqlServerJdbcTemplate")
            JdbcTemplate sqlServerJdbcTemplate) {

        this.sqlServerJdbcTemplate =
                sqlServerJdbcTemplate;
    }


    public Map<String, Object> generarEstadoCuenta(
            String codAsp) throws Exception {

        validarCodigoAsociado(codAsp);

        codAsp = codAsp.trim();

        Map<String, Object> datos =
                consultarDatosEstadoCuenta(codAsp);

        Map<String, Object> parametros =
                crearParametrosReporte(datos);

        String jrxmlPath =
                ReportPathUtil
                        .getAccountStatementJrxml();

        String jasperPath =
                ReportPathUtil
                        .getAccountStatementJasper();

        File jrxmlFile =
                new File(jrxmlPath);

        File jasperFile =
                new File(jasperPath);

        if (!jrxmlFile.exists()) {

            throw new Exception(
                    "No se encontró el archivo JRXML: "
                            + jrxmlPath
            );
        }

        if (!jasperFile.exists() ||
                jrxmlFile.lastModified()
                        > jasperFile.lastModified()) {

            System.out.println(
                    "Compilando reporte FEMPROBIEN: "
                            + jrxmlPath
            );

            JasperCompileManager
                    .compileReportToFile(
                            jrxmlPath,
                            jasperPath
                    );
        }

        JasperPrint jasperPrint =
                JasperFillManager.fillReport(
                        jasperPath,
                        parametros,
                        new JREmptyDataSource(1)
                );

        String pdfPath =
                ReportPathUtil
                        .getAccountStatementPdf(
                                codAsp
                        );

        System.out.println(
                "Generando estado de cuenta FEMPROBIEN en: "
                        + pdfPath
        );

        JasperExportManager
                .exportReportToPdfFile(
                        jasperPrint,
                        pdfPath
                );

        File pdf =
                new File(pdfPath);

        if (!pdf.exists()) {

            throw new Exception(
                    "Jasper finalizó pero no se encontró el PDF generado: "
                            + pdfPath
            );
        }

        Map<String, Object> respuesta =
                new HashMap<>();

        respuesta.put(
                "codAsp",
                codAsp
        );

        respuesta.put(
                "fileName",
                codAsp + ".pdf"
        );

        respuesta.put(
                "path",
                pdfPath
        );

        respuesta.put(
                "message",
                "Estado de cuenta generado correctamente."
        );

        return respuesta;
    }


    public File obtenerEstadoCuentaPdf(
            String codAsp) throws Exception {

        validarCodigoAsociado(codAsp);

        codAsp = codAsp.trim();

        String pdfPath =
                ReportPathUtil
                        .getAccountStatementPdf(
                                codAsp
                        );

        File pdf =
                new File(pdfPath);

        if (!pdf.exists()) {

            throw new Exception(
                    "No se encontró el estado de cuenta generado: "
                            + pdf.getAbsolutePath()
            );
        }

        if (!pdf.isFile()) {

            throw new Exception(
                    "La ruta del estado de cuenta no corresponde a un archivo: "
                            + pdf.getAbsolutePath()
            );
        }

        return pdf;
    }


    private Map<String, Object>
    consultarDatosEstadoCuenta(
            String codAsp) {

        String sql =
                "SELECT " +

                        "a.id_aso, " +
                        "a.cod_asp, " +
                        "a.nom_aso, " +
                        "a.fec_ing, " +
                        "a.activo, " +
                        "a.fecha_retiro, " +

                        "ISNULL(ap.SaldoAporteSocialConsolidado_3AniosNuevoCiclo, 0) " +
                        "AS SaldoAporteSocialConsolidado_3AniosNuevoCiclo, " +

                        "ISNULL(ap.SaldoAhorroPermanenteConsolidado_3AniosNuevoCiclo, 0) " +
                        "AS SaldoAhorroPermanenteConsolidado_3AniosNuevoCiclo, " +

                        "ISNULL(ap.SaldoRendimientoAhorroPermanenteConsolidado_3AniosNuevoCiclo, 0) " +
                        "AS SaldoRendimientoAhorroPermanenteConsolidado_3AniosNuevoCiclo, " +

                        "ISNULL(ap.SaldosAporteEmpresa, 0) " +
                        "AS SaldosAporteEmpresa, " +

                        "ISNULL(ap.SaldosRendimientosAporteEmpresa, 0) " +
                        "AS SaldosRendimientosAporteEmpresa, " +

                        "ISNULL(ap.SaldoAhorroNavideno, 0) " +
                        "AS SaldoAhorroNavideno, " +

                        "ISNULL(ap.TotalCrucesRetiros_2025_2026, 0) " +
                        "AS TotalCrucesRetiros_2025_2026 " +

                        "FROM FEMPROBIEN.dbo.tblAsociado a " +

                        "LEFT JOIN FEMPROBIEN.dbo.tblAportes ap " +
                        "ON ap.id_aso = a.id_aso " +

                        "WHERE a.cod_asp = ?";

        try {

            return sqlServerJdbcTemplate
                    .queryForMap(
                            sql,
                            codAsp
                    );

        } catch (EmptyResultDataAccessException e) {

            throw new IllegalArgumentException(
                    "No se encontró el asociado con código: "
                            + codAsp
            );
        }
    }


    private Map<String, Object>
    crearParametrosReporte(
            Map<String, Object> datos) {

        Map<String, Object> params =
                new HashMap<>();

        params.put(
                "codAsp",
                getString(
                        datos.get("cod_asp")
                )
        );

        params.put(
                "nomAso",
                getString(
                        datos.get("nom_aso")
                )
        );

        params.put(
                "fecIng",
                getDate(
                        datos.get("fec_ing")
                )
        );

        params.put(
                "fechaRetiro",
                getDate(
                        datos.get("fecha_retiro")
                )
        );

        boolean activo =
                getBoolean(
                        datos.get("activo")
                );

        params.put(
                "estado",
                activo
                        ? "ACTIVO"
                        : "INACTIVO"
        );



        params.put(
                "saldoAporteSocialConsolidado",
                getBigDecimal(
                        datos.get(
                                "SaldoAporteSocialConsolidado_3AniosNuevoCiclo"
                        )
                )
        );

        params.put(
                "saldoAhorroPermanenteConsolidado",
                getBigDecimal(
                        datos.get(
                                "SaldoAhorroPermanenteConsolidado_3AniosNuevoCiclo"
                        )
                )
        );

        params.put(
                "saldoRendimientoAhorroPermanenteConsolidado",
                getBigDecimal(
                        datos.get(
                                "SaldoRendimientoAhorroPermanenteConsolidado_3AniosNuevoCiclo"
                        )
                )
        );

        params.put(
                "saldosAporteEmpresa",
                getBigDecimal(
                        datos.get(
                                "SaldosAporteEmpresa"
                        )
                )
        );

        params.put(
                "saldosRendimientosAporteEmpresa",
                getBigDecimal(
                        datos.get(
                                "SaldosRendimientosAporteEmpresa"
                        )
                )
        );

        params.put(
                "saldoAhorroNavideno",
                getBigDecimal(
                        datos.get(
                                "SaldoAhorroNavideno"
                        )
                )
        );

        params.put(
                "totalCrucesRetiros2025_2026",
                getBigDecimal(
                        datos.get(
                                "TotalCrucesRetiros_2025_2026"
                        )
                )
        );

        params.put(
                "fechaGeneracion",
                new java.util.Date()
        );

        return params;
    }


    private void validarCodigoAsociado(
            String codAsp) {

        if (codAsp == null ||
                codAsp.trim().isEmpty()) {

            throw new IllegalArgumentException(
                    "El código del asociado es obligatorio."
            );
        }


        if (!codAsp.trim()
                .matches("[A-Za-z0-9_-]+")) {

            throw new IllegalArgumentException(
                    "El código del asociado contiene caracteres no permitidos."
            );
        }
    }

    private String getString(
            Object value) {

        if (value == null) {
            return "";
        }

        return value.toString();
    }

    private BigDecimal getBigDecimal(
            Object value) {

        if (value == null) {
            return BigDecimal.ZERO;
        }

        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }

        if (value instanceof Number) {

            return new BigDecimal(
                    value.toString()
            );
        }

        try {

            return new BigDecimal(
                    value.toString()
            );

        } catch (Exception e) {

            return BigDecimal.ZERO;
        }
    }

    private java.util.Date getDate(
            Object value) {

        if (value == null) {
            return null;
        }

        if (value instanceof Date) {

            return new java.util.Date(
                    ((Date) value).getTime()
            );
        }

        if (value instanceof java.util.Date) {

            return (java.util.Date) value;
        }

        return null;
    }

    private boolean getBoolean(
            Object value) {

        if (value == null) {
            return false;
        }

        if (value instanceof Boolean) {
            return (Boolean) value;
        }

        if (value instanceof Number) {

            return ((Number) value)
                    .intValue() == 1;
        }

        String valor =
                value.toString();

        return "1".equals(valor)
                || "true".equalsIgnoreCase(valor)
                || "y".equalsIgnoreCase(valor)
                || "si".equalsIgnoreCase(valor);
    }
}