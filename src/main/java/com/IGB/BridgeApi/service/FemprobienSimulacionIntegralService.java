package com.IGB.BridgeApi.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orquestador único. Reutiliza evaluación existente, preparación de etapa 1
 * y el motor FemprobienSimuladorService existente. No modifica base de datos.
 * No certifica nómina, no asume cartera inexistente como saldo cero y
 * NO aprueba solicitudes.
 */
@Service
public class FemprobienSimulacionIntegralService {
    private final FemprobienEntradaSimulacionService entradas;
    private final FemprobienSimuladorService simulador;
    private final MotorFinancieroService motorFinanciero;
    private static final BigDecimal DOS = new BigDecimal("2");

    public FemprobienSimulacionIntegralService(
            FemprobienEntradaSimulacionService entradas,
            FemprobienSimuladorService simulador,
            MotorFinancieroService motorFinanciero) {
        this.entradas = entradas;
        this.simulador = simulador;
        this.motorFinanciero = motorFinanciero;
    }

    public Map<String, Object> simular(Map<String, Object> solicitud) {
        return calcularPreparada(entradas.preparar(solicitud));
    }

    /** Cada fila se evalúa de forma independiente para no detener el lote. */
    public Map<String, Object> simularLote(List<Map<String, Object>> solicitudes) {
        if (solicitudes == null || solicitudes.isEmpty() || solicitudes.size() > 100) {
            throw new IllegalArgumentException("Ingrese entre 1 y 100 solicitudes por lote.");
        }
        List<Map<String, Object>> resultados = new ArrayList<>();
        List<Integer> numerosProcesados = new ArrayList<>();
        int correctas = 0;
        for (Map<String, Object> entrada : solicitudes) {
            try {
                Integer numero = entrada == null ? null : entero(entrada.get("numeroSolicitud"));
                if (numero == null || numero <= 0) {
                    throw new IllegalArgumentException("numeroSolicitud debe ser un entero positivo.");
                }
                if (numerosProcesados.contains(numero)) {
                    throw new IllegalArgumentException("La solicitud #" + numero + " aparece repetida en el lote.");
                }
                numerosProcesados.add(numero);
                Map<String, Object> r = simular(entrada);
                r.put("modo", "MASIVA_POR_SOLICITUD");
                resultados.add(r);
                correctas++;
            } catch (RuntimeException ex) {
                Map<String, Object> fallo = new LinkedHashMap<>();
                fallo.put("numeroSolicitud", entrada == null ? null : entrada.get("numeroSolicitud"));
                fallo.put("estado", "ERROR");
                fallo.put("mensaje", ex instanceof IllegalArgumentException
                        ? ex.getMessage() : "No fue posible simular esta solicitud.");
                resultados.add(fallo);
            }
        }
        Map<String, Object> lote = new LinkedHashMap<>();
        lote.put("modo", "MASIVA_POR_SOLICITUD");
        lote.put("total", solicitudes.size());
        lote.put("simuladas", correctas);
        lote.put("errores", solicitudes.size() - correctas);
        lote.put("resultados", resultados);
        // Cada TOPE proyectado corresponde a una solicitud independiente.
        // No sumar automaticamente cuotas de solicitudes del mismo asociado:
        // podrian ser alternativas y no obligaciones simultaneas.
        lote.put("notaCapacidadPago",
                "Los TOPES proyectados son individuales por solicitud. " +
                        "Si varias solicitudes del mismo asociado se otorgarian simultaneamente, " +
                        "se requiere una evaluacion conjunta; este lote no acumula sus cuotas.");
        lote.put("modificaSolicitud", false);
        lote.put("decisionAutomatica", false);
        return lote;
    }

    /** Compara alternativas de una misma solicitud, cada una con su propia amortización. */
    public Map<String, Object> comparar(Map<String, Object> body) {
        if (body == null) throw new IllegalArgumentException("Petición vacía.");
        Object alternativasObj = body.get("alternativas");
        if (!(alternativasObj instanceof List)) {
            throw new IllegalArgumentException("Envíe el arreglo alternativas.");
        }
        List<?> alternativas = (List<?>) alternativasObj;
        if (alternativas.isEmpty() || alternativas.size() > 6) {
            throw new IllegalArgumentException("Debe comparar de 1 a 6 alternativas.");
        }
        Map<String, Object> originales = asMap(body.get("parametros"));
        List<Map<String, Object>> resultados = new ArrayList<>();
        int correctas = 0;
        for (int i = 0; i < alternativas.size(); i++) {
            Map<String, Object> cambio = asMap(alternativas.get(i));
            Map<String, Object> request = new LinkedHashMap<>(body);
            Map<String, Object> condiciones = new LinkedHashMap<>(originales);
            condiciones.putAll(cambio);
            request.put("parametros", condiciones);
            request.remove("alternativas");
            try {
                Map<String, Object> r = simular(request);
                r.put("numeroAlternativa", i + 1);
                resultados.add(r);
                correctas++;
            } catch (RuntimeException ex) {
                Map<String, Object> fallo = new LinkedHashMap<>();
                fallo.put("numeroAlternativa", i + 1);
                fallo.put("estado", "ERROR");
                fallo.put("mensaje", ex instanceof IllegalArgumentException
                        ? ex.getMessage() : "No fue posible simular la alternativa.");
                resultados.add(fallo);
            }
        }
        Map<String, Object> comparacion = new LinkedHashMap<>();
        comparacion.put("numeroSolicitud", body.get("numeroSolicitud"));
        comparacion.put("modo", "COMPARACION");
        comparacion.put("total", alternativas.size());
        comparacion.put("simuladas", correctas);
        comparacion.put("resultados", resultados);
        comparacion.put("modificaSolicitud", false);
        comparacion.put("decisionAutomatica", false);
        return comparacion;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> calcularPreparada(Map<String, Object> base) {
        Map<String, Object> condiciones = asMap(base.get("condiciones"));
        Map<String, Object> ingresos = asMap(base.get("ingresos"));
        Map<String, Object> deducciones = asMap(base.get("deducciones"));
        Map<String, Object> cartera = asMap(base.get("cartera"));

        BigDecimal monto = decimal(condiciones.get("monto"));
        Integer meses = entero(condiciones.get("plazoMeses"));
        BigDecimal tasa = decimal(condiciones.get("tasaPorcentaje"));
        String frecuencia = String.valueOf(condiciones.get("frecuenciaPago"));
        String tipoTasa = String.valueOf(condiciones.get("tipoTasa"));
        if (monto == null || meses == null || tasa == null) {
            throw new IllegalArgumentException("Faltan monto, plazo o tasa.");
        }
        if (meses > 120) {
            throw new IllegalArgumentException("El plazo matemático está limitado a 120 meses para estas simulaciones.");
        }

        Map<String, Object> resultadoOriginal;
        if ("TNA_30_360".equals(tipoTasa)) {
            resultadoOriginal = simulador.simularEscenarioMeses(monto, tasa, meses, frecuencia);
        } else if ("MENSUAL".equals(tipoTasa)) {
            // Mantiene la convención original del simulador para tasa mensual.
            resultadoOriginal = simulador.calcular(monto, meses, frecuencia, tasa);
        } else {
            throw new IllegalArgumentException("Convención de tasa no admitida.");
        }

        List<Map<String, Object>> tabla = tablaComun(resultadoOriginal, tipoTasa, monto);
        LocalDate fechaInicial = fecha(condiciones.get("fechaPrimeraCuota"));
        // Sólo fechas de referencia: no se infiere el calendario de nómina.
        for (Map<String, Object> fila : tabla) {
            int n = entero(fila.get("numeroCuota"));
            fila.put("fechaVencimiento", n > 0 && fechaInicial != null
                    ? ("MENSUAL".equals(frecuencia)
                    ? fechaInicial.plusMonths(n - 1L)
                    : fechaInicial.plusDays(15L * (n - 1L))).toString()
                    : null);
        }

        BigDecimal cuota = decimal(resultadoOriginal.get("cuota"));
        BigDecimal equivalente = "QUINCENAL".equals(frecuencia)
                ? cuota.multiply(DOS) : cuota;
        BigDecimal totalPagado = decimal(resultadoOriginal.get("total"));
        BigDecimal totalIntereses = decimal(resultadoOriginal.get("interesesTotales"));

        // MISMO motor financiero que el estudio individual: se cambia solo la
        // cuota mensual equivalente del escenario solicitado, no la fórmula.
        MotorFinancieroService.Entrada evaluacion = new MotorFinancieroService.Entrada();
        evaluacion.salarioBasicoMensual = decimal(ingresos.get("salarioBasico"));
        evaluacion.ingresosVariablesMensualesObservados = decimal(
                ingresos.get("ingresosVariablesMensualesObservados"));
        evaluacion.nominaMensualCompleta = Boolean.TRUE.equals(ingresos.get("nominaMensualCompleta"));
        evaluacion.escenarioHistoricoConDosQuincenas = Boolean.TRUE.equals(
                ingresos.get("escenarioHistoricoConDosQuincenas"));
        Object fechasBase = ingresos.get("fechasBaseEscenarioHistorico");
        if (fechasBase instanceof List) {
            evaluacion.fechasBaseEscenarioHistorico = new ArrayList<>();
            for (Object fechaBase : (List<?>) fechasBase) {
                evaluacion.fechasBaseEscenarioHistorico.add(String.valueOf(fechaBase));
            }
        }
        boolean descuentosVerificados = Boolean.TRUE.equals(deducciones.get("verificadosParaMotor"));
        evaluacion.deduccionesValidadasParaMotor = descuentosVerificados;
        evaluacion.deduccionesMensuales = descuentosVerificados
                ? decimal(deducciones.get("totalMensualValidadoNovaWeb")) : null;
        evaluacion.deduccionesMensualesObservadas = decimal(
                deducciones.get("totalMensualParaEscenario"));
        evaluacion.montoSolicitado = monto;
        evaluacion.plazoMeses = meses;
        evaluacion.cuotaNuevaMensual = equivalente;
        evaluacion.saldoCreditosVigentes = decimal(cartera.get("saldoCreditosVerificado"));
        evaluacion.carteraActualizada = Boolean.TRUE.equals(cartera.get("carteraActualizada"));

        Map<String, Object> indicadores = motorFinanciero.generarIndicadoresFinancieros(evaluacion);
        Map<String, Object> escenario = asMap(indicadores.get("escenarioObservado"));
        BigDecimal porcentajeProyectado = decimal(escenario.get("porcentajeConNuevaCuota"));
        BigDecimal porcentajeActual = decimal(escenario.get("porcentajeDeduccionesObservadas"));
        boolean porcentajeVerificado = false;
        if (porcentajeProyectado == null) {
            porcentajeProyectado = decimal(indicadores.get("porcentajeDeduccionesProyectado"));
            porcentajeActual = decimal(indicadores.get("porcentajeDeduccionesActual"));
            porcentajeVerificado = porcentajeProyectado != null;
        }
        BigDecimal baseSalarial = decimal(escenario.get("baseIngresosParaTope"));
        BigDecimal descuentos = decimal(deducciones.get("totalMensualParaEscenario"));
        BigDecimal limite = decimal(escenario.get("limiteDescuentosMensuales"));
        BigDecimal observadasConCuota = decimal(escenario.get("deduccionesConNuevaCuota"));
        if (porcentajeVerificado) {
            baseSalarial = decimal(indicadores.get("baseIngresosVerificados"));
            limite = decimal(indicadores.get("limiteDescuentosMensualesVerificado"));
            observadasConCuota = decimal(indicadores.get("deduccionesMensualesProyectadas"));
        }
        BigDecimal margenDespues = limite == null || observadasConCuota == null
                ? null : limite.subtract(observadasConCuota);

        Map<String, Object> capacidad = new LinkedHashMap<>();
        capacidad.put("salarioBaseEscenario", baseSalarial);
        capacidad.put("deduccionesMensualesEscenario", descuentos);
        capacidad.put("limiteDescuentos50", limite);
        capacidad.put("cuotaMensualEquivalente", equivalente);
        capacidad.put("deduccionesConNuevaCuota", observadasConCuota);
        capacidad.put("topeActualPorcentaje", porcentajeActual);
        capacidad.put("topeProyectadoPorcentaje", porcentajeProyectado);
        capacidad.put("margenPosteriorNuevaCuota", margenDespues);
        capacidad.put("tipo", porcentajeVerificado
                ? "PROYECCION_CON_DATOS_VALIDOS" : "ESCENARIO_ILUSTRATIVO_NO_VALIDADO");
        capacidad.put("verificado", porcentajeVerificado);
        capacidad.put("alcanceVerificacion", porcentajeVerificado
                ? "SOLO_BASE_DE_NOMINA" : "ESCENARIO_NO_VERIFICADO");
        capacidad.put("capacidadIntegralCertificada", false);
        capacidad.put("cumpleLimiteIlustrativo", porcentajeProyectado == null ? null
                : porcentajeProyectado.compareTo(new BigDecimal("50")) <= 0);
        capacidad.put("origenCalculo", "MOTOR_FINANCIERO_EXISTENTE");
        capacidad.put("motivoTopeNoDisponible", porcentajeProyectado != null ? null
                : evaluacion.salarioBasicoMensual == null || evaluacion.salarioBasicoMensual.signum() <= 0
                ? "FALTA_SALARIO_MENSUAL"
                : evaluacion.deduccionesMensualesObservadas == null && !descuentosVerificados
                ? "FALTAN_DEDUCCIONES_MENSUALES"
                : !Boolean.TRUE.equals(evaluacion.nominaMensualCompleta)
                && !Boolean.TRUE.equals(evaluacion.escenarioHistoricoConDosQuincenas)
                ? "FALTAN_DOS_QUINCENAS_VALIDAS"
                : "MOTOR_FINANCIERO_SIN_DATOS_SUFICIENTES");

        Map<String, Object> regla = new LinkedHashMap<>();
        regla.put("plazoMaximoGeneralMeses", 24);
        regla.put("plazoCumple", meses <= 24);
        regla.put("montoDentroCupoGeneralIndividual", monto.compareTo(new BigDecimal("15000000")) <= 0);
        regla.put("cupoDisponibleVerificado", cartera.get("cupoDisponibleVerificado"));
        BigDecimal cupoReal = decimal(cartera.get("cupoDisponibleVerificado"));
        regla.put("montoDentroCupoVerificado", cupoReal == null ? null : monto.compareTo(cupoReal) <= 0);
        regla.put("requiereRevisionDeudorSolidario", monto.compareTo(new BigDecimal("10000000")) > 0);
        regla.put("datosNominaParaTopeVerificados", porcentajeVerificado);
        regla.put("capacidadPagoIntegralCertificada", false);
        regla.put("requiereRevisarObligacionesExternas", true);
        regla.put("decision", "SIN_DECISION_AUTOMATICA");

        List<String> alertas = new ArrayList<>();
        Object antiguas = base.get("advertencias");
        if (antiguas instanceof List) for (Object item : (List<?>) antiguas) alertas.add(String.valueOf(item));
        if (monto.compareTo(new BigDecimal("15000000")) > 0) alertas.add("MONTO_SUPERA_CUPO_GENERAL_15M");
        if (cupoReal == null) alertas.add("CUPO_DISPONIBLE_NO_CERTIFICADO");
        else if (monto.compareTo(cupoReal) > 0) alertas.add("MONTO_SUPERA_CUPO_VERIFICADO");
        if (porcentajeProyectado == null) alertas.add("TOPE_NO_CALCULABLE_POR_FALTA_DE_DATOS");
        else if (porcentajeProyectado.compareTo(new BigDecimal("50")) > 0) alertas.add("TOPE_ESCENARIO_SUPERA_50_PORCIENTO");
        if (!porcentajeVerificado) alertas.add("TOPE_PROYECTADO_NO_CERTIFICADO");
        if (fechaInicial == null) alertas.add("FECHAS_DE_VENCIMIENTO_NO_DEFINIDAS");
        else if ("QUINCENAL".equals(frecuencia)) alertas.add("FECHAS_QUINCENALES_REFERENCIA_AJUSTAR_A_NOMINA");
        alertas.add("VALIDAR_SEGUROS_GASTOS_Y_OBLIGACIONES_EXTERNAS");

        Map<String, Object> simulacion = new LinkedHashMap<>();
        simulacion.put("monto", monto);
        simulacion.put("plazoMeses", meses);
        simulacion.put("frecuenciaPago", frecuencia);
        simulacion.put("tipoTasa", tipoTasa);
        simulacion.put("tasaPorcentaje", tasa);
        simulacion.put("cuotaPorPeriodo", cuota);
        simulacion.put("cuotaMensualEquivalente", equivalente);
        simulacion.put("cantidadCuotas", tabla.size() - 1);
        simulacion.put("interesesTotales", totalIntereses);
        simulacion.put("totalPagar", totalPagado);
        simulacion.put("tablaAmortizacion", tabla);
        simulacion.put("incluyeSegurosOGastos", false);
        simulacion.put("fechasSonReferenciales", fechaInicial != null);
        simulacion.put("metodo", "CUOTA_FIJA_EXISTENTE_BRIDGEAPI");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("numeroSolicitud", base.get("numeroSolicitud"));
        response.put("modo", "INDIVIDUAL_POR_SOLICITUD");
        response.put("estado", "SIMULADA");
        response.put("entrada", base);
        response.put("simulacion", simulacion);
        response.put("capacidadPago", capacidad);
        response.put("validacionReglamentaria", regla);
        response.put("advertencias", alertas);
        response.put("modificaSolicitud", false);
        response.put("decisionAutomatica", false);
        return response;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tablaComun(Map<String, Object> salida,
                                                        String tipoTasa, BigDecimal monto) {
        List<Map<String, Object>> tabla = new ArrayList<>();
        if ("TNA_30_360".equals(tipoTasa)) {
            Object result = salida.get("tablaAmortizacion");
            if (!(result instanceof List)) throw new IllegalStateException("Amortización no disponible.");
            for (Object linea : (List<?>) result) {
                tabla.add(new LinkedHashMap<>(asMap(linea)));
            }
        } else {
            Map<String, Object> inicial = new LinkedHashMap<>();
            inicial.put("numeroCuota", 0);
            inicial.put("cuotaAPagar", null);
            inicial.put("pagoAnticipado", null);
            inicial.put("totalPagado", null);
            inicial.put("interes", null);
            inicial.put("capitalAmortizado", null);
            inicial.put("capitalVivo", monto);
            tabla.add(inicial);
            Object pagos = salida.get("pagos");
            if (!(pagos instanceof List)) throw new IllegalStateException("Amortización no disponible.");
            for (Object item : (List<?>) pagos) {
                Map<String, Object> x = asMap(item);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("numeroCuota", x.get("numero"));
                f.put("cuotaAPagar", x.get("cuota"));
                f.put("pagoAnticipado", BigDecimal.ZERO);
                f.put("totalPagado", x.get("cuota"));
                f.put("interes", x.get("interes"));
                f.put("capitalAmortizado", x.get("capital"));
                f.put("capitalVivo", x.get("saldo"));
                tabla.add(f);
            }
            if (Boolean.TRUE.equals(salida.get("tablaParcial"))) {
                throw new IllegalArgumentException("La tabla del motor está incompleta; reduzca el plazo.");
            }
        }
        if (tabla.size() < 2) throw new IllegalArgumentException("No se generaron cuotas.");
        return tabla;
    }

    private static LocalDate fecha(Object value) {
        if (value == null || String.valueOf(value).trim().isEmpty()) return null;
        try { return LocalDate.parse(String.valueOf(value)); }
        catch (DateTimeParseException ex) { throw new IllegalArgumentException("fechaPrimeraCuota debe tener formato AAAA-MM-DD."); }
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) return null;
        try { return new BigDecimal(String.valueOf(value)); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Número inválido."); }
    }

    private static Integer entero(Object value) {
        BigDecimal d = decimal(value);
        if (d == null) return null;
        try { return d.intValueExact(); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException("Se requiere plazo entero."); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map)) return Collections.emptyMap();
        return (Map<String, Object>) value;
    }
}