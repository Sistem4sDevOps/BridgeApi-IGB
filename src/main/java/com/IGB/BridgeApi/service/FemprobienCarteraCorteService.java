
package com.IGB.BridgeApi.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class FemprobienCarteraCorteService {

    private static final ZoneId ZONA_NEGOCIO =
            ZoneId.of("America/Bogota");

    private final JdbcTemplate jdbcTemplate;

    public FemprobienCarteraCorteService(
            @Qualifier("sqlServerJdbcTemplate")
            JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> verificarCorte(String cedula) {

        String documento =
                cedula == null ? "" : cedula.trim();

        if (documento.isEmpty()) {
            throw new IllegalArgumentException(
                    "El documento del asociado es obligatorio."
            );
        }

        LocalDate fechaCorteEsperada =
                YearMonth.now(ZONA_NEGOCIO)
                        .minusMonths(1)
                        .atEndOfMonth();

        // Última importación procesada correctamente
        List<Map<String, Object>> importaciones =
                jdbcTemplate.queryForList(
                        "SELECT TOP 1 " +
                                "id_importacion, fecha_corte, fecha_proceso, " +
                                "insertados, actualizados, omitidos " +
                                "FROM FEMPROBIEN.dbo.tblCarteraImportacion " +
                                "WHERE estado = 'PROCESADO' " +
                                "ORDER BY fecha_corte DESC, " +
                                "fecha_proceso DESC, id_importacion DESC"
                );

        LocalDate ultimoCorte = null;
        Map<String, Object> ultimaImportacion =
                importaciones.isEmpty() ? null : importaciones.get(0);

        if (ultimaImportacion != null) {
            ultimoCorte = convertirFecha(
                    ultimaImportacion.get("fecha_corte")
            );
        }

        // Todos los créditos históricos del asociado
        String sql =
                "SELECT " +
                        "id, activo, estado, fecha_retiro, valor_pagar, " +
                        "fecha_corte_saldo, fecha_importacion_saldo " +
                        "FROM FEMPROBIEN.dbo.tblAporteCredito " +
                        "WHERE LTRIM(RTRIM(" +
                        "CAST(cod_asp AS VARCHAR(100)))) = ? " +
                        "ORDER BY id";

        List<Map<String, Object>> creditos =
                jdbcTemplate.queryForList(sql, documento);

        BigDecimal saldoReportado = BigDecimal.ZERO;

        int creditosConSaldo = 0;
        int creditosSinCorte = 0;
        int saldosInvalidos = 0;

        // Un credito inactivo con deuda reportada exige revision.
        // No se elimina de saldoReportado.
        int cantidadActivoCeroConSaldo = 0;
        BigDecimal saldoActivoCeroConSaldo = BigDecimal.ZERO;
        List<Object> idsActivoCeroConSaldo = new ArrayList<>();

        List<Object> idsSinCorte = new ArrayList<>();
        List<Object> idsSaldoInvalido = new ArrayList<>();
        List<String> observaciones = new ArrayList<>();

        for (Map<String, Object> credito : creditos) {

            Object id = credito.get("id");

            BigDecimal saldo = convertirDecimal(
                    credito.get("valor_pagar")
            );

            LocalDate corteCredito = convertirFecha(
                    credito.get("fecha_corte_saldo")
            );

            Object fechaImportacion =
                    credito.get("fecha_importacion_saldo");

            // 1. Verificar integridad del saldo
            if (saldo == null || saldo.signum() < 0) {

                saldosInvalidos++;
                idsSaldoInvalido.add(id);

            } else {

                saldoReportado =
                        saldoReportado.add(saldo);

                if (saldo.signum() > 0) {
                    creditosConSaldo++;
                }
            }

            // Los registros inactivos con saldo > 0 se mantienen
            // en la suma informada, pero bloquean su certificacion.
            // Puede representar una deuda tras el retiro del asociado.
            if (saldo != null && saldo.signum() > 0 &&
                    esActivoCero(credito.get("activo"))) {
                cantidadActivoCeroConSaldo++;
                saldoActivoCeroConSaldo =
                        saldoActivoCeroConSaldo.add(saldo);
                idsActivoCeroConSaldo.add(id);
            }

            // 2. Verificar trazabilidad por crédito
            boolean corteValido =
                    ultimoCorte != null
                            && ultimoCorte.equals(corteCredito)
                            && fechaImportacion != null;

            if (!corteValido) {
                creditosSinCorte++;
                idsSinCorte.add(id);
            }
        }

        // 3. Verificar que el corte global corresponde
        // al último cierre mensual esperado.
        boolean corteGlobalActual =
                ultimoCorte != null
                        && ultimoCorte.equals(fechaCorteEsperada);

        boolean existenCreditos = !creditos.isEmpty();

        boolean saldosCompletos =
                existenCreditos && saldosInvalidos == 0;

        // Referencia histórica: todos los registros del asociado deben
        // tener saldo válido y trazabilidad del MISMO último corte procesado.
        // No exige que ese corte corresponda al cierre mensual esperado.
        // No equivale a certificar ausencia de créditos no registrados.
        boolean corteHistoricoUtilizable =
                ultimoCorte != null &&
                        existenCreditos &&
                        saldosInvalidos == 0 &&
                        creditosSinCorte == 0 &&
                        cantidadActivoCeroConSaldo == 0;

        BigDecimal saldoCreditosUltimoCorte =
                corteHistoricoUtilizable ? saldoReportado : null;


        // Motivos legibles por sistemas y por funcionarios.
        // Evitar afirmar saldo cero cuando solo faltan registros.
        List<String> motivosPendientes = new ArrayList<>();
        if (ultimoCorte == null) {
            motivosPendientes.add("SIN_IMPORTACION_PROCESADA");
        } else if (!corteGlobalActual) {
            motivosPendientes.add("CORTE_GLOBAL_DESACTUALIZADO");
        }
        if (!existenCreditos) {
            motivosPendientes.add("SIN_CREDITOS_REGISTRADOS_NO_CERTIFICADO");
        }
        if (creditosSinCorte > 0) {
            motivosPendientes.add("CREDITOS_SIN_TRAZABILIDAD_ACTUAL");
        }
        if (saldosInvalidos > 0) {
            motivosPendientes.add("SALDOS_NULOS_NEGATIVOS_O_INVALIDOS");
        }
        if (cantidadActivoCeroConSaldo > 0) {
            motivosPendientes.add("ACTIVO_0_CON_SALDO_POSITIVO");
        }

        // Solo es true cuando todos los créditos
        // registrados tienen saldo válido y trazabilidad.
        boolean carteraActualizada =
                existenCreditos
                        && saldosCompletos
                        && corteGlobalActual
                        && creditosSinCorte == 0
                        && cantidadActivoCeroConSaldo == 0;

        if (ultimoCorte == null) {
            observaciones.add(
                    "No existe una importación de cartera " +
                            "con estado PROCESADO."
            );
        } else if (!corteGlobalActual) {
            observaciones.add(
                    "El último corte procesado no corresponde " +
                            "al mes anterior esperado."
            );
        }

        if (!existenCreditos) {
            observaciones.add(
                    "No se encontraron créditos registrados. " +
                            "Esto no demuestra por sí solo " +
                            "que no existan obligaciones."
            );
        }

        if (creditosSinCorte > 0) {
            observaciones.add(
                    "Existen " + creditosSinCorte +
                            " crédito(s) sin trazabilidad válida " +
                            "para el último corte procesado."
            );
        }

        if (saldosInvalidos > 0) {
            observaciones.add(
                    "Existen " + saldosInvalidos +
                            " crédito(s) con saldo nulo o negativo."
            );
        }

        if (cantidadActivoCeroConSaldo > 0) {
            observaciones.add(
                    "Existen " + cantidadActivoCeroConSaldo +
                            " credito(s) con activo=0 y saldo positivo al corte. " +
                            "Su saldo se conserva como reportado, pero requiere " +
                            "aclarar si la obligacion sigue vigente. No se " +
                            "certifica cupo disponible con esa inconsistencia."
            );
        }

        if (carteraActualizada) {
            observaciones.add(
                    "Todos los créditos registrados del " +
                            "asociado tienen saldo y fecha de corte " +
                            "verificados para el cierre esperado. " +
                            "No representa certificación de saldo " +
                            "en tiempo real."
            );
        }

        if (!existenCreditos && ultimoCorte != null) {
            observaciones.add(
                    "No hay créditos registrados para el asociado; " +
                            "puede mostrarse solo un escenario hipotético sin deuda " +
                            "con el cupo máximo general. El saldo cero NO está certificado."
            );
        }

        Map<String, Object> resultado =
                new LinkedHashMap<>();

        resultado.put("documento", documento);

        resultado.put(
                "cantidadCreditos",
                creditos.size()
        );

        resultado.put(
                "creditosConSaldo",
                creditosConSaldo
        );

        resultado.put(
                "creditosSinCorte",
                creditosSinCorte
        );

        resultado.put(
                "creditosSinSaldoVerificable",
                saldosInvalidos
        );

        resultado.put(
                "idsCreditosSinCorte",
                idsSinCorte
        );

        resultado.put(
                "idsCreditosSaldoInvalido",
                idsSaldoInvalido
        );

        // Una consulta sin filas NO certifica saldo cero.
        resultado.put(
                "saldoCreditosReportado",
                existenCreditos && saldosInvalidos == 0
                        ? saldoReportado : null
        );

        resultado.put(
                "saldoCreditosVerificado",
                carteraActualizada ? saldoReportado : null
        );

        resultado.put(
                "saldosCompletos",
                saldosCompletos
        );

        resultado.put(
                "carteraActualizada",
                carteraActualizada
        );

        resultado.put(
                "fechaCorteVerificada",
                carteraActualizada
                        ? ultimoCorte.toString()
                        : null
        );

        resultado.put(
                "fechaCorteUltimaImportacion",
                ultimoCorte == null
                        ? null
                        : ultimoCorte.toString()
        );

        resultado.put(
                "fechaCorteEsperada",
                fechaCorteEsperada.toString()
        );

        resultado.put(
                "observacionesCorte",
                observaciones
        );
        resultado.put("estadoVerificacionCartera",
                carteraActualizada ? "VERIFICADA_AL_CORTE" : "PENDIENTE");
        resultado.put("motivosPendientesCartera", motivosPendientes);
        resultado.put("cantidadCreditosSinCorte", creditosSinCorte);
        resultado.put("cantidadSaldosInvalidos", saldosInvalidos);
        resultado.put("existenCreditosRegistrados", existenCreditos);

        // Evidencia de conflicto entre actividad y saldo del credito.
        resultado.put("cantidadActivoCeroConSaldo", cantidadActivoCeroConSaldo);
        resultado.put("saldoActivoCeroConSaldo", saldoActivoCeroConSaldo);
        resultado.put("idsActivoCeroConSaldo", idsActivoCeroConSaldo);
        resultado.put("vigenciaCarteraRequiereValidacion",
                cantidadActivoCeroConSaldo > 0);

        // Campos adicionales para estimar cupo sin cambiar el saldo
        // verificado ni el estado de cartera vigente.
        resultado.put("corteHistoricoUtilizable", corteHistoricoUtilizable);
        resultado.put("saldoCreditosUltimoCorte", saldoCreditosUltimoCorte);
        resultado.put("fechaCorteHistoricoUtilizable",
                corteHistoricoUtilizable ? ultimoCorte.toString() : null);
        resultado.put("cantidadCreditosUltimoCorte",
                corteHistoricoUtilizable ? creditos.size() : null);
        resultado.put("cupoHistoricoNoEsCupoAprobado", true);

        // Informacion auditada de la ultima importacion procesada.
        // La tabla real contiene omitidos, no errores/baseDatos/empresa.
        resultado.put("idUltimaImportacion",
                ultimaImportacion == null ? null : ultimaImportacion.get("id_importacion"));
        resultado.put("registrosOmitidosUltimaImportacion",
                ultimaImportacion == null ? null : ultimaImportacion.get("omitidos"));
        resultado.put("registrosActualizadosUltimaImportacion",
                ultimaImportacion == null ? null : ultimaImportacion.get("actualizados"));
        resultado.put("registrosInsertadosUltimaImportacion",
                ultimaImportacion == null ? null : ultimaImportacion.get("insertados"));

        // Es posible mostrar un escenario CONDICIONAL con el cupo general
        // cuando no existen filas para el asociado. No es saldo cero
        // acreditado y NUNCA alimenta el motor financiero.
        boolean sinCreditosRegistrados = creditos.isEmpty();
        resultado.put("sinCreditosRegistrados", sinCreditosRegistrados);
        resultado.put("saldoCeroCertificado", false);
        resultado.put("permiteEscenarioCondicionalSinDeudas",
                sinCreditosRegistrados && ultimoCorte != null);

        return resultado;
    }

    private boolean esActivoCero(Object valor) {
        if (valor == null) return false;
        if (valor instanceof Boolean) return !((Boolean) valor);
        if (valor instanceof Number) {
            return new BigDecimal(valor.toString()).compareTo(BigDecimal.ZERO) == 0;
        }
        String texto = String.valueOf(valor).trim();
        return "0".equals(texto) || "false".equalsIgnoreCase(texto);
    }

    private LocalDate convertirFecha(Object valor) {

        if (valor == null) {
            return null;
        }

        if (valor instanceof LocalDate) {
            return (LocalDate) valor;
        }

        if (valor instanceof Date) {
            return ((Date) valor).toLocalDate();
        }

        if (valor instanceof java.util.Date) {
            return new Date(
                    ((java.util.Date) valor).getTime()
            ).toLocalDate();
        }

        return LocalDate.parse(
                valor.toString().trim().substring(0, 10)
        );
    }

    private BigDecimal convertirDecimal(Object valor) {

        if (valor == null) {
            return null;
        }

        if (valor instanceof BigDecimal) {
            return (BigDecimal) valor;
        }

        try {
            return new BigDecimal(valor.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}