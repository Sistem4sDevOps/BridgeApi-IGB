
package com.IGB.BridgeApi.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MotorFinancieroService {

    /*
     * Base normativa del motor:
     * - Art. 5: descuentos por nomina <= 50% del salario,
     *   incluidos descuentos de ley; ingresos de comisiones
     *   prestacionales y horas extras sujetos a validacion.
     * - Arts. 5, 8 y 21: cupo general de COP 15 millones;
     *   las condiciones ligadas a ahorros/aportes requieren
     *   estudio y no se autorizan automaticamente aqui.
     * - Art. 10: plazo maximo general de 24 meses.
     *
     * Los controles de dos quincenas, periodicidad y corte
     * de cartera son validaciones tecnicas de la fuente,
     * NO requisitos que el reglamento enuncie literalmente.
     */
    private static final BigDecimal CUPO_GENERAL =
            new BigDecimal("15000000.00");

    private static final BigDecimal PORCENTAJE_MAXIMO =
            new BigDecimal("0.50");

    private static final int PLAZO_MAXIMO_MESES = 24;

    private static final BigDecimal CERO =
            new BigDecimal("0.00");

    private static final BigDecimal CIEN =
            new BigDecimal("100");

    public static class Entrada {

        public BigDecimal salarioBasicoMensual;

        // Ingresos variables de DOS quincenas aptas para escenario
        // ilustrativo: comisiones de ventas, horas extra y recargos.
        // No son ingresos mensuales certificados por estar observados.
        public BigDecimal ingresosVariablesMensualesObservados;
        // Solo una fuente autorizada puede habilitar su inclusion
        // en el calculo de cumplimiento financiero verificado.
        public BigDecimal ingresosVariablesMensualesValidados;
        public Boolean ingresosVariablesValidadosParaMotor;

        // Solamente deducciones validadas para
        // la proyeccion financiera.
        public BigDecimal deduccionesMensuales;

        // Suma observada en dos quincenas.
        // No equivale a una proyeccion validada.
        public BigDecimal deduccionesMensualesObservadas;

        // Confirma existencia de ambos periodos.
        public Boolean nominaMensualCompleta;

        // Exclusivamente para mostrar un escenario historico ilustrativo.
        // Nunca cambia nominaMensualCompleta ni deducciones validadas.
        public Boolean escenarioHistoricoConDosQuincenas;
        public List<String> fechasBaseEscenarioHistorico;

        // Confirma que las deducciones fueron validadas
        // mediante un proceso independiente.
        public Boolean deduccionesValidadasParaMotor;

        public BigDecimal saldoCreditosVigentes;
        public Boolean carteraActualizada;

        public BigDecimal montoSolicitado;
        public BigDecimal cuotaNuevaMensual;
        public Integer plazoMeses;
    }

    private BigDecimal moneda(BigDecimal valor) {
        return valor.setScale(2, RoundingMode.HALF_UP);
    }

    private void validarNoNegativo(
            BigDecimal valor, String campo) {

        if (valor != null && valor.signum() < 0) {
            throw new IllegalArgumentException(
                    campo + " no puede ser negativo.");
        }
    }

    // =====================================================
    // CUPO DISPONIBLE
    // =====================================================

    public BigDecimal calcularCupoDisponible(
            BigDecimal cupoMaximo,
            BigDecimal saldoCreditosVigentes) {

        if (cupoMaximo == null ||
                saldoCreditosVigentes == null) {

            throw new IllegalArgumentException(
                    "Cupo y saldo de cartera son obligatorios.");
        }

        validarNoNegativo(cupoMaximo, "Cupo máximo");

        validarNoNegativo(
                saldoCreditosVigentes,
                "Saldo de créditos");

        return moneda(
                cupoMaximo.subtract(
                        saldoCreditosVigentes).max(CERO));
    }

    // =====================================================
    // PORCENTAJE DE DEDUCCIONES
    // =====================================================

    public BigDecimal calcularPorcentajeDeducciones(
            BigDecimal deduccionesMensuales,
            BigDecimal salarioMensual) {

        if (deduccionesMensuales == null ||
                salarioMensual == null ||
                salarioMensual.signum() <= 0) {

            throw new IllegalArgumentException(
                    "Se requieren deducciones y salario " +
                            "mensual mayor que cero.");
        }

        validarNoNegativo(
                deduccionesMensuales,
                "Deducciones");

        return deduccionesMensuales
                .multiply(CIEN)
                .divide(
                        salarioMensual,
                        2,
                        RoundingMode.HALF_UP);
    }

    // =====================================================
    // LIMITE DE DESCUENTOS
    // =====================================================

    public BigDecimal calcularLimiteDescuentos(
            BigDecimal salarioMensual) {

        if (salarioMensual == null ||
                salarioMensual.signum() <= 0) {

            throw new IllegalArgumentException(
                    "El salario mensual debe ser mayor a cero.");
        }

        return moneda(
                salarioMensual.multiply(PORCENTAJE_MAXIMO));
    }

    // =====================================================
    // MARGEN DE DESCUENTO
    // =====================================================

    public BigDecimal calcularMargenDescuento(
            BigDecimal salarioMensual,
            BigDecimal deduccionesMensuales) {

        if (deduccionesMensuales == null) {

            throw new IllegalArgumentException(
                    "Se requieren deducciones mensuales.");
        }

        validarNoNegativo(
                deduccionesMensuales,
                "Deducciones");

        BigDecimal limite =
                calcularLimiteDescuentos(salarioMensual);

        return moneda(
                limite.subtract(
                        deduccionesMensuales).max(CERO));
    }

    // =====================================================
    // EVALUAR CUOTA
    // =====================================================

    public boolean evaluarCuotaSolicitada(
            BigDecimal cuotaMensual,
            BigDecimal margenMensualDisponible) {

        if (cuotaMensual == null ||
                margenMensualDisponible == null) {

            throw new IllegalArgumentException(
                    "Cuota y margen son obligatorios.");
        }

        validarNoNegativo(
                margenMensualDisponible,
                "Margen disponible");

        if (cuotaMensual.signum() <= 0) {

            throw new IllegalArgumentException(
                    "La cuota debe ser mayor a cero.");
        }

        return cuotaMensual.compareTo(
                margenMensualDisponible) <= 0;
    }

    // =====================================================
    // ESCENARIO ILUSTRATIVO
    // =====================================================

    private Map<String, Object> calcularEscenarioObservado(
            Entrada entrada,
            boolean salarioValido,
            boolean nominaCompleta) {

        boolean historico = !nominaCompleta && Boolean.TRUE.equals(
                entrada.escenarioHistoricoConDosQuincenas);

        if (!salarioValido ||
                (!nominaCompleta && !historico) ||
                entrada.deduccionesMensualesObservadas == null) {

            return null;
        }

        BigDecimal deducciones =
                entrada.deduccionesMensualesObservadas;
        // El limite y los dos TOPEs deben usar la MISMA base.
        BigDecimal variablesObservadas = entrada.ingresosVariablesMensualesObservados
                == null ? CERO : entrada.ingresosVariablesMensualesObservados;
        BigDecimal baseIngresosEscenario = moneda(
                entrada.salarioBasicoMensual.add(variablesObservadas));
        BigDecimal limiteEscenario = calcularLimiteDescuentos(baseIngresosEscenario);

        Map<String, Object> escenario =
                new LinkedHashMap<>();

        escenario.put(
                "tipo",
                historico ? "ILUSTRATIVO_HISTORICO_NO_VALIDADO"
                        : "ILUSTRATIVO_NO_VALIDADO");
        escenario.put("baseHistorica", historico);
        escenario.put("fechasNominaBase",
                historico ? entrada.fechasBaseEscenarioHistorico : null);

        escenario.put("salarioBasicoMensual", entrada.salarioBasicoMensual);
        escenario.put("ingresosVariablesObservados", variablesObservadas);
        escenario.put("baseIngresosParaTope", baseIngresosEscenario);
        escenario.put("ingresosVariablesCertificados", false);

        escenario.put(
                "deduccionesObservadas",
                deducciones);

        escenario.put(
                "porcentajeDeduccionesObservadas",
                calcularPorcentajeDeducciones(
                        deducciones,
                        baseIngresosEscenario));

        escenario.put(
                "limiteDescuentosMensuales",
                limiteEscenario);

        escenario.put(
                "cuotaNuevaMensual",
                entrada.cuotaNuevaMensual);

        BigDecimal totalConCuota = null;
        BigDecimal porcentajeConCuota = null;
        BigDecimal diferenciaFrenteLimite = null;
        Boolean superaLimite = null;

        if (entrada.cuotaNuevaMensual != null &&
                entrada.cuotaNuevaMensual.signum() > 0) {

            totalConCuota = moneda(
                    deducciones.add(
                            entrada.cuotaNuevaMensual));

            porcentajeConCuota =
                    calcularPorcentajeDeducciones(
                            totalConCuota,
                            baseIngresosEscenario);

            diferenciaFrenteLimite = moneda(
                    limiteEscenario.subtract(
                            totalConCuota));

            superaLimite =
                    totalConCuota.compareTo(
                            limiteEscenario) > 0;
        }

        escenario.put(
                "deduccionesConNuevaCuota",
                totalConCuota);

        escenario.put(
                "porcentajeConNuevaCuota",
                porcentajeConCuota);

        escenario.put(
                "diferenciaFrenteLimite",
                diferenciaFrenteLimite);

        escenario.put(
                "superaLimiteEnEscenario",
                superaLimite);

        escenario.put(
                "observacion",
                historico
                        ? "Escenario ilustrativo con dos quincenas HISTORICAS " +
                        "consecutivas disponibles " +
                        entrada.fechasBaseEscenarioHistorico +
                        ". No son las dos quincenas esperadas. " +
                        "No valida el TOPE ni la capacidad de pago."
                        : "Escenario calculado usando descuentos " +
                        "observados en dos quincenas. " +
                        "Incluye comisiones, horas extras y recargos observados " +
                        "en la base de ingresos cuando existan. " +
                        "No acredita recurrencia futura ni constituye " +
                        "validacion de capacidad de pago.");

        return escenario;
    }

    // =====================================================
    // TRAZABILIDAD DEL REGLAMENTO (INFORMATIVA)
    // =====================================================

    private Map<String, Object> criterioReglamentario(
            String codigo,
            String articulo,
            String criterio,
            String estado,
            String aclaracion) {

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("codigo", codigo);
        item.put("articulo", articulo);
        item.put("criterio", criterio);
        item.put("estado", estado);
        item.put("aclaracion", aclaracion);
        return item;
    }

    private List<Map<String, Object>> construirCriteriosReglamentarios(
            Entrada entrada,
            boolean salarioValido,
            boolean deduccionesValidadas,
            boolean carteraCompleta,
            BigDecimal limiteDescuentos,
            BigDecimal cupoDisponible) {

        List<Map<String, Object>> criterios = new ArrayList<>();

        // Articulo 5: la regla es el limite del 50%;
        // la certificacion de descuentos es una fuente tecnica.
        String estadoDescuentos = "PENDIENTE_DATOS_VERIFICADOS";
        if (salarioValido && deduccionesValidadas &&
                entrada.cuotaNuevaMensual != null &&
                entrada.cuotaNuevaMensual.signum() > 0) {
            BigDecimal total = entrada.deduccionesMensuales.add(
                    entrada.cuotaNuevaMensual);
            estadoDescuentos = total.compareTo(limiteDescuentos) > 0
                    ? "SUPERA_LIMITE" : "DENTRO_LIMITE";
        }
        criterios.add(criterioReglamentario(
                "DESCUENTOS_NOMINA_50",
                "ARTICULO_5",
                "Los descuentos por nomina no deben superar el " +
                        "50% del salario, incluidos los descuentos de ley.",
                estadoDescuentos,
                "El margen disponible es un calculo derivado del " +
                        "limite del 50%, no un requisito autonomo. " +
                        "Se requieren descuentos mensuales verificados."));

        String estadoPlazo = entrada.plazoMeses == null
                ? "PENDIENTE_DATO"
                : (entrada.plazoMeses > 0 &&
                entrada.plazoMeses <= PLAZO_MAXIMO_MESES
                ? "DENTRO_LIMITE" : "SUPERA_LIMITE");
        criterios.add(criterioReglamentario(
                "PLAZO_MAXIMO_24_MESES",
                "ARTICULO_10",
                "Plazo maximo general de dos anos (24 meses).",
                estadoPlazo,
                "La simulacion no constituye aprobacion de un plazo."));

        String estadoCupo = "PENDIENTE_CARTERA_VERIFICADA";
        if (carteraCompleta && cupoDisponible != null &&
                entrada.montoSolicitado != null) {
            estadoCupo = entrada.montoSolicitado.compareTo(
                    cupoDisponible) > 0
                    ? "SUPERA_CUPO_GENERAL_DISPONIBLE"
                    : "DENTRO_CUPO_GENERAL_DISPONIBLE";
        }
        criterios.add(criterioReglamentario(
                "CUPO_GENERAL_15000000",
                "ARTICULOS_5_8_21",
                "Cupo general conjunto de COP 15.000.000, " +
                        "descontando obligaciones vigentes.",
                estadoCupo,
                "La posibilidad relacionada con altos niveles de " +
                        "ahorros y aportes requiere estudio de la " +
                        "instancia competente; el motor no la " +
                        "aprueba automaticamente."));

        criterios.add(criterioReglamentario(
                "AHORROS_APORTES_CASO_ESPECIAL",
                "ARTICULOS_5_8_21_31",
                "Condiciones especiales relacionadas con " +
                        "ahorros/aportes y decision del comite.",
                "NO_EVALUADO",
                "No calcular ni conceder automaticamente un cupo " +
                        "extra con base solamente en saldos de ahorro."));

        criterios.add(criterioReglamentario(
                "INGRESOS_PRESTACIONALES",
                "ARTICULO_5",
                "Pueden considerarse comisiones prestacionales " +
                        "y horas extras; excluir bonificaciones " +
                        "no prestacionales.",
                Boolean.TRUE.equals(entrada.ingresosVariablesValidadosParaMotor) &&
                        entrada.ingresosVariablesMensualesValidados != null
                        ? "VERIFICADO_POR_FUENTE" : "PENDIENTE_VERIFICACION_FUENTE",
                "El escenario observado incluye comisiones, horas extras " +
                        "y recargos en las quincenas utilizadas; el resultado " +
                        "verificado solo los incluye con fuente autorizada."));

        criterios.add(criterioReglamentario(
                "OBLIGACIONES_EXTERNAS_Y_SOLVENCIA",
                "ARTICULO_5",
                "Evaluar obligaciones financieras externas e " +
                        "internas, ingresos, egresos y solvencia.",
                "REQUIERE_ESTUDIO_COMPLEMENTARIO",
                "El motor numerico no sustituye la comprobacion " +
                        "de obligaciones externas ni del expediente."));

        return criterios;
    }

    // =====================================================
    // GENERAR INDICADORES FINANCIEROS
    // =====================================================

    public Map<String, Object> generarIndicadoresFinancieros(
            Entrada entrada) {

        if (entrada == null) {
            throw new IllegalArgumentException(
                    "Los datos de evaluación son obligatorios.");
        }

        validarNoNegativo(
                entrada.salarioBasicoMensual,
                "Salario mensual");

        validarNoNegativo(
                entrada.deduccionesMensuales,
                "Deducciones mensuales validadas");
        validarNoNegativo(entrada.ingresosVariablesMensualesObservados,
                "Ingresos variables observados");
        validarNoNegativo(entrada.ingresosVariablesMensualesValidados,
                "Ingresos variables validados");

        validarNoNegativo(
                entrada.deduccionesMensualesObservadas,
                "Deducciones observadas");

        validarNoNegativo(
                entrada.saldoCreditosVigentes,
                "Saldo de cartera");

        validarNoNegativo(
                entrada.montoSolicitado,
                "Monto solicitado");

        validarNoNegativo(
                entrada.cuotaNuevaMensual,
                "Cuota mensual");

        List<String> pendientes =
                new ArrayList<>();

        List<String> incumplimientos =
                new ArrayList<>();

        List<String> observaciones =
                new ArrayList<>();

        BigDecimal limiteDescuentos = null;
        BigDecimal baseIngresosVerificados = null;
        BigDecimal porcentajeActual = null;
        BigDecimal porcentajeProyectado = null;
        BigDecimal margenDisponible = null;
        BigDecimal cupoDisponible = null;
        BigDecimal deduccionesProyectadas = null;

        // =================================================
        // 1. SALARIO MENSUAL
        // =================================================

        boolean salarioValido =
                entrada.salarioBasicoMensual != null &&
                        entrada.salarioBasicoMensual.signum() > 0;

        if (!salarioValido) {

            pendientes.add(
                    "Falta confirmar el salario básico mensual.");

        } else {

            // El calculo certificado solo incorpora ingresos variables
            // cuando existe confirmacion explicita de fuente autorizada.
            baseIngresosVerificados = entrada.salarioBasicoMensual;
            if (Boolean.TRUE.equals(entrada.ingresosVariablesValidadosParaMotor)
                    && entrada.ingresosVariablesMensualesValidados != null) {
                baseIngresosVerificados = baseIngresosVerificados.add(
                        entrada.ingresosVariablesMensualesValidados);
            }
            limiteDescuentos = calcularLimiteDescuentos(baseIngresosVerificados);
        }

        if (entrada.ingresosVariablesMensualesObservados != null &&
                entrada.ingresosVariablesMensualesObservados.signum() > 0 &&
                !Boolean.TRUE.equals(entrada.ingresosVariablesValidadosParaMotor)) {
            pendientes.add("Existen comisiones, horas extras o recargos observados " +
                    "incluidos en el TOPE estimado, pero pendientes de validacion " +
                    "para el calculo financiero definitivo.");
        }

        // =================================================
        // 2. VALIDACION DE NOMINA
        // =================================================

        boolean nominaCompleta =
                Boolean.TRUE.equals(
                        entrada.nominaMensualCompleta);

        boolean deduccionesValidadas =
                nominaCompleta &&
                        Boolean.TRUE.equals(
                                entrada.deduccionesValidadasParaMotor) &&
                        entrada.deduccionesMensuales != null;

        if (!nominaCompleta) {

            pendientes.add(
                    "No están confirmadas las dos " +
                            "quincenas consecutivas de nómina.");

        } else if (!deduccionesValidadas) {

            pendientes.add(
                    "Las dos quincenas fueron consultadas, " +
                            "pero falta validar naturaleza, " +
                            "periodicidad e importes mensuales " +
                            "de las deducciones.");
        }

        if (deduccionesValidadas && salarioValido) {

            porcentajeActual =
                    calcularPorcentajeDeducciones(
                            entrada.deduccionesMensuales,
                            baseIngresosVerificados);

            margenDisponible =
                    calcularMargenDescuento(
                            baseIngresosVerificados,
                            entrada.deduccionesMensuales);

            if (entrada.deduccionesMensuales.compareTo(
                    limiteDescuentos) > 0) {

                incumplimientos.add(
                        "Los descuentos actuales validados " +
                                "superan el límite general del 50%.");
            }
        }

        // =================================================
        // 3. CARTERA
        // =================================================

        boolean carteraCompleta =
                Boolean.TRUE.equals(
                        entrada.carteraActualizada) &&
                        entrada.saldoCreditosVigentes != null;

        if (!carteraCompleta) {

            pendientes.add(
                    "Falta confirmar los saldos vigentes " +
                            "de créditos FEMPROBIEN.");

        } else {

            cupoDisponible =
                    calcularCupoDisponible(
                            CUPO_GENERAL,
                            entrada.saldoCreditosVigentes);
        }

        // =================================================
        // 4. MONTO SOLICITADO
        // =================================================

        if (entrada.montoSolicitado == null) {

            pendientes.add(
                    "Falta el monto solicitado.");

        } else if (entrada.montoSolicitado.signum() == 0) {

            incumplimientos.add(
                    "El monto solicitado debe ser mayor a cero.");

        } else if (cupoDisponible != null &&
                entrada.montoSolicitado.compareTo(
                        cupoDisponible) > 0) {

            incumplimientos.add(
                    "El monto solicitado supera el " +
                            "cupo general disponible.");
        }

        // =================================================
        // 5. PLAZO
        // =================================================

        if (entrada.plazoMeses == null) {

            pendientes.add(
                    "Falta el plazo solicitado.");

        } else if (entrada.plazoMeses <= 0 ||
                entrada.plazoMeses > PLAZO_MAXIMO_MESES) {

            incumplimientos.add(
                    "El plazo debe estar entre 1 y " +
                            PLAZO_MAXIMO_MESES + " meses.");
        }

        // =================================================
        // 6. NUEVA CUOTA
        // =================================================

        if (entrada.cuotaNuevaMensual == null) {

            pendientes.add(
                    "Falta simular la cuota mensual del crédito.");

        } else if (entrada.cuotaNuevaMensual.signum() == 0) {

            incumplimientos.add(
                    "La cuota mensual debe ser mayor a cero.");

        } else if (deduccionesValidadas && salarioValido) {

            deduccionesProyectadas = moneda(
                    entrada.deduccionesMensuales.add(
                            entrada.cuotaNuevaMensual));

            porcentajeProyectado =
                    calcularPorcentajeDeducciones(
                            deduccionesProyectadas,
                            baseIngresosVerificados);

            if (!evaluarCuotaSolicitada(
                    entrada.cuotaNuevaMensual,
                    margenDisponible)) {

                incumplimientos.add(
                        "La nueva cuota supera el margen " +
                                "disponible de descuentos de nómina.");
            }
        }

        // =================================================
        // 7. ESCENARIO OBSERVADO
        // =================================================

        Map<String, Object> escenarioObservado =
                calcularEscenarioObservado(
                        entrada,
                        salarioValido,
                        nominaCompleta);

        // =================================================
        // 8. OBSERVACIONES
        // =================================================

        if (entrada.montoSolicitado != null &&
                entrada.montoSolicitado.compareTo(
                        CUPO_GENERAL) > 0) {

            observaciones.add(
                    "El monto supera el límite general " +
                            "de $15.000.000. Cualquier excepción " +
                            "requiere revisión del órgano competente.");
        }

        if (escenarioObservado != null) {

            if (Boolean.TRUE.equals(escenarioObservado.get("baseHistorica"))) {
                pendientes.add("TOPE estimado con nomina historica: " +
                        escenarioObservado.get("fechasNominaBase") +
                        ". Falta nomina vigente y validacion de deducciones.");
            }

            observaciones.add(
                    "Se presenta un escenario ilustrativo " +
                            "con las deducciones observadas. " +
                            "Este escenario no determina cumplimiento.");
        }

        observaciones.add(
                "El estado CUMPLE representa únicamente " +
                        "el cumplimiento de las reglas numéricas " +
                        "evaluadas por este motor.");

        observaciones.add(
                "Este análisis no constituye aprobación " +
                        "ni rechazo definitivo del crédito.");

        // =================================================
        // 9. ESTADO DEL ANALISIS
        // =================================================

        String estado;

        if (!incumplimientos.isEmpty()) {

            estado = "NO_CUMPLE";

        } else if (!pendientes.isEmpty()) {

            estado = "REQUIERE_VALIDACION";

        } else {

            estado = "CUMPLE";
        }

        // =================================================
        // 10. RESPUESTA
        // =================================================

        Map<String, Object> resultado =
                new LinkedHashMap<>();

        resultado.put(
                "estadoAnalisisFinanciero",
                estado);

        resultado.put(
                "cupoGeneral",
                CUPO_GENERAL);

        resultado.put(
                "saldoCreditosVigentes",
                carteraCompleta
                        ? entrada.saldoCreditosVigentes
                        : null);

        resultado.put(
                "cupoDisponible",
                cupoDisponible);

        resultado.put(
                "salarioBasicoMensual",
                entrada.salarioBasicoMensual);
        resultado.put("ingresosVariablesMensualesObservados",
                entrada.ingresosVariablesMensualesObservados);
        resultado.put("ingresosVariablesMensualesValidados",
                Boolean.TRUE.equals(entrada.ingresosVariablesValidadosParaMotor)
                        ? entrada.ingresosVariablesMensualesValidados : null);
        resultado.put("baseIngresosVerificados", baseIngresosVerificados);
        resultado.put("baseIngresosEscenarioEstimado",
                escenarioObservado == null ? null :
                        escenarioObservado.get("baseIngresosParaTope"));
        resultado.put("limiteDescuentosMensualesVerificado", limiteDescuentos);
        resultado.put("limiteDescuentosMensualesEscenario",
                escenarioObservado == null ? null :
                        escenarioObservado.get("limiteDescuentosMensuales"));

        resultado.put(
                "nominaMensualCompleta",
                nominaCompleta);

        resultado.put(
                "deduccionesValidadasParaMotor",
                deduccionesValidadas);

        resultado.put(
                "deduccionesMensualesObservadas",
                nominaCompleta
                        ? entrada.deduccionesMensualesObservadas
                        : null);
        resultado.put("deduccionesHistoricasParaEscenario",
                !nominaCompleta && Boolean.TRUE.equals(
                        entrada.escenarioHistoricoConDosQuincenas)
                        ? entrada.deduccionesMensualesObservadas : null);

        resultado.put(
                "deduccionesMensuales",
                deduccionesValidadas
                        ? entrada.deduccionesMensuales
                        : null);

        resultado.put(
                "porcentajeMaximoDeducciones",
                new BigDecimal("50.00"));

        // Mantener el limite visible alineado con el TOPE observado.
        // El limite de cumplimiento verificado se publica por separado.
        resultado.put(
                "limiteDescuentosMensuales",
                escenarioObservado == null ? limiteDescuentos :
                        escenarioObservado.get("limiteDescuentosMensuales"));

        resultado.put(
                "porcentajeDeduccionesActual",
                porcentajeActual);

        resultado.put(
                "margenMensualDisponible",
                margenDisponible);

        resultado.put(
                "montoSolicitado",
                entrada.montoSolicitado);

        resultado.put(
                "cuotaNuevaMensual",
                entrada.cuotaNuevaMensual);

        resultado.put(
                "deduccionesMensualesProyectadas",
                deduccionesProyectadas);

        resultado.put(
                "porcentajeDeduccionesProyectado",
                porcentajeProyectado);

        resultado.put(
                "escenarioObservado",
                escenarioObservado);

        String resultadoTopeObservado = "SIN_ESCENARIO";
        if (escenarioObservado != null &&
                escenarioObservado.get("porcentajeConNuevaCuota") != null) {
            resultadoTopeObservado = Boolean.TRUE.equals(
                    escenarioObservado.get("superaLimiteEnEscenario"))
                    ? "SUPERA_50_ESTIMADO" : "DENTRO_50_ESTIMADO";
        }
        resultado.put("resultadoTopeObservado", resultadoTopeObservado);
        resultado.put("topeObservadoEsCertificado", false);

        resultado.put(
                "plazoMeses",
                entrada.plazoMeses);

        resultado.put(
                "plazoMaximoMeses",
                PLAZO_MAXIMO_MESES);

        resultado.put(
                "pendientes",
                pendientes);

        resultado.put(
                "incumplimientos",
                incumplimientos);

        resultado.put(
                "observaciones",
                observaciones);

        // Campos ADICIONALES: no alteran ningun dato ni estado
        // consumido por el frontend o por el agente actual.
        resultado.put(
                "criteriosReglamentarios",
                construirCriteriosReglamentarios(
                        entrada, salarioValido, deduccionesValidadas,
                        carteraCompleta, limiteDescuentos, cupoDisponible));

        Map<String, Object> controles = new LinkedHashMap<>();
        controles.put("origen", "CONTROLES_TECNICOS_BRIDGEAPI");
        controles.put("sonRequisitosTextualesReglamento", false);
        controles.put("dosQuincenasEncontradas", nominaCompleta);
        controles.put("ingresosVariablesParaEscenario",
                entrada.ingresosVariablesMensualesObservados);
        controles.put("ingresosVariablesCertificados",
                Boolean.TRUE.equals(entrada.ingresosVariablesValidadosParaMotor) &&
                        entrada.ingresosVariablesMensualesValidados != null);
        controles.put("deduccionesMensualesVerificadas", deduccionesValidadas);
        controles.put("carteraVerificadaAlCorte", carteraCompleta);
        controles.put("margenDisponibleCalculadoConDatosVerificados",
                margenDisponible != null);
        controles.put("margenMensualDisponibleEsPrevioALaNuevaCuota", true);
        controles.put("advertencia",
                "Las dos quincenas y la fecha de corte son " +
                        "controles de calidad de datos, no nuevos " +
                        "limites del reglamento.");
        resultado.put("controlesTecnicos", controles);

        return resultado;
    }
}
