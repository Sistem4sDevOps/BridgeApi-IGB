package com.IGB.BridgeApi.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * Etapa 1: construye una entrada común para simulación individual y masiva.
 * No calcula cuotas, no autoriza créditos y no modifica ninguna tabla.
 * Las cifras manuales y observadas nunca se convierten en verificadas.
 */
@Service
public class FemprobienEntradaSimulacionService {

    private static final BigDecimal CUPO_GENERAL = new BigDecimal("15000000");
    private final EvaluacionCreditoService evaluacionService;

    public FemprobienEntradaSimulacionService(EvaluacionCreditoService evaluacionService) {
        this.evaluacionService = evaluacionService;
    }

    public Map<String, Object> preparar(Map<String, Object> solicitudEntrada) {
        if (solicitudEntrada == null) {
            throw new IllegalArgumentException("Debe enviar los parámetros de simulación.");
        }

        Integer numero = entero(solicitudEntrada.get("numeroSolicitud"));
        if (numero == null || numero <= 0) {
            throw new IllegalArgumentException("numeroSolicitud debe ser un entero positivo.");
        }

        // Reutiliza el estudio existente (solicitud, nómina, cartera y motor).
        Map<String, Object> estudio = evaluacionService.evaluarSolicitud(numero);
        if (estudio == null) {
            throw new IllegalStateException("La evaluación no devolvió información.");
        }

        Map<String, Object> solicitud = mapa(estudio.get("solicitud"));
        Map<String, Object> nomina = mapa(estudio.get("nomina"));
        Map<String, Object> indicadores = mapa(estudio.get("indicadoresFinancieros"));
        Map<String, Object> cartera = mapa(estudio.get("cartera"));
        Map<String, Object> historico = mapa(estudio.get("cupoHistorico"));
        // Fuente financiera autoritativa: mismo estudio que usa el agente.
        Map<String, Object> resumenFinanciero = mapa(estudio.get("resumenFinancieroAgente"));
        Map<String, Object> parametros = mapa(solicitudEntrada.get("parametros"));
        Map<String, Object> ingresosManual = mapa(solicitudEntrada.get("ingresos"));
        Map<String, Object> descuentosManual = mapa(solicitudEntrada.get("deducciones"));

        BigDecimal monto = preferirDecimal(parametros, "monto", solicitud, "montoSolicitado");
        Integer plazo = preferirEntero(parametros, "plazoMeses", solicitud, "plazoMeses");
        String frecuencia = preferirTexto(parametros, "frecuenciaPago", solicitud, "frecuenciaPagoRegistrada");
        if (frecuencia == null) frecuencia = "MENSUAL";
        frecuencia = frecuencia.toUpperCase(Locale.ROOT);

        if (monto == null || monto.signum() <= 0 || monto.scale() > 2) {
            throw new IllegalArgumentException("El monto debe ser positivo y tener máximo dos decimales.");
        }
        if (plazo == null || plazo <= 0 || plazo > 480) {
            throw new IllegalArgumentException("El plazo debe estar entre 1 y 480 meses para el escenario matemático.");
        }
        if (!"MENSUAL".equals(frecuencia) && !"QUINCENAL".equals(frecuencia)) {
            throw new IllegalArgumentException("La frecuencia debe ser MENSUAL o QUINCENAL.");
        }

        // No mezclar tasa mensual con TNA: la convención queda explícita.
        String tipoTasa = texto(parametros.get("tipoTasa"));
        if (tipoTasa == null) tipoTasa = "MENSUAL";
        tipoTasa = tipoTasa.toUpperCase(Locale.ROOT);
        if (!"MENSUAL".equals(tipoTasa) && !"TNA_30_360".equals(tipoTasa)) {
            throw new IllegalArgumentException("tipoTasa debe ser MENSUAL o TNA_30_360.");
        }
        BigDecimal tasa = "MENSUAL".equals(tipoTasa)
                ? preferirDecimal(parametros, "tasaPorcentaje", solicitud, "tasaInteresMensualRegistrada")
                : numeroDecimal(parametros.get("tasaPorcentaje"));
        if (tasa == null || tasa.signum() < 0 || tasa.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("Indique tasaPorcentaje entre 0 y 100 según tipoTasa.");
        }

        boolean salarioModificado = ingresosManual.containsKey("salarioBasico");
        BigDecimal salarioNomina = numeroDecimal(nomina.get("salarioBasicoMensual"));
        BigDecimal salario = salarioModificado
                ? numeroDecimal(ingresosManual.get("salarioBasico")) : salarioNomina;
        if (salario != null && salario.signum() < 0) {
            throw new IllegalArgumentException("salarioBasico no puede ser negativo.");
        }

        // No sumar comisiones, HE ni bonificaciones como ingreso admisible sin verificación externa.
        BigDecimal comisiones = numeroDecimal(ingresosManual.get("comisionesPrestacionales"));
        BigDecimal extras = numeroDecimal(ingresosManual.get("horasExtrasPrestacionales"));
        BigDecimal bonificaciones = numeroDecimal(ingresosManual.get("bonificacionesNoPrestacionales"));
        validarNoNegativo(comisiones, "comisionesPrestacionales");
        validarNoNegativo(extras, "horasExtrasPrestacionales");
        validarNoNegativo(bonificaciones, "bonificacionesNoPrestacionales");

        BigDecimal deduccionesVerificadas = numeroDecimal(estudio.get("deduccionesMensualesValidadas"));
        BigDecimal deduccionesObservadas = numeroDecimal(estudio.get("deduccionesMensualesObservadas"));
        if (deduccionesObservadas == null) {
            deduccionesObservadas = numeroDecimal(nomina.get("deduccionesMensualesObservadas"));
        }
        boolean nominaCompleta = Boolean.TRUE.equals(estudio.get("nominaDosQuincenasEncontradas"))
                || Boolean.TRUE.equals(nomina.get("nominaMensualCompleta"));
        boolean historicoDisponible = !nominaCompleta &&
                Boolean.TRUE.equals(nomina.get("escenarioHistoricoDisponible"));
        // La nómina histórica no es nómina vigente ni deducción certificada.
        if (deduccionesObservadas == null && historicoDisponible) {
            deduccionesObservadas = numeroDecimal(nomina.get("deduccionesHistoricasParaEscenario"));
        }
        // Respaldo únicamente para el MISMO estudio, sin mezclar asociados.
        if (deduccionesObservadas == null && nominaCompleta) {
            deduccionesObservadas = numeroDecimal(resumenFinanciero.get("totalDeduccionesObservadas"));
        }
        boolean nominaValidada = Boolean.TRUE.equals(estudio.get("nominaValidadaParaMotor"))
                && deduccionesVerificadas != null;

        // Ingresos variables clasificados por NovaWEB: no inferirlos a partir
        // de ingresosMensualesEstimados, que ya puede incluir el salario.
        BigDecimal variablesNomina = numeroDecimal(nomina.get("ingresosVariablesParaEscenario"));
        if (variablesNomina == null && nominaCompleta) {
            variablesNomina = numeroDecimal(nomina.get("comisionesHorasExtrasMensuales"));
        }

        boolean tieneDetalleManual = descuentosManual.containsKey("detalle");
        boolean tieneTotalManual = descuentosManual.containsKey("totalMensual");
        if (tieneDetalleManual && tieneTotalManual) {
            throw new IllegalArgumentException("Envíe deducciones.detalle o deducciones.totalMensual, no ambos.");
        }
        boolean usaDeduccionManual = tieneDetalleManual || tieneTotalManual;
        BigDecimal deducciones;
        List<Map<String, Object>> detalle;
        String origenDeducciones;

        if (tieneDetalleManual) {
            Object lista = descuentosManual.get("detalle");
            if (!(lista instanceof List)) {
                throw new IllegalArgumentException("deducciones.detalle debe ser una lista.");
            }
            detalle = new ArrayList<>();
            deducciones = BigDecimal.ZERO;
            for (Object item : (List<?>) lista) {
                Map<String, Object> fila = mapa(item);
                BigDecimal valor = numeroDecimal(fila.get("valor"));
                if (valor == null || valor.signum() < 0) {
                    throw new IllegalArgumentException("Cada deducción manual requiere valor mayor o igual a cero.");
                }
                String concepto = texto(fila.get("concepto"));
                if (concepto == null) {
                    throw new IllegalArgumentException("Cada deducción manual requiere concepto.");
                }
                Map<String, Object> normalizada = new LinkedHashMap<>();
                normalizada.put("concepto", concepto);
                normalizada.put("valor", valor);
                normalizada.put("origen", "MANUAL_NO_VERIFICADO");
                detalle.add(normalizada);
                deducciones = deducciones.add(valor);
            }
            origenDeducciones = "MANUAL_NO_VERIFICADO";
        } else if (tieneTotalManual) {
            deducciones = numeroDecimal(descuentosManual.get("totalMensual"));
            validarNoNegativo(deducciones, "deducciones.totalMensual");
            if (deducciones == null) throw new IllegalArgumentException("Debe indicar totalMensual.");
            detalle = Collections.emptyList();
            origenDeducciones = "MANUAL_NO_VERIFICADO";
        } else {
            deducciones = nominaValidada ? deduccionesVerificadas : deduccionesObservadas;
            detalle = listaMapas(nomina.get("deduccionesConsolidadasDetalle"));
            origenDeducciones = nominaValidada ? "NOMINA_VALIDADA"
                    : historicoDisponible ? "NOMINA_HISTORICA_ILUSTRATIVA"
                    : "NOMINA_OBSERVADA_NO_CERTIFICADA";
        }

        Map<String, Object> ingresos = new LinkedHashMap<>();
        ingresos.put("salarioBasico", salario);
        ingresos.put("salarioBasicoNovaWeb", salarioNomina);
        ingresos.put("origenSalario", salarioModificado ? "MANUAL_NO_VERIFICADO" : "NOMINA_NOVAWEB");
        ingresos.put("comisionesPrestacionalesReportadas", comisiones);
        ingresos.put("horasExtrasPrestacionalesReportadas", extras);
        ingresos.put("bonificacionesNoPrestacionalesReportadas", bonificaciones);
        ingresos.put("baseMensualParaEstudio", salario);
        // El motor financiero incluye comisiones/HE observadas sólo como
        // escenario ilustrativo. Un ajuste manual reemplaza esos valores,
        // nunca se suma una segunda vez a los importes de NovaWEB.
        boolean incluirVariables = Boolean.TRUE.equals(
                ingresosManual.get("incluirVariablesEnEscenario"));
        BigDecimal variablesEscenario = variablesNomina;
        if (incluirVariables) {
            variablesEscenario = BigDecimal.ZERO;
            if (comisiones != null) variablesEscenario = variablesEscenario.add(comisiones);
            if (extras != null) variablesEscenario = variablesEscenario.add(extras);
        }
        BigDecimal baseEscenario = salario;
        if (baseEscenario != null && variablesEscenario != null) {
            baseEscenario = baseEscenario.add(variablesEscenario);
        }
        ingresos.put("baseMensualEscenario", baseEscenario);
        ingresos.put("ingresosVariablesMensualesObservados", variablesEscenario);
        // Campos adicionales que consume el orquestador integral existente.
        // Se alimentan desde NovaWEB, sin sumar variables dos veces.
        ingresos.put("variablesObservadasNovaweb",
                nominaCompleta ? variablesNomina : null);
        ingresos.put("variablesHistoricasNovaweb",
                historicoDisponible
                        ? numeroDecimal(nomina.get("ingresosVariablesHistoricosParaEscenario"))
                        : null);
        ingresos.put("nominaMensualCompleta", nominaCompleta);
        ingresos.put("escenarioHistoricoConDosQuincenas", historicoDisponible);
        ingresos.put("fechasBaseEscenarioHistorico", nomina.get("fechasBaseEscenarioHistorico"));
        // Estos importes por sí solos no convierten un estudio preliminar
        // ni las variables observadas en ingreso verificado/certificado.
        ingresos.put("baseMensualEscenarioVerificada",
                nominaCompleta && salario != null && salario.signum() > 0 &&
                        !salarioModificado && !incluirVariables &&
                        (variablesEscenario == null || variablesEscenario.signum() == 0));
        ingresos.put("variablesIncluidasSoloEnEscenario", incluirVariables);
        ingresos.put("variablesAdicionalesValidadas", false);

        Map<String, Object> descuentos = new LinkedHashMap<>();
        descuentos.put("totalMensualParaEscenario", deducciones);
        descuentos.put("totalMensualObservadoNovaWeb", deduccionesObservadas);
        descuentos.put("totalMensualValidadoNovaWeb", deduccionesVerificadas);
        descuentos.put("deduccionesHistoricasNovaweb",
                historicoDisponible ? numeroDecimal(nomina.get("deduccionesHistoricasParaEscenario")) : null);
        descuentos.put("origen", origenDeducciones);
        descuentos.put("verificadosParaMotor", nominaValidada && !usaDeduccionManual && !salarioModificado);
        descuentos.put("detalle", detalle);

        Map<String, Object> condiciones = new LinkedHashMap<>();
        condiciones.put("monto", monto);
        condiciones.put("plazoMeses", plazo);
        condiciones.put("frecuenciaPago", frecuencia);
        condiciones.put("tipoTasa", tipoTasa);
        condiciones.put("tasaPorcentaje", tasa);
        // Únicamente una fecha explícita o registrada en el crédito.
        // No inventar el vencimiento a partir de fecha_solicitud ni de hoy.
        condiciones.put("fechaPrimeraCuota", preferirTexto(
                parametros, "fechaPrimeraCuota", solicitud, "fechaPrimeraCuotaRegistrada"));
        condiciones.put("plazoGeneralReglamentarioMeses", 24);
        condiciones.put("plazoCumpleReglamentoGeneral", plazo <= 24);

        Map<String, Object> evidencia = new LinkedHashMap<>();
        evidencia.put("fechaCorteCartera", cartera.get("fechaCorteUltimaImportacion"));
        evidencia.put("carteraActualizada", Boolean.TRUE.equals(cartera.get("carteraActualizada")));
        evidencia.put("saldoCreditosVerificado", indicadores.get("saldoCreditosVigentes"));
        evidencia.put("cupoDisponibleVerificado", indicadores.get("cupoDisponible"));
        evidencia.put("cupoPotencialCondicional", historico.get("cupoPotencialSiNoHayDeuda"));
        evidencia.put("cupoGeneral", CUPO_GENERAL);

        List<String> advertencias = new ArrayList<>();
        if (plazo > 24) advertencias.add("PLAZO_SUPERA_24_MESES");
        if (!nominaValidada || usaDeduccionManual) advertencias.add("DEDUCCIONES_PENDIENTES_DE_VERIFICACION");
        if (salarioModificado) advertencias.add("SALARIO_MODIFICADO_MANUALMENTE");
        if (comisiones != null || extras != null || bonificaciones != null) {
            advertencias.add(incluirVariables
                    ? "INGRESOS_VARIABLES_INCLUIDOS_SOLO_EN_ESCENARIO_NO_CERTIFICADO"
                    : "INGRESOS_VARIABLES_EXCLUIDOS_HASTA_VALIDACION");
        }
        if (!Boolean.TRUE.equals(cartera.get("carteraActualizada"))) {
            advertencias.add("CARTERA_NO_VERIFICADA_A_FECHA_ACTUAL");
        }
        if (historicoDisponible) advertencias.add("NOMINA_HISTORICA_SOLO_ILUSTRATIVA");
        if (salario == null) advertencias.add("SALARIO_PENDIENTE");
        if (deducciones == null) advertencias.add("DEDUCCIONES_PENDIENTES");

        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("numeroSolicitud", numero);
        // Conserva el estado real consultado en BridgeApi para trazabilidad.
        // No se altera ni se toma una decision sobre la solicitud.
        resultado.put("estadoSolicitud", solicitud.get("estadoSolicitud"));
        resultado.put("modo", "INDIVIDUAL");
        resultado.put("estadoPreparacion", "ENTRADA_NORMALIZADA");
        resultado.put("ingresos", ingresos);
        resultado.put("deducciones", descuentos);
        resultado.put("condiciones", condiciones);
        resultado.put("cartera", evidencia);
        resultado.put("advertencias", advertencias);
        resultado.put("modificaSolicitud", false);
        resultado.put("decisionAutomatica", false);
        resultado.put("nota", "Preparación de parámetros, no simulación ni estudio aprobado. Las cifras manuales y observadas no se certifican.");
        return resultado;
    }

    public Map<String, Object> prepararLote(List<Map<String, Object>> entradas) {
        if (entradas == null || entradas.isEmpty() || entradas.size() > 100) {
            throw new IllegalArgumentException("Debe enviar entre 1 y 100 solicitudes.");
        }
        List<Map<String, Object>> resultados = new ArrayList<>();
        List<Integer> numerosProcesados = new ArrayList<>();
        int exitos = 0;
        for (Map<String, Object> entrada : entradas) {
            try {
                Integer numero = entrada == null ? null : entero(entrada.get("numeroSolicitud"));
                if (numero == null || numero <= 0) {
                    throw new IllegalArgumentException("numeroSolicitud debe ser un entero positivo.");
                }
                if (numerosProcesados.contains(numero)) {
                    throw new IllegalArgumentException("La solicitud #" + numero + " aparece repetida en el lote.");
                }
                numerosProcesados.add(numero);
                Map<String, Object> normalizada = preparar(entrada);
                normalizada.put("modo", "MASIVA");
                resultados.add(normalizada);
                exitos++;
            } catch (RuntimeException ex) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("numeroSolicitud", entrada == null ? null : entrada.get("numeroSolicitud"));
                error.put("estadoPreparacion", "ERROR");
                error.put("mensaje", ex instanceof IllegalArgumentException
                        ? ex.getMessage() : "No se pudo preparar esta solicitud.");
                resultados.add(error);
            }
        }
        Map<String, Object> lote = new LinkedHashMap<>();
        lote.put("total", entradas.size());
        lote.put("preparadas", exitos);
        lote.put("errores", entradas.size() - exitos);
        lote.put("resultados", resultados);
        lote.put("modificaSolicitud", false);
        return lote;
    }

    private static void validarNoNegativo(BigDecimal numero, String campo) {
        if (numero != null && numero.signum() < 0)
            throw new IllegalArgumentException(campo + " no puede ser negativo.");
    }

    private static BigDecimal preferirDecimal(Map<String, Object> primario, String a,
                                              Map<String, Object> secundario, String b) {
        return primario.containsKey(a) ? numeroDecimal(primario.get(a)) : numeroDecimal(secundario.get(b));
    }

    private static Integer preferirEntero(Map<String, Object> primario, String a,
                                          Map<String, Object> secundario, String b) {
        return primario.containsKey(a) ? entero(primario.get(a)) : entero(secundario.get(b));
    }

    private static String preferirTexto(Map<String, Object> primario, String a,
                                        Map<String, Object> secundario, String b) {
        return primario.containsKey(a) ? texto(primario.get(a)) : texto(secundario.get(b));
    }

    private static BigDecimal numeroDecimal(Object o) {
        if (o == null || "".equals(String.valueOf(o).trim())) return null;
        try { return new BigDecimal(o.toString().trim()); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Importe numérico inválido: " + o); }
    }

    private static Integer entero(Object o) {
        if (o == null || "".equals(String.valueOf(o).trim())) return null;
        try { return numeroDecimal(o).intValueExact(); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException("Se esperaba un entero: " + o); }
    }

    private static String texto(Object o) {
        if (o == null) return null;
        String t = o.toString().trim();
        return t.isEmpty() ? null : t;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapa(Object o) {
        if (o == null) return Collections.emptyMap();
        if (!(o instanceof Map)) throw new IllegalArgumentException("Se esperaba un objeto JSON.");
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listaMapas(Object o) {
        if (!(o instanceof List)) return Collections.emptyList();
        return (List<Map<String, Object>>) o;
    }
}
