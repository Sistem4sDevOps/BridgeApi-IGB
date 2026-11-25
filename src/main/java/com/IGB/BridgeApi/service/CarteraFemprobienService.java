
package com.IGB.BridgeApi.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class CarteraFemprobienService {

    private final JdbcTemplate jdbc;

    private final FemprobienCarteraCorteService carteraCorteService;

    public CarteraFemprobienService(
            @Qualifier("sqlServerJdbcTemplate")
            JdbcTemplate jdbc,
            FemprobienCarteraCorteService carteraCorteService) {

        this.jdbc = jdbc;
        this.carteraCorteService = carteraCorteService;
    }

    public Map<String, Object> consultar(String documento) {

        if (documento == null || documento.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "El documento del asociado es obligatorio."
            );
        }

        String cedula = documento.trim();

        /* =====================================================
           1. CONSULTAR CREDITOS DEL ASOCIADO
           ===================================================== */

        String sqlCreditos =
                "SELECT " +
                        "id, " +
                        "cod_asp, " +
                        "nom_aso, " +
                        "val_desm, " +
                        "fecha_desem, " +
                        "inicio_dcto, " +
                        "fec_fin, " +
                        "plazo, " +
                        "valor_cuota, " +
                        "valor_pagar, " +
                        "activo, " +
                        "estado, " +
                        "fecha_retiro, " +
                        "fecha_corte_saldo, " +
                        "fecha_importacion_saldo " +
                        "FROM FEMPROBIEN.dbo.tblAporteCredito " +
                        "WHERE LTRIM(RTRIM(" +
                        "CAST(cod_asp AS VARCHAR(100)))) = ? " +
                        "ORDER BY fecha_desem DESC, id DESC";

        List<Map<String, Object>> registros =
                jdbc.queryForList(sqlCreditos, cedula);

        List<Map<String, Object>> creditos =
                new ArrayList<>();

        BigDecimal saldoTotal = BigDecimal.ZERO;

        int creditosConSaldo = 0;
        int creditosSinSaldoVerificable = 0;

        for (Map<String, Object> registro : registros) {

            Map<String, Object> credito =
                    new LinkedHashMap<>(registro);

            Object saldoObjeto = registro.get("valor_pagar");

            if (saldoObjeto == null) {

                creditosSinSaldoVerificable++;

                credito.put(
                        "observacionSaldo",
                        "El registro no tiene saldo informado."
                );

            } else {

                BigDecimal saldo;

                try {

                    saldo = new BigDecimal(
                            saldoObjeto.toString()
                    );

                } catch (NumberFormatException e) {

                    saldo = null;
                }

                if (saldo == null) {

                    creditosSinSaldoVerificable++;

                    credito.put(
                            "observacionSaldo",
                            "El saldo registrado no tiene " +
                                    "un formato numérico válido."
                    );

                } else if (saldo.compareTo(BigDecimal.ZERO) < 0) {

                    creditosSinSaldoVerificable++;

                    credito.put(
                            "observacionSaldo",
                            "El saldo es negativo y requiere revisión."
                    );

                } else {

                    saldoTotal = saldoTotal.add(saldo);

                    if (saldo.compareTo(BigDecimal.ZERO) > 0) {
                        creditosConSaldo++;
                    }
                }
            }

            creditos.add(credito);
        }

        // Cero registros NO permite certificar que el saldo sea cero.
        boolean saldosCompletos =
                !creditos.isEmpty() && creditosSinSaldoVerificable == 0;

        /* =====================================================
           2. VERIFICAR FECHA DE CORTE DE CARTERA
           ===================================================== */

        Map<String, Object> validacionCorte =
                carteraCorteService.verificarCorte(cedula);

        boolean carteraActualizada =
                Boolean.TRUE.equals(
                        validacionCorte.get("carteraActualizada")
                );

        Object fechaCorteVerificada =
                validacionCorte.get("fechaCorteVerificada");

        Object fechaCorteUltimaImportacion =
                validacionCorte.get(
                        "fechaCorteUltimaImportacion"
                );

        Object fechaCorteEsperada =
                validacionCorte.get("fechaCorteEsperada");

        Object creditosSinCorte =
                validacionCorte.get("creditosSinCorte");

        Object idsCreditosSinCorte =
                validacionCorte.get("idsCreditosSinCorte");

        Object idsCreditosSaldoInvalido =
                validacionCorte.get(
                        "idsCreditosSaldoInvalido"
                );

        Object saldoCreditosVerificado =
                validacionCorte.get(
                        "saldoCreditosVerificado"
                );

        /* =====================================================
           3. CONSULTAR APORTES DEL ASOCIADO
           ===================================================== */

        String sqlAportes =
                "SELECT " +
                        "[SaldoAporteSocialConsolidado_3AniosNuevoCiclo], " +
                        "[SaldoAhorroPermanenteConsolidado_3AniosNuevoCiclo], " +
                        "[SaldoRendimientoAhorroPermanenteConsolidado_3AniosNuevoCiclo], " +
                        "[SaldosAporteEmpresa], " +
                        "[SaldosRendimientosAporteEmpresa], " +
                        "[SaldoAhorroNavideno], " +
                        "[TotalCrucesRetiros_2025_2026] " +
                        "FROM FEMPROBIEN.dbo.tblAportes " +
                        "WHERE LTRIM(RTRIM(" +
                        "CAST(cod_asp AS VARCHAR(100)))) = ?";

        List<Map<String, Object>> aportes =
                jdbc.queryForList(sqlAportes, cedula);

        /* =====================================================
           4. CONSTRUIR RESPUESTA
           ===================================================== */

        Map<String, Object> respuesta =
                new LinkedHashMap<>();

        respuesta.put(
                "documento",
                cedula
        );

        respuesta.put(
                "cantidadCreditos",
                creditos.size()
        );

        respuesta.put(
                "creditosConSaldo",
                creditosConSaldo
        );

        respuesta.put(
                "creditosSinSaldoVerificable",
                creditosSinSaldoVerificable
        );

        respuesta.put(
                "creditos",
                creditos
        );

        // Auditoria informativa de los valores originales y conflicto puntual.
        // No determina vigencia ni cambia los saldos usados por el motor.
        Map<String, Object> diagnosticoVigencia =
                diagnosticarVigencia(registros);
        diagnosticoVigencia.put("cantidadActivoCeroConSaldo",
                validacionCorte.get("cantidadActivoCeroConSaldo"));
        diagnosticoVigencia.put("saldoActivoCeroConSaldo",
                validacionCorte.get("saldoActivoCeroConSaldo"));
        diagnosticoVigencia.put("requiereValidacionActivoCeroConSaldo",
                validacionCorte.get("vigenciaCarteraRequiereValidacion"));
        respuesta.put("diagnosticoVigenciaCartera", diagnosticoVigencia);

        // Un corte integro no basta para certificar vigencia contractual.
        boolean vigenciaConfirmada = Boolean.TRUE.equals(
                diagnosticoVigencia.get("criterioVigenciaConfirmado"));
        boolean inactivoConSaldo = Boolean.TRUE.equals(
                validacionCorte.get("vigenciaCarteraRequiereValidacion"));
        String estadoCarteraParaEstudio = registros.isEmpty()
                ? "SIN_CREDITOS_REGISTRADOS_NO_CERTIFICADO"
                : inactivoConSaldo
                ? "INACTIVO_CON_SALDO_PENDIENTE"
                : !saldosCompletos
                ? "SALDOS_INCOMPLETOS"
                : !carteraActualizada
                ? "CORTE_SIN_VALIDAR"
                : vigenciaConfirmada
                ? "SALDOS_Y_VIGENCIA_VERIFICADOS"
                : "SALDOS_VERIFICADOS_AL_CORTE_VIGENCIA_PENDIENTE";
        respuesta.put("estadoCarteraParaEstudio", estadoCarteraParaEstudio);
        respuesta.put("vigenciaContractualConfirmada", vigenciaConfirmada);
        respuesta.put("cupoCertificableConFuentesActuales",
                carteraActualizada && saldosCompletos && vigenciaConfirmada);
        respuesta.put("saldoInactivoPositivoNoExcluido", inactivoConSaldo);

        respuesta.put(
                "saldosCompletos",
                saldosCompletos
        );

        // Saldo registrado en tblAporteCredito.
        // No implica que corresponda a la fecha actual.
        respuesta.put(
                "saldoCreditosReportado",
                saldosCompletos ? saldoTotal : null
        );

        /* =====================================================
           5. INCORPORAR RESULTADOS DE TRAZABILIDAD
           ===================================================== */

        respuesta.put(
                "carteraActualizada",
                carteraActualizada
        );

        respuesta.put("estadoVerificacionCartera",
                validacionCorte.get("estadoVerificacionCartera"));
        respuesta.put("motivosPendientesCartera",
                validacionCorte.get("motivosPendientesCartera"));
        respuesta.put("cantidadCreditosSinCorte",
                validacionCorte.get("cantidadCreditosSinCorte"));
        respuesta.put("cantidadSaldosInvalidos",
                validacionCorte.get("cantidadSaldosInvalidos"));
        respuesta.put("sinCreditosRegistrados",
                validacionCorte.get("sinCreditosRegistrados"));
        respuesta.put("saldoCeroCertificado",
                validacionCorte.get("saldoCeroCertificado"));
        respuesta.put("permiteEscenarioCondicionalSinDeudas",
                validacionCorte.get("permiteEscenarioCondicionalSinDeudas"));
        // Conserva las alertas específicas del corte sin filtrar deuda.
        respuesta.put("cantidadActivoCeroConSaldo",
                validacionCorte.get("cantidadActivoCeroConSaldo"));
        respuesta.put("saldoActivoCeroConSaldo",
                validacionCorte.get("saldoActivoCeroConSaldo"));
        respuesta.put("idsActivoCeroConSaldo",
                validacionCorte.get("idsActivoCeroConSaldo"));
        respuesta.put("vigenciaCarteraRequiereValidacion",
                validacionCorte.get("vigenciaCarteraRequiereValidacion"));
        respuesta.put("idUltimaImportacion",
                validacionCorte.get("idUltimaImportacion"));
        respuesta.put("registrosOmitidosUltimaImportacion",
                validacionCorte.get("registrosOmitidosUltimaImportacion"));
        respuesta.put("registrosActualizadosUltimaImportacion",
                validacionCorte.get("registrosActualizadosUltimaImportacion"));
        respuesta.put("registrosInsertadosUltimaImportacion",
                validacionCorte.get("registrosInsertadosUltimaImportacion"));

        respuesta.put(
                "fechaCorteVerificada",
                fechaCorteVerificada
        );

        respuesta.put(
                "fechaCorteUltimaImportacion",
                fechaCorteUltimaImportacion
        );

        respuesta.put(
                "fechaCorteEsperada",
                fechaCorteEsperada
        );

        respuesta.put(
                "creditosSinCorte",
                creditosSinCorte
        );

        respuesta.put(
                "idsCreditosSinCorte",
                idsCreditosSinCorte
        );

        respuesta.put(
                "idsCreditosSaldoInvalido",
                idsCreditosSaldoInvalido
        );

        respuesta.put(
                "saldoCreditosVerificado",
                saldoCreditosVerificado
        );

        // Saldo histórico sólo cuando coincide el último corte
        // PROCESADO en todos los créditos encontrados del asociado.
        respuesta.put("corteHistoricoUtilizable",
                validacionCorte.get("corteHistoricoUtilizable"));
        respuesta.put("saldoCreditosUltimoCorte",
                validacionCorte.get("saldoCreditosUltimoCorte"));
        respuesta.put("fechaCorteHistoricoUtilizable",
                validacionCorte.get("fechaCorteHistoricoUtilizable"));
        respuesta.put("cantidadCreditosUltimoCorte",
                validacionCorte.get("cantidadCreditosUltimoCorte"));

        /* =====================================================
           6. INCORPORAR APORTES
           ===================================================== */

        respuesta.put(
                "aportes",
                aportes.size() == 1 ? aportes.get(0) : null
        );

        respuesta.put(
                "cantidadRegistrosAportes",
                aportes.size()
        );

        /* =====================================================
           7. CONSTRUIR OBSERVACIONES
           ===================================================== */

        List<String> observaciones =
                new ArrayList<>();

        if (carteraActualizada) {

            observaciones.add(
                    "La cartera del asociado tiene saldos " +
                            "verificados para el último cierre " +
                            "mensual esperado: " +
                            fechaCorteVerificada + "."
            );

            observaciones.add(
                    "La fecha de corte permite verificar " +
                            "los saldos reportados a ese cierre, " +
                            "pero no certifica el saldo " +
                            "en tiempo real."
            );

        } else {

            observaciones.add(
                    "No fue posible verificar completamente " +
                            "la cartera del asociado para el " +
                            "último cierre mensual esperado."
            );
        }

        // Si no hay créditos, no afirmar que hay saldos inválidos.
        if (creditosSinSaldoVerificable > 0) {

            observaciones.add(
                    "Existen " + creditosSinSaldoVerificable +
                            " crédito(s) sin un saldo numérico válido."
            );
        }

        if (creditos.isEmpty()) {

            observaciones.add(
                    "No se encontraron créditos registrados " +
                            "para el asociado. Se requiere " +
                            "confirmar que no existan " +
                            "obligaciones pendientes."
            );
        }

        /* =====================================================
           8. INCORPORAR OBSERVACIONES DEL CORTE
           ===================================================== */

        Object observacionesCorte =
                validacionCorte.get("observacionesCorte");

        if (observacionesCorte instanceof List<?>) {

            for (Object observacion :
                    (List<?>) observacionesCorte) {

                if (observacion != null) {
                    observaciones.add(
                            observacion.toString()
                    );
                }
            }
        }

        /* =====================================================
           9. VALIDAR INFORMACION DE APORTES
           ===================================================== */

        if (aportes.isEmpty()) {

            observaciones.add(
                    "No se encontraron aportes " +
                            "para el asociado."
            );

        } else if (aportes.size() > 1) {

            observaciones.add(
                    "Se encontraron varios registros " +
                            "de aportes. Debe definirse " +
                            "cuál corresponde al saldo vigente."
            );
        }

        respuesta.put(
                "observaciones",
                observaciones
        );

        return respuesta;
    }

    /**
     * Auditoría sin regla de negocio implícita: no asume que activo = 1,
     * estado = ACTIVO o saldo positivo acrediten por sí solos vigencia.
     * No contiene nombres, documentos ni identificadores de crédito.
     */
    private Map<String, Object> diagnosticarVigencia(
            List<Map<String, Object>> registros) {

        Map<String, Map<String, Object>> grupos = new LinkedHashMap<>();
        int positivos = 0;
        int ceros = 0;
        int invalidos = 0;
        BigDecimal saldoPositivoObservado = BigDecimal.ZERO;

        for (Map<String, Object> registro : registros) {
            String activo = valorRegistrado(registro.get("activo"));
            String estado = valorRegistrado(registro.get("estado"));
            String clave = activo.length() + ":" + activo + estado;
            Map<String, Object> grupo = grupos.get(clave);
            if (grupo == null) {
                grupo = new LinkedHashMap<>();
                grupo.put("activoRegistrado", activo);
                grupo.put("estadoRegistrado", estado);
                grupo.put("cantidadCreditos", 0);
                grupo.put("creditosSaldoPositivo", 0);
                grupo.put("creditosSaldoCero", 0);
                grupo.put("creditosSaldoNoVerificable", 0);
                grupo.put("saldoPositivoReportado", BigDecimal.ZERO);
                grupos.put(clave, grupo);
            }
            grupo.put("cantidadCreditos", (Integer) grupo.get("cantidadCreditos") + 1);

            BigDecimal saldo = saldoDiagnostico(registro.get("valor_pagar"));
            if (saldo == null || saldo.signum() < 0) {
                invalidos++;
                grupo.put("creditosSaldoNoVerificable",
                        (Integer) grupo.get("creditosSaldoNoVerificable") + 1);
            } else if (saldo.signum() == 0) {
                ceros++;
                grupo.put("creditosSaldoCero",
                        (Integer) grupo.get("creditosSaldoCero") + 1);
            } else {
                positivos++;
                saldoPositivoObservado = saldoPositivoObservado.add(saldo);
                grupo.put("creditosSaldoPositivo",
                        (Integer) grupo.get("creditosSaldoPositivo") + 1);
                grupo.put("saldoPositivoReportado",
                        ((BigDecimal) grupo.get("saldoPositivoReportado")).add(saldo));
            }
        }

        Map<String, Object> diagnostico = new LinkedHashMap<>();
        diagnostico.put("estado", registros.isEmpty()
                ? "SIN_REGISTROS_NO_CERTIFICA_SALDO_CERO"
                : "PENDIENTE_DEFINIR_REGLA_VIGENCIA");
        diagnostico.put("criterioVigenciaConfirmado", false);
        diagnostico.put("cantidadCreditos", registros.size());
        diagnostico.put("cantidadGruposActivoEstado", grupos.size());
        diagnostico.put("cantidadCreditosSaldoPositivo", positivos);
        diagnostico.put("cantidadCreditosSaldoCero", ceros);
        diagnostico.put("cantidadCreditosSaldoNoVerificable", invalidos);
        diagnostico.put("saldoPositivoReportado",
                invalidos == 0 && !registros.isEmpty()
                        ? saldoPositivoObservado : null);
        diagnostico.put("gruposActivoEstado", new ArrayList<>(grupos.values()));
        diagnostico.put("nota", "Los estados y la marca activo se muestran tal " +
                "como están registrados. No se ha definido qué combinaciones " +
                "representan obligaciones vigentes. La suma es solo histórica " +
                "y no certifica vigencia ni saldo cero.");
        return diagnostico;
    }

    private static String valorRegistrado(Object valor) {
        if (valor == null) return "SIN_DATO";
        String texto = String.valueOf(valor).trim().toUpperCase(Locale.ROOT);
        if (texto.isEmpty()) return "SIN_DATO";
        return texto.length() > 60 ? texto.substring(0, 60) : texto;
    }

    private static BigDecimal saldoDiagnostico(Object valor) {
        if (valor == null) return null;
        try {
            return new BigDecimal(String.valueOf(valor).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
