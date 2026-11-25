package com.IGB.BridgeApi.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.logging.Level;

@Service
public class EvaluacionCreditoService {

    private static final BigDecimal DOS =
            new BigDecimal("2");
    private static final Logger LOG = Logger.getLogger(EvaluacionCreditoService.class.getName());

    private final JdbcTemplate jdbc;
    private final FemprobienCreditService creditService;
    private final NominaNovawebService nominaService;
    private final CarteraFemprobienService carteraService;
    private final MotorFinancieroService motorFinanciero;
    private final FemprobienSimuladorService simuladorService;

    public EvaluacionCreditoService(
            @Qualifier("sqlServerJdbcTemplate") JdbcTemplate jdbc,
            FemprobienCreditService creditService,
            NominaNovawebService nominaService,
            CarteraFemprobienService carteraService,
            MotorFinancieroService motorFinanciero,
            FemprobienSimuladorService simuladorService) {

        this.jdbc = jdbc;
        this.creditService = creditService;
        this.nominaService = nominaService;
        this.carteraService = carteraService;
        this.motorFinanciero = motorFinanciero;
        this.simuladorService = simuladorService;
    }

    // =====================================================
    // 1. EVALUAR SOLICITUD EXISTENTE
    // =====================================================

    public Map<String, Object> evaluarSolicitud(
            Integer numeroSolicitud) {

        if (numeroSolicitud == null || numeroSolicitud <= 0) {
            throw new IllegalArgumentException(
                    "Número de solicitud inválido.");
        }

        // =================================================
        // CONSULTAR SOLICITUD
        // =================================================

        Map<String, Object> solicitud =
                creditService.consultarSolicitud(numeroSolicitud);

        Integer idAsociado = entero(
                solicitud.get("id_aso"));

        if (idAsociado == null) {
            throw new IllegalStateException(
                    "La solicitud no tiene asociado relacionado.");
        }

        BigDecimal montoSolicitado = decimal(
                solicitud.get("monto_solicitado"));

        Integer plazoMeses = entero(
                solicitud.get("plazo_meses"));

        if (montoSolicitado == null ||
                montoSolicitado.signum() <= 0 ||
                plazoMeses == null ||
                plazoMeses <= 0) {

            throw new IllegalStateException(
                    "Monto o plazo inválido en la solicitud.");
        }

        // =================================================
        // CONSULTAR ASOCIADO
        // =================================================

        List<Map<String, Object>> asociados =
                jdbc.queryForList(
                        // Se leen los campos reales del registro para mapear
                        // cargo y fecha de afiliacion si estan registrados.
                        "SELECT * FROM FEMPROBIEN.dbo.tblAsociado " +
                                "WHERE id_aso = ?",
                        idAsociado
                );

        if (asociados.isEmpty()) {
            throw new IllegalStateException(
                    "No se encontró el asociado.");
        }

        Map<String, Object> asociado = asociados.get(0);

        String documento = texto(asociado.get("cod_asp"));
        String nombre = texto(asociado.get("nom_aso"));

        if (documento == null) {
            throw new IllegalStateException(
                    "El asociado no tiene documento registrado.");
        }

        String empresaRegistrada = texto(
                solicitud.get("empresa_grupo"));

        if (empresaRegistrada == null) {
            empresaRegistrada = texto(
                    asociado.get("nom_emp"));
        }

        String empresaNomina =
                normalizarEmpresa(empresaRegistrada);

        // =================================================
        // CONSULTAR NOMINA NOVAWEB
        // =================================================

        List<Map<String, Object>> resultadosNomina =
                nominaService.consultarNominaConsolidada(
                        empresaNomina,
                        Collections.singletonList(documento)
                );

        Map<String, Object> nomina = null;

        int coincidenciasNomina = 0;
        int erroresNomina = 0;

        for (Map<String, Object> item : resultadosNomina) {

            String estado = texto(item.get("estado"));

            if ("ENCONTRADO".equals(estado)) {

                coincidenciasNomina++;

                if (coincidenciasNomina == 1) {
                    nomina = item;
                }

            } else if (
                    "ERROR_CONSULTA".equals(estado) ||
                            "CONSULTA_INCOMPLETA".equals(estado)) {

                erroresNomina++;
            }
        }

        // No seleccionar una empresa arbitrariamente.
        if (coincidenciasNomina != 1) {
            nomina = null;
        }

        // =================================================
        // CONSOLIDACION DE NOMINA
        // =================================================

        BigDecimal salarioMensual = null;
        BigDecimal ingresosVariablesObservados = null;
        BigDecimal deduccionesObservadas = null;
        BigDecimal deduccionesHistoricas = null;
        BigDecimal deduccionesValidadas = null;
        boolean escenarioHistoricoDisponible = false;
        List<String> fechasBaseEscenarioHistorico = new ArrayList<>();

        boolean dosQuincenasEncontradas = false;
        boolean requiereValidacionDeducciones = true;

        if (nomina != null) {

            salarioMensual = decimal(
                    nomina.get("salarioBasicoMensual"));

            deduccionesObservadas = decimal(
                    nomina.get("deduccionesMensualesObservadas"));
            deduccionesHistoricas = decimal(
                    nomina.get("deduccionesHistoricasParaEscenario"));
            escenarioHistoricoDisponible = Boolean.TRUE.equals(
                    nomina.get("escenarioHistoricoDisponible"));
            Object fechasHistoricas = nomina.get("fechasBaseEscenarioHistorico");
            if (fechasHistoricas instanceof List) {
                for (Object fecha : (List<?>) fechasHistoricas) {
                    if (fecha != null) {
                        fechasBaseEscenarioHistorico.add(String.valueOf(fecha));
                    }
                }
            }

            deduccionesValidadas = decimal(
                    nomina.get("deduccionesValidadasParaProyeccion"));

            dosQuincenasEncontradas =
                    Boolean.TRUE.equals(
                            nomina.get("nominaMensualCompleta"));

            requiereValidacionDeducciones =
                    !Boolean.FALSE.equals(
                            nomina.get(
                                    "requiereValidacionDeducciones"));

            // Comisiones y horas extras observadas: solo escenario ilustrativo.
            // No sumar dos campos que representan el mismo consolidado.
            if (dosQuincenasEncontradas) {
                ingresosVariablesObservados = decimal(
                        nomina.get("ingresosVariablesParaEscenario"));
                if (ingresosVariablesObservados == null) {
                    ingresosVariablesObservados = decimal(
                            nomina.get("comisionesHorasExtrasMensuales"));
                }
            } else if (escenarioHistoricoDisponible) {
                ingresosVariablesObservados = decimal(
                        nomina.get("ingresosVariablesHistoricosParaEscenario"));
            }
        }

        /*
         * Dos quincenas completas no implican que
         * las deducciones esten validadas.
         */
        boolean nominaValidadaParaMotor =
                dosQuincenasEncontradas &&
                        !requiereValidacionDeducciones &&
                        deduccionesValidadas != null &&
                        deduccionesValidadas.signum() >= 0;

        // =================================================
        // CONSULTAR CARTERA Y APORTES
        // =================================================

        Map<String, Object> cartera =
                carteraService.consultar(documento);

        BigDecimal saldoVerificado = decimal(
                cartera.get("saldoCreditosVerificado"));

        boolean saldosCompletos =
                Boolean.TRUE.equals(
                        cartera.get("saldosCompletos"));

        boolean carteraActualizada =
                Boolean.TRUE.equals(
                        cartera.get("carteraActualizada"));

        BigDecimal saldoValidado =
                saldosCompletos && carteraActualizada
                        ? saldoVerificado
                        : null;

        // =================================================
        // SIMULAR CON TASA DE LA SOLICITUD
        // =================================================

        Map<String, Object> simulacion =
                simularSolicitudRegistrada(
                        solicitud,
                        montoSolicitado,
                        plazoMeses
                );

        String estadoSimulacion =
                texto(simulacion.get("estado"));

        boolean simulacionValidada =
                "VALIDADA".equals(estadoSimulacion);

        BigDecimal cuotaNuevaMensual =
                simulacionValidada
                        ? decimal(simulacion.get(
                        "cuotaMensualEquivalente"))
                        : null;

        // =================================================
        // PREPARAR ENTRADA DEL MOTOR FINANCIERO
        // =================================================

        MotorFinancieroService.Entrada entrada =
                new MotorFinancieroService.Entrada();

        entrada.salarioBasicoMensual =
                salarioMensual;

        // El MotorFinancieroService suma estas variables a la base observada.
        // No se declaran verificadas ni se altera la base certificada.
        entrada.ingresosVariablesMensualesObservados =
                ingresosVariablesObservados;

        entrada.nominaMensualCompleta =
                dosQuincenasEncontradas;

        // En nomina incompleta solo se admite el historial para un
        // escenario ilustrativo. NO se marca como deduccion verificada.
        entrada.deduccionesMensualesObservadas =
                dosQuincenasEncontradas ? deduccionesObservadas
                        : (escenarioHistoricoDisponible
                        ? deduccionesHistoricas : null);
        entrada.escenarioHistoricoConDosQuincenas =
                !dosQuincenasEncontradas && escenarioHistoricoDisponible;
        entrada.fechasBaseEscenarioHistorico =
                fechasBaseEscenarioHistorico;

        entrada.deduccionesValidadasParaMotor =
                nominaValidadaParaMotor;

        entrada.deduccionesMensuales =
                nominaValidadaParaMotor
                        ? deduccionesValidadas
                        : null;

        entrada.saldoCreditosVigentes =
                saldoValidado;

        entrada.carteraActualizada =
                carteraActualizada &&
                        saldosCompletos &&
                        saldoValidado != null;

        entrada.montoSolicitado =
                montoSolicitado;

        entrada.cuotaNuevaMensual =
                cuotaNuevaMensual;

        entrada.plazoMeses =
                plazoMeses;

        // =================================================
        // EJECUTAR MOTOR FINANCIERO
        // =================================================

        Map<String, Object> indicadores =
                motorFinanciero.generarIndicadoresFinancieros(
                        entrada);

        // =================================================
        // CUPO ESTIMADO AL ULTIMO CORTE (INDEPENDIENTE)
        // =================================================
        // No usarlo para alimentar el motor ni reemplazar cupoDisponible.
        // Solo se calcula si la verificación histórica identificó
        // saldos completos, no negativos y trazables a un mismo corte.
        BigDecimal saldoHistorico = decimal(
                cartera.get("saldoCreditosUltimoCorte"));
        BigDecimal cupoGeneral = decimal(indicadores.get("cupoGeneral"));
        boolean historicoUtilizable = Boolean.TRUE.equals(
                cartera.get("corteHistoricoUtilizable"));

        BigDecimal cupoEstimadoAlCorte = null;
        if (historicoUtilizable && saldoHistorico != null &&
                saldoHistorico.signum() >= 0 &&
                cupoGeneral != null && cupoGeneral.signum() >= 0) {
            cupoEstimadoAlCorte = cupoGeneral.subtract(saldoHistorico)
                    .max(BigDecimal.ZERO);
        }

        // Escenario exclusivamente condicional para el caso de CERO filas.
        // NO cambia saldoCreditosAlCorte, cupoEstimadoAlCorte ni cupoDisponible.
        boolean escenarioSinRegistros = Boolean.TRUE.equals(
                cartera.get("permiteEscenarioCondicionalSinDeudas"));
        BigDecimal cupoPotencialSiNoHayDeuda =
                escenarioSinRegistros && cupoGeneral != null &&
                        cupoGeneral.signum() >= 0 ? cupoGeneral : null;

        Map<String, Object> cupoHistorico = new LinkedHashMap<>();
        cupoHistorico.put("estado", cupoEstimadoAlCorte != null
                ? (carteraActualizada && saldoValidado != null
                ? "CORTE_ACTUAL_VERIFICADO" : "ESTIMADO_AL_ULTIMO_CORTE")
                : (cupoPotencialSiNoHayDeuda != null
                ? "ESCENARIO_CONDICIONAL_SIN_CREDITOS_REGISTRADOS"
                : "SIN_INFORMACION_HISTORICA_SUFICIENTE"));
        cupoHistorico.put("fechaCorte",
                cartera.get("fechaCorteHistoricoUtilizable"));
        cupoHistorico.put("fechaCorteReferencia",
                cartera.get("fechaCorteUltimaImportacion"));
        cupoHistorico.put("saldoCreditosAlCorte", saldoHistorico);
        cupoHistorico.put("cupoEstimadoAlCorte", cupoEstimadoAlCorte);
        cupoHistorico.put("cupoPotencialSiNoHayDeuda",
                cupoPotencialSiNoHayDeuda);
        cupoHistorico.put("sinCreditosRegistrados",
                Boolean.TRUE.equals(cartera.get("sinCreditosRegistrados")));
        cupoHistorico.put("saldoCeroCertificado", false);
        cupoHistorico.put("registrosOmitidosUltimaImportacion",
                cartera.get("registrosOmitidosUltimaImportacion"));
        cupoHistorico.put("esEstimacion", cupoEstimadoAlCorte != null &&
                !(carteraActualizada && saldoValidado != null));
        cupoHistorico.put("esHipotesisSinDeudas",
                cupoPotencialSiNoHayDeuda != null);
        cupoHistorico.put("nota",
                cupoPotencialSiNoHayDeuda != null
                        ? "Se muestra el cupo máximo general bajo el SUPUESTO de " +
                        "que no existen obligaciones: cero filas NO certifica saldo cero. " +
                        "La última importación puede contener registros omitidos y " +
                        "faltan movimientos posteriores al corte. No autoriza créditos."
                        : "Referencia del último corte procesado. No incluye " +
                        "movimientos posteriores, no certifica obligaciones " +
                        "no registradas y no constituye autorización de crédito.");

        // =================================================
        // OBSERVACIONES PENDIENTES
        // =================================================

        List<String> pendientes = new ArrayList<>();

        if (coincidenciasNomina == 0) {

            pendientes.add(
                    "No se encontró nómina del asociado.");
        }

        if (coincidenciasNomina > 1) {

            pendientes.add(
                    "Se encontraron varias coincidencias " +
                            "de nómina. Debe verificarse la empresa.");
        }

        if (erroresNomina > 0) {

            pendientes.add(
                    "Algunas bases NovaWEB presentaron " +
                            "errores de consulta.");
        }

        if (!dosQuincenasEncontradas) {

            pendientes.add(
                    "No están confirmadas las dos " +
                            "quincenas consecutivas esperadas.");
        }

        if (nomina != null &&
                dosQuincenasEncontradas &&
                !nominaValidadaParaMotor) {

            int conceptosPendientes =
                    entero(nomina.get("cantidadConceptosPendientesValidacion")) == null
                            ? 0 : entero(nomina.get("cantidadConceptosPendientesValidacion"));
            pendientes.add(
                    "Las deducciones observadas en NovaWEB " +
                            "requieren validación de naturaleza, " +
                            "periodicidad e importes aplicables. " +
                            "Conceptos pendientes: " + conceptosPendientes +
                            ". Consulta detalleConceptosPendientesValidacion.");
        }

        if (!carteraActualizada) {
            Object esperado = cartera.get("fechaCorteEsperada");
            Object ultimo = cartera.get("fechaCorteUltimaImportacion");
            pendientes.add(
                    "La cartera FEMPROBIEN no está validada. " +
                            "Corte esperado: " + (esperado == null ? "sin dato" : esperado) +
                            "; último corte procesado: " +
                            (ultimo == null ? "sin importación" : ultimo) +
                            ". Revisa motivosPendientesCartera.");
        }

        // No presentar "saldos invalidos" cuando la consulta
        // no encontro ningun credito. La ausencia de registros
        // tampoco certifica que el saldo del asociado sea cero.
        Integer cantidadCreditos = entero(
                cartera.get("cantidadCreditos"));
        if (cantidadCreditos != null && cantidadCreditos == 0) {
            pendientes.add(
                    "No se encontraron creditos registrados " +
                            "del asociado; no se puede certificar " +
                            "un saldo de cartera cero sin evidencia.");
        } else if (!saldosCompletos ||
                (carteraActualizada && saldoValidado == null)) {
            pendientes.add(
                    "Existen saldos de credito que " +
                            "requieren validacion.");
        }

        // El corte de cartera verifica trazabilidad temporal, pero no
        // interpreta por sí solo activo/estado como vigencia de la obligación.
        Object diagnosticoVigenciaObj = cartera.get("diagnosticoVigenciaCartera");
        Map<?, ?> diagnosticoVigencia = diagnosticoVigenciaObj instanceof Map
                ? (Map<?, ?>) diagnosticoVigenciaObj
                : Collections.emptyMap();
        if (!Boolean.TRUE.equals(diagnosticoVigencia.get("criterioVigenciaConfirmado")) &&
                cantidadCreditos != null && cantidadCreditos > 0) {
            pendientes.add("Pendiente definir con FEMPROBIEN qué valores " +
                    "de activo y estado representan créditos vigentes. " +
                    "El saldo al corte incluye los registros encontrados; " +
                    "no se ha certificado su vigencia contractual.");
        }

        if (!simulacionValidada) {

            pendientes.add(
                    "Verificar la simulación de cuota " +
                            "y las condiciones registradas " +
                            "en la solicitud.");
        }

        Integer cantidadAportes = entero(
                cartera.get("cantidadRegistrosAportes"));

        if (cantidadAportes == null ||
                cantidadAportes != 1) {

            pendientes.add(
                    "Verificar los aportes y ahorros " +
                            "registrados del asociado.");
        }

        // No inferir cumplimiento a partir de un resultado administrativo
        // APROBADA o de la ausencia de registros en fuentes locales.
        pendientes.add("OBLIGACIONES_EXTERNAS: consultar una fuente o " +
                "certificacion autorizada de obligaciones no registradas en NovaWEB.");
        pendientes.add("REGLAMENTO_ADICIONAL: acreditar antiguedad, " +
                "afiliacion y demas condiciones del reglamento del Fondo.");

        // =================================================
        // RESUMEN DE SOLICITUD
        // =================================================

        Map<String, Object> resumenSolicitud =
                new LinkedHashMap<>();

        resumenSolicitud.put(
                "numeroSolicitud", numeroSolicitud);

        resumenSolicitud.put(
                "idSolicitud", solicitud.get("id"));

        resumenSolicitud.put(
                "estadoSolicitud",
                solicitud.get("estado_credito"));

        resumenSolicitud.put(
                "montoSolicitado", montoSolicitado);

        resumenSolicitud.put(
                "plazoMeses", plazoMeses);

        resumenSolicitud.put(
                "destinoCredito",
                solicitud.get("destino_credito"));

        resumenSolicitud.put(
                "empresaRegistrada",
                empresaRegistrada);

        resumenSolicitud.put(
                "tasaInteresMensualRegistrada",
                solicitud.get("tasa_interes_mensual"));

        resumenSolicitud.put(
                "frecuenciaPagoRegistrada",
                solicitud.get("frecuencia_pago"));

        resumenSolicitud.put(
                "cantidadCuotasRegistrada",
                solicitud.get("cantidad_cuotas"));

        resumenSolicitud.put(
                "valorCuotaRegistrada",
                solicitud.get("valor_cuota"));

        // =================================================
        // RESUMEN ASOCIADO
        // =================================================

        Map<String, Object> resumenAsociado =
                new LinkedHashMap<>();

        resumenAsociado.put(
                "idAsociado", idAsociado);

        resumenAsociado.put(
                "documento", documento);

        resumenAsociado.put(
                "nombre", nombre);

        // Opcionales: no confundir afiliacion al fondo con ingreso laboral.
        // Se utilizan UNICAMENTE columnas realmente presentes en tblAsociado.
        // FEMPROBIEN.dbo.tblAsociado.fec_ing: fecha real de ingreso al fondo.
        // Se prioriza frente a alias historicos; no utilizar ingreso laboral NovaWEB.
        Object fechaAfiliacion = campoAsociadoOpcional(asociado,
                "fec_ing", "fecha_ingreso_fondo", "fec_ingreso_fondo", "fecha_afiliacion",
                "fec_afiliacion", "fch_afiliacion", "fecha_vinculacion_fondo",
                "fechaIngresoFondo", "fechaAfiliacionFondo", "fec_aso",
                "fecha_afi", "fec_afi", "fch_afi", "fec_ing_aso",
                "fecha_ing_aso", "fecha_asociacion", "fec_asociacion",
                "fecha_admision", "fec_admision", "fecha_vinculacion");
        resumenAsociado.put("fechaIngresoFondo", fechaAfiliacion);

        Object cargoRegistrado = campoAsociadoOpcional(asociado,
                "cargo", "nombre_cargo", "nom_cargo", "des_cargo",
                "cargo_empleado", "cargoActual", "puesto", "ocupacion",
                "nom_car", "des_car", "descripcion_cargo");
        // La ficha del fondo puede no guardar el cargo laboral. Solo en ese
        // caso se consulta rhh_emplea, validando empresa/documento y la
        // existencia de una descripcion legible (no solo un codigo de cargo).
        if (cargoRegistrado == null) {
            cargoRegistrado = consultarCargoLaboralNovaweb(empresaNomina, documento);
        }
        resumenAsociado.put("cargo", cargoRegistrado);
        if (fechaAfiliacion == null) {
            LOG.log(Level.INFO,
                    "FEMPROBIEN: fecha de afiliacion al fondo no disponible en tblAsociado para id_aso={0}",
                    idAsociado);
        }

        resumenAsociado.put(
                "empresaNominaConsultada",
                empresaNomina == null
                        ? "TODAS"
                        : empresaNomina);

        // =================================================
        // RESPUESTA COMPLETA
        // =================================================

        Map<String, Object> respuesta =
                new LinkedHashMap<>();

        respuesta.put("status", 200);

        respuesta.put(
                "tipoAnalisis",
                "ESTUDIO_FINANCIERO_PRELIMINAR");

        respuesta.put("modificaSolicitud", false);
        respuesta.put("decisionAutomatica", false);

        respuesta.put("solicitud", resumenSolicitud);
        respuesta.put("asociado", resumenAsociado);

        respuesta.put("nomina", nomina);

        respuesta.put(
                "resultadosBusquedaNomina",
                resultadosNomina);

        respuesta.put(
                "nominaDosQuincenasEncontradas",
                dosQuincenasEncontradas);

        respuesta.put(
                "nominaValidadaParaMotor",
                nominaValidadaParaMotor);

        respuesta.put(
                "deduccionesMensualesObservadas",
                deduccionesObservadas);
        respuesta.put("deduccionesHistoricasParaEscenario",
                escenarioHistoricoDisponible ? deduccionesHistoricas : null);
        respuesta.put("escenarioHistoricoDisponible",
                escenarioHistoricoDisponible);

        respuesta.put(
                "deduccionesMensualesValidadas",
                nominaValidadaParaMotor
                        ? deduccionesValidadas
                        : null);

        respuesta.put("cartera", cartera);
        respuesta.put("diagnosticoVigenciaCartera",
                cartera.get("diagnosticoVigenciaCartera"));
        respuesta.put("cupoHistorico", cupoHistorico);
        respuesta.put("simulacion", simulacion);

        respuesta.put(
                "cuotaNuevaMensual",
                cuotaNuevaMensual);

        respuesta.put(
                "estadoSimulacion",
                estadoSimulacion);

        respuesta.put(
                "indicadoresFinancieros",
                indicadores);

        // =================================================
        // NUEVO RESUMEN PARA EL AGENTE FEMPROBIEN
        // =================================================

        Map<String, Object> resumenAgente =
                construirResumenFinancieroAgente(
                        nomina,
                        indicadores,
                        cartera);

        // =================================================
        // ESTADO GENERAL DEL ESTUDIO: RESULTADO DINAMICO
        // =================================================
        // El motor evalua los criterios numericos con sus propias
        // comprobaciones de evidencia. El estado de la solicitud
        // (APROBADA, NEGADA...) NO certifica una reevaluacion.
        String estadoMotor = texto(
                indicadores.get("estadoAnalisisFinanciero"));
        Object escenarioObj = indicadores.get("escenarioObservado");
        Map<?, ?> escenarioObservado = escenarioObj instanceof Map
                ? (Map<?, ?>) escenarioObj : Collections.emptyMap();
        boolean tieneTopeEstimado =
                escenarioObservado.get("porcentajeConNuevaCuota") != null;

        String estadoEstudio = resolverEstadoEstudio(
                estadoMotor,
                !pendientes.isEmpty(),
                tieneTopeEstimado);

        // El resultado ilustrativo se informa aparte del estado tecnico
        // del motor: no convierte deducciones observadas en certificadas.
        boolean superaLimiteEstimado = Boolean.TRUE.equals(
                escenarioObservado.get("superaLimiteEnEscenario"));
        boolean dentroLimiteEstimado = Boolean.FALSE.equals(
                escenarioObservado.get("superaLimiteEnEscenario"));
        String resultadoEscenarioEstimado = superaLimiteEstimado
                ? "EXCEDE_LIMITE_ESTIMADO"
                : dentroLimiteEstimado
                ? "DENTRO_DEL_LIMITE_ESTIMADO" : "SIN_DATOS_SUFICIENTES";
        String resultadoEstudio = describirResultadoEstudio(
                estadoEstudio, superaLimiteEstimado, dentroLimiteEstimado);
        // El estado del estudio NO equivale al grado de evidencia disponible.
        // Su detalle se determina más adelante a partir de comprobaciones
        // individuales, sin certificar datos observados de NovaWEB.
        String estadoValidacionEstudio = "PENDIENTE";

        BigDecimal totalProyectadoEstimado = decimal(
                escenarioObservado.get("deduccionesConNuevaCuota"));
        BigDecimal limiteMensual = decimal(
                indicadores.get("limiteDescuentosMensuales"));
        BigDecimal excesoEstimadoPesos = superaLimiteEstimado &&
                totalProyectadoEstimado != null && limiteMensual != null
                ? totalProyectadoEstimado.subtract(limiteMensual)
                .max(BigDecimal.ZERO)
                : null;
        BigDecimal topeEstimado = decimal(
                escenarioObservado.get("porcentajeConNuevaCuota"));
        BigDecimal topeReglamentario = decimal(
                indicadores.get("porcentajeMaximoDeducciones"));
        BigDecimal excesoEstimadoPuntos = superaLimiteEstimado &&
                topeEstimado != null && topeReglamentario != null
                ? topeEstimado.subtract(topeReglamentario)
                .max(BigDecimal.ZERO)
                : null;

        Map<String, Object> diagnosticoEstado =
                new LinkedHashMap<>();
        diagnosticoEstado.put("estadoMotor", estadoMotor);
        diagnosticoEstado.put("estadoEstudio", estadoEstudio);
        diagnosticoEstado.put("estadoSolicitudOriginal",
                solicitud.get("estado_credito"));
        diagnosticoEstado.put("resultadoTopeObservado",
                indicadores.get("resultadoTopeObservado"));
        diagnosticoEstado.put("topeObservadoEsCertificado", false);
        diagnosticoEstado.put("estadoNominaComoFuente",
                nomina == null ? "NO_ENCONTRADA" :
                        nomina.get("estadoValidacionDeducciones"));
        diagnosticoEstado.put("estadoCarteraComoFuente",
                cartera.get("estadoCarteraParaEstudio"));
        diagnosticoEstado.put("certificacionExternaDisponible", false);
        diagnosticoEstado.put("incumplimientosComprobados",
                indicadores.get("incumplimientos"));
        diagnosticoEstado.put("cantidadValidacionesPendientes",
                pendientes.size());
        diagnosticoEstado.put("tieneTopeEstimado", tieneTopeEstimado);
        diagnosticoEstado.put("topeProyectadoEstimado",
                escenarioObservado.get("porcentajeConNuevaCuota"));
        diagnosticoEstado.put("escenarioSuperaLimiteEstimado",
                escenarioObservado.get("superaLimiteEnEscenario"));
        diagnosticoEstado.put("resultadoEscenarioEstimado",
                resultadoEscenarioEstimado);
        diagnosticoEstado.put("resultadoEstudio", resultadoEstudio);
        diagnosticoEstado.put("estadoValidacionEstudio",
                estadoValidacionEstudio);
        diagnosticoEstado.put("excesoEstimadoPesos", excesoEstimadoPesos);
        diagnosticoEstado.put("excesoEstimadoPuntosPorcentuales",
                excesoEstimadoPuntos);
        diagnosticoEstado.put("origenEscenario",
                escenarioObservado.get("tipo"));
        diagnosticoEstado.put("esResultadoCertificado",
                "CUMPLE".equals(estadoEstudio));
        diagnosticoEstado.put("esDecisionDeCredito", false);
        diagnosticoEstado.put("interpretacion",
                "NO_CUMPLE".equals(estadoEstudio)
                        ? "Hay incumplimientos de reglas comprobadas por el motor. " +
                        "Ver incumplimientosComprobados. No es un rechazo administrativo."
                        : "CUMPLE".equals(estadoEstudio)
                        ? "Los controles financieros previstos para este estudio " +
                        "se encuentran completos. No representa aprobacion."
                        : superaLimiteEstimado
                        ? "El escenario estimado supera el limite del 50%. " +
                        "No demuestra incumplimiento definitivo: faltan " +
                        "validar deducciones y obligaciones."
                        : "PRELIMINAR".equals(estadoEstudio)
                        ? "Existen indicadores ilustrativos de nomina, pero " +
                        "faltan verificaciones. El TOPE no esta certificado."
                        : "No existen datos suficientes para interpretar " +
                        "el escenario financiero ni certificar un resultado.");

        // El resumen y la respuesta superior deben publicar el MISMO estado.
        resumenAgente.put("estado", estadoEstudio);
        respuesta.put("resumenFinancieroAgente", resumenAgente);
        respuesta.put("estadoEstudio", estadoEstudio);
        respuesta.put("resultadoEstudio", resultadoEstudio);
        respuesta.put("estadoValidacionEstudio", estadoValidacionEstudio);
        respuesta.put("diagnosticoEstadoEstudio", diagnosticoEstado);
        respuesta.put("pendientes", pendientes);
        respuesta.put("estadoAdministrativoSolicitud",
                solicitud.get("estado_credito"));

        // Lista de comprobaciones por fuente: permite construir una
        // validacion futura sin inferir datos que la fuente no proporciona.
        List<Map<String, Object>> comprobaciones = new ArrayList<>();
        comprobaciones.add(controlVerificacion(
                "NOMINA_DOS_QUINCENAS",
                dosQuincenasEncontradas ? "VERIFICADO" : "PENDIENTE",
                "Existe evidencia de salario en las dos quincenas esperadas",
                "Consultar en NovaWEB la quincena faltante y su movimiento salarial"));
        comprobaciones.add(controlVerificacion(
                "DESCUENTOS_DE_NOMINA_OBSERVADOS",
                dosQuincenasEncontradas && deduccionesObservadas != null
                        ? "OBSERVADO" : "PENDIENTE",
                "La suma observada no equivale a obligaciones futuras certificadas",
                "Completar los periodos esperados y revisar sus movimientos"));
        String estadoObservacionalNomina = nomina == null ? null :
                texto(nomina.get("estadoObservacionalNomina"));
        comprobaciones.add(controlVerificacion(
                "NATURALEZA_Y_PERIODICIDAD_OBSERVADA",
                "MOVIMIENTOS_OBSERVADOS_CONCILIADOS".equals(
                        estadoObservacionalNomina) ? "OBSERVADO" : "PENDIENTE",
                "Identificacion automatica de conceptos; no acredita vigencia futura",
                "Revisar el detalle de conceptos y los movimientos no identificados"));
        comprobaciones.add(controlVerificacion(
                "DEDUCCIONES_RECURRENCIA_Y_VIGENCIA",
                nominaValidadaParaMotor ? "VERIFICADO" : "PENDIENTE",
                "Se necesita una certificacion de descuentos aplicables al mes proyectado",
                "Obtener de una fuente autorizada los descuentos vigentes y su periodicidad"));
        comprobaciones.add(controlVerificacion(
                "SALDOS_CARTERA_AL_CORTE",
                carteraActualizada && saldosCompletos && saldoValidado != null
                        ? "VERIFICADO_AL_CORTE" : "PENDIENTE",
                "Un saldo al corte no certifica movimientos posteriores",
                "Reconciliar los saldos con la ultima importacion y sus omisiones"));
        comprobaciones.add(controlVerificacion(
                "VIGENCIA_CONTRACTUAL_CARTERA",
                Boolean.TRUE.equals(diagnosticoVigencia.get(
                        "criterioVigenciaConfirmado")) ? "VERIFICADO" : "PENDIENTE",
                "La marca activo y el estado de cartera requieren criterio autorizado",
                "Confirmar cuales registros representan obligaciones vigentes"));
        comprobaciones.add(controlVerificacion(
                "CUOTA_REGISTRADA",
                simulacionValidada ? "VERIFICADO" : "PENDIENTE",
                "Se debe cotejar la cuota con tasa, plazo y frecuencia registrados",
                "Verificar las condiciones del credito almacenadas en la solicitud"));
        comprobaciones.add(controlVerificacion(
                "OBLIGACIONES_EXTERNAS_Y_SOLVENCIA", "PENDIENTE",
                "No hay fuente externa certificada en este flujo",
                "Obtener evidencia autorizada de las obligaciones externas"));
        comprobaciones.add(controlVerificacion(
                "CONDICIONES_ADICIONALES_REGLAMENTO", "PENDIENTE",
                "Afiliacion, antiguedad y requisitos no se evalúan desde estas fuentes",
                "Validar los requisitos aplicables con la administracion del Fondo"));
        int verificados = 0;
        int observados = 0;
        int sinVerificar = 0;
        List<Map<String, Object>> accionesPendientes = new ArrayList<>();
        for (Map<String, Object> control : comprobaciones) {
            String estadoControl = String.valueOf(control.get("estado"));
            if ("VERIFICADO".equals(estadoControl) ||
                    "VERIFICADO_AL_CORTE".equals(estadoControl)) {
                verificados++;
            } else if ("OBSERVADO".equals(estadoControl)) {
                observados++;
            } else {
                sinVerificar++;
                Map<String, Object> accion = new LinkedHashMap<>();
                accion.put("codigo", control.get("codigo"));
                accion.put("accionRequerida", control.get("accionRequerida"));
                accionesPendientes.add(accion);
            }
        }
        // Solo un CUMPLE respaldado por TODAS las comprobaciones
        // permite exponer un estado global VERIFICADO.
        estadoValidacionEstudio = "CUMPLE".equals(estadoEstudio) && sinVerificar == 0
                ? "VERIFICADO"
                : (verificados > 0 || observados > 0)
                ? "VALIDACION_PARCIAL" : "SIN_EVIDENCIA_SUFICIENTE";
        String resumenValidacion = "VERIFICADO".equals(estadoValidacionEstudio)
                ? "Todos los controles exigidos para el estudio fueron verificados"
                : "Hay " + verificados + " controles verificados, " + observados +
                " observados y " + sinVerificar + " pendientes de evidencia";

        Map<String, Object> avanceVerificacion = new LinkedHashMap<>();
        avanceVerificacion.put("estado", estadoValidacionEstudio);
        avanceVerificacion.put("verificados", verificados);
        avanceVerificacion.put("observados", observados);
        avanceVerificacion.put("pendientes", sinVerificar);
        avanceVerificacion.put("resumen", resumenValidacion);
        avanceVerificacion.put("accionesPendientes", accionesPendientes);
        avanceVerificacion.put("certificaCapacidadDePago",
                "VERIFICADO".equals(estadoValidacionEstudio));
        respuesta.put("comprobacionesEstudio", comprobaciones);
        respuesta.put("avanceVerificacion", avanceVerificacion);
        // Reconciliar las copias publicadas del estado una sola vez.
        respuesta.put("estadoValidacionEstudio", estadoValidacionEstudio);
        diagnosticoEstado.put("estadoValidacionEstudio", estadoValidacionEstudio);
        diagnosticoEstado.put("cantidadControlesVerificados", verificados);
        diagnosticoEstado.put("cantidadControlesObservados", observados);
        diagnosticoEstado.put("cantidadControlesPendientes", sinVerificar);
        diagnosticoEstado.put("accionesPendientes", accionesPendientes);

        // Diagnóstico detallado sin modificar indicadores ni autorizar
        // cálculos con saldos o deducciones no certificados.
        respuesta.put("validacionNomina", construirValidacionNomina(nomina));
        respuesta.put("validacionCartera", construirValidacionCartera(cartera));

        // Separa las reglas del reglamento de las verificaciones
        // tecnicas de origen, sin reinterpretar NO_CUMPLE ni
        // afirmar que existan datos definitivos.
        Map<String, Object> trazabilidad = new LinkedHashMap<>();
        trazabilidad.put("documento", "REGLAMENTO_CREDITO_FEMPROBIEN");
        trazabilidad.put("vigenteDesde", "2026-08-01");
        trazabilidad.put("decisionAutomatica", false);
        trazabilidad.put("criterios",
                indicadores.get("criteriosReglamentarios"));
        trazabilidad.put("nota",
                "Los estados del motor comprueban solo las " +
                        "reglas numericas implementadas. La aprobacion " +
                        "requiere estudio integral por el organo competente.");
        respuesta.put("trazabilidadReglamentaria", trazabilidad);

        Map<String, Object> controlFuentes = new LinkedHashMap<>();
        controlFuentes.put("controlesDelMotor",
                indicadores.get("controlesTecnicos"));
        controlFuentes.put("nominaMensualCompleta", dosQuincenasEncontradas);
        controlFuentes.put("deduccionesMensualesVerificadas",
                nominaValidadaParaMotor);
        controlFuentes.put("carteraVerificadaAlCorte",
                carteraActualizada && saldosCompletos &&
                        saldoValidado != null);
        controlFuentes.put("clasificacionVigenciaCreditoVerificada",
                Boolean.TRUE.equals(diagnosticoVigencia.get("criterioVigenciaConfirmado")));
        controlFuentes.put("cuotaSimuladaCoincideConSolicitud",
                simulacionValidada);
        controlFuentes.put("validacionObligacionesExternas", "PENDIENTE");
        controlFuentes.put("verificacionIngresosPrestacionales",
                "PENDIENTE_FUENTE_AUTORIZADA");
        controlFuentes.put("evidenciaConceptosDeducciones",
                nomina == null ? Collections.emptyList() :
                        nomina.get("evidenciaConceptosDeducciones"));
        controlFuentes.put("motivosCertificacionNomina",
                nomina == null ? Collections.singletonList("SIN_NOMINA_VERIFICABLE") :
                        nomina.get("motivosPendientesCertificacionDeducciones"));
        controlFuentes.put("estadoCarteraParaEstudio",
                cartera.get("estadoCarteraParaEstudio"));
        controlFuentes.put("resultadoTopeObservado",
                indicadores.get("resultadoTopeObservado"));
        respuesta.put("validacionesTecnicasEstudio", controlFuentes);

        respuesta.put(
                "mensaje",
                "NO_CUMPLE".equals(estadoEstudio)
                        ? "Se identificaron incumplimientos comprobados de reglas " +
                        "evaluadas. No constituye rechazo administrativo."
                        : "CUMPLE".equals(estadoEstudio)
                        ? "Cumple los criterios verificados del estudio. " +
                        "No constituye aprobacion de credito."
                        : superaLimiteEstimado
                        ? "Atencion: el TOPE proyectado estimado supera el 50%. " +
                        "Pendiente de verificar obligaciones y deducciones."
                        : "PRELIMINAR".equals(estadoEstudio)
                        ? "Resultado preliminar disponible. Faltan verificaciones " +
                        "de obligaciones y deducciones antes de certificarlo."
                        : "Estudio pendiente de evidencia financiera suficiente.");

        return respuesta;
    }

    // =====================================================
    // 2. CONSTRUIR RESUMEN FINANCIERO DEL AGENTE
    // =====================================================

    @SuppressWarnings("unchecked")
    private Map<String, Object> construirValidacionNomina(
            Map<String, Object> nomina) {
        Map<String, Object> resultado = new LinkedHashMap<>();
        if (nomina == null) {
            resultado.put("estado", "SIN_NOMINA_VERIFICABLE");
            return resultado;
        }
        resultado.put("estado", nomina.get("estadoValidacionDeducciones"));
        resultado.put("nominaMensualCompleta", nomina.get("nominaMensualCompleta"));
        resultado.put("cantidadConceptosPendientesValidacion",
                nomina.get("cantidadConceptosPendientesValidacion"));
        resultado.put("cantidadMovimientosEspecialesPendientes",
                nomina.get("cantidadMovimientosEspecialesPendientes"));
        resultado.put("periodicidadPendienteConfirmacion",
                nomina.get("periodicidadPendienteConfirmacion"));
        resultado.put("detalleConceptosPendientesValidacion",
                nomina.get("detalleConceptosPendientesValidacion"));
        // Solo evidencia observada: no cambiar a saldo validado.
        resultado.put("deduccionesMensualesObservadas",
                nomina.get("deduccionesMensualesObservadas"));
        resultado.put("escenarioHistoricoDisponible",
                nomina.get("escenarioHistoricoDisponible"));
        resultado.put("deduccionesHistoricasParaEscenario",
                nomina.get("deduccionesHistoricasParaEscenario"));
        resultado.put("fechasBaseEscenarioHistorico",
                nomina.get("fechasBaseEscenarioHistorico"));
        resultado.put("deduccionesValidadasParaProyeccion",
                nomina.get("deduccionesValidadasParaProyeccion"));
        resultado.put("evidenciaConceptosDeducciones",
                nomina.get("evidenciaConceptosDeducciones"));
        resultado.put("motivosPendientesCertificacionDeducciones",
                nomina.get("motivosPendientesCertificacionDeducciones"));
        return resultado;
    }

    private Map<String, Object> construirValidacionCartera(
            Map<String, Object> cartera) {
        Map<String, Object> resultado = new LinkedHashMap<>();
        if (cartera == null) {
            resultado.put("estado", "SIN_CARTERA_VERIFICABLE");
            return resultado;
        }
        resultado.put("estado", cartera.get("estadoVerificacionCartera"));
        resultado.put("motivosPendientesCartera",
                cartera.get("motivosPendientesCartera"));
        resultado.put("fechaCorteEsperada", cartera.get("fechaCorteEsperada"));
        resultado.put("fechaCorteUltimaImportacion",
                cartera.get("fechaCorteUltimaImportacion"));
        resultado.put("cantidadCreditos", cartera.get("cantidadCreditos"));
        resultado.put("cantidadCreditosSinCorte",
                cartera.get("cantidadCreditosSinCorte"));
        resultado.put("cantidadSaldosInvalidos",
                cartera.get("cantidadSaldosInvalidos"));
        resultado.put("saldoCreditosVerificado",
                cartera.get("saldoCreditosVerificado"));
        resultado.put("corteHistoricoUtilizable",
                cartera.get("corteHistoricoUtilizable"));
        resultado.put("saldoCreditosUltimoCorte",
                cartera.get("saldoCreditosUltimoCorte"));
        resultado.put("fechaCorteHistoricoUtilizable",
                cartera.get("fechaCorteHistoricoUtilizable"));
        resultado.put("diagnosticoVigenciaCartera",
                cartera.get("diagnosticoVigenciaCartera"));
        resultado.put("estadoCarteraParaEstudio",
                cartera.get("estadoCarteraParaEstudio"));
        resultado.put("vigenciaContractualConfirmada",
                cartera.get("vigenciaContractualConfirmada"));
        return resultado;
    }

    private Map<String, Object> construirResumenFinancieroAgente(
            Map<String, Object> nomina,
            Map<String, Object> indicadores,
            Map<String, Object> cartera) {

        Map<String, Object> resumen =
                new LinkedHashMap<>();

        Map<String, Object> ingresos =
                new LinkedHashMap<>();

        List<Map<String, Object>> deducciones =
                new ArrayList<>();

        List<String> observaciones =
                new ArrayList<>();

        BigDecimal salario = null;
        BigDecimal comisionesHorasExtras = null;
        BigDecimal totalIngresosEstimados = null;
        BigDecimal totalDeduccionesObservadas = null;

        boolean nominaCompleta = false;

        // =================================================
        // INGRESOS
        // =================================================

        if (nomina != null) {

            salario = decimal(
                    nomina.get("salarioBasicoMensual"));

            comisionesHorasExtras = decimal(
                    nomina.get(
                            "comisionesHorasExtrasMensuales"));

            totalIngresosEstimados = decimal(
                    nomina.get(
                            "ingresosMensualesEstimados"));

            totalDeduccionesObservadas = decimal(
                    nomina.get("deduccionesMensualesObservadas"));
            // Solo para presentar la referencia historica que ya muestra
            // el motor; no cambia la evidencia ni el estado de nomina.
            if (totalDeduccionesObservadas == null &&
                    Boolean.TRUE.equals(nomina.get("escenarioHistoricoDisponible"))) {
                totalDeduccionesObservadas = decimal(
                        nomina.get("deduccionesHistoricasParaEscenario"));
            }

            nominaCompleta =
                    Boolean.TRUE.equals(
                            nomina.get("nominaMensualCompleta"));
        }

        ingresos.put("salario", salario);

        ingresos.put(
                "comisionesHorasExtras",
                comisionesHorasExtras);

        /*
         * Actualmente NovaWEB no expone un campo
         * independiente de bonificaciones dentro
         * del consolidado que estamos utilizando.
         *
         * No asumir que null equivale a cero.
         */
        ingresos.put(
                "bonificaciones", null);

        ingresos.put(
                "totalIngresosEstimados",
                totalIngresosEstimados);

        ingresos.put(
                "origenSalario",
                nomina != null
                        ? nomina.get("origenSalario")
                        : null);

        ingresos.put(
                "bonificacionesVerificadas",
                false);

        // =================================================
        // DEDUCCIONES DETALLADAS
        // =================================================

        if (nomina != null) {

            Object detalles =
                    nomina.get(
                            "deduccionesConsolidadasDetalle");

            if (detalles instanceof List) {

                for (Object elemento : (List<?>) detalles) {

                    if (!(elemento instanceof Map)) {
                        continue;
                    }

                    Map<String, Object> concepto =
                            (Map<String, Object>) elemento;

                    // Excluir ingresos salariales y
                    // movimientos que no son descuentos.
                    if (!Boolean.TRUE.equals(
                            concepto.get(
                                    "incluirEnDescuentosObservados"))) {
                        continue;
                    }

                    Map<String, Object> detalle =
                            new LinkedHashMap<>();

                    detalle.put(
                            "codigo",
                            concepto.get("codigo"));

                    detalle.put(
                            "concepto",
                            concepto.get("concepto"));

                    detalle.put(
                            "grupo",
                            concepto.get("grupo"));

                    detalle.put(
                            "tipoMovimiento",
                            concepto.get("tipoMovimiento"));

                    detalle.put(
                            "valorObservado",
                            decimal(concepto.get("valor")));

                    detalle.put(
                            "quincenasConMovimiento",
                            concepto.get(
                                    "quincenasConMovimiento"));

                    detalle.put(
                            "periodicidad",
                            concepto.get("periodicidad"));

                    detalle.put(
                            "evidenciaRecurrencia",
                            concepto.get(
                                    "evidenciaRecurrencia"));

                    detalle.put(
                            "recurrenciaFuturaConfirmada",
                            concepto.get(
                                    "recurrenciaFuturaConfirmada"));

                    detalle.put(
                            "requiereValidacion",
                            concepto.get("requiereValidacion"));

                    detalle.put(
                            "requiereValidacionPeriodicidad",
                            concepto.get(
                                    "requiereValidacionPeriodicidad"));

                    detalle.put(
                            "aptoParaProyeccionAutomatica",
                            concepto.get(
                                    "aptoParaProyeccionAutomatica"));

                    deducciones.add(detalle);
                }
            }
        }

        // =================================================
        // ESCENARIO FINANCIERO EXISTENTE
        // =================================================

        Map<String, Object> escenarioObservado = null;

        if (indicadores != null) {

            Object escenario =
                    indicadores.get("escenarioObservado");

            if (escenario instanceof Map) {

                escenarioObservado =
                        (Map<String, Object>) escenario;
            }
        }

        // =================================================
        // RESUMEN DE INGRESOS Y DESCUENTOS
        // =================================================

        resumen.put(
                "tipoResumen",
                "NOMINA_OBSERVADA_PARA_AGENTE");

        resumen.put(
                "nominaMensualCompleta",
                nominaCompleta);
        resumen.put("tipoBaseEscenario",
                escenarioObservado == null ? "SIN_ESCENARIO" :
                        escenarioObservado.get("tipo"));
        resumen.put("fechasNominaBaseEscenario",
                escenarioObservado == null ? null :
                        escenarioObservado.get("fechasNominaBase"));

        resumen.put(
                "ingresos",
                ingresos);

        resumen.put(
                "deducciones",
                deducciones);

        resumen.put(
                "cantidadConceptosDeduccion",
                deducciones.size());

        resumen.put(
                "totalDeduccionesObservadas",
                totalDeduccionesObservadas);

        resumen.put(
                "deduccionesMensualesValidadas",
                indicadores != null
                        ? indicadores.get(
                        "deduccionesMensuales")
                        : null);

        resumen.put(
                "porcentajeDeduccionesObservadas",
                escenarioObservado != null
                        ? escenarioObservado.get(
                        "porcentajeDeduccionesObservadas")
                        : null);

        // =================================================
        // NUEVO CREDITO
        // =================================================

        resumen.put(
                "cuotaNuevaMensual",
                indicadores != null
                        ? indicadores.get("cuotaNuevaMensual")
                        : null);

        resumen.put(
                "totalConNuevaCuotaIlustrativo",
                escenarioObservado != null
                        ? escenarioObservado.get(
                        "deduccionesConNuevaCuota")
                        : null);

        resumen.put(
                "porcentajeConNuevaCuotaIlustrativo",
                escenarioObservado != null
                        ? escenarioObservado.get(
                        "porcentajeConNuevaCuota")
                        : null);

        // =================================================
        // TOPE DE DEDUCCIONES
        // =================================================

        resumen.put(
                "porcentajeMaximoDeducciones",
                indicadores != null
                        ? indicadores.get(
                        "porcentajeMaximoDeducciones")
                        : null);

        resumen.put(
                "limiteDescuentosMensuales",
                indicadores != null
                        ? indicadores.get(
                        "limiteDescuentosMensuales")
                        : null);

        resumen.put(
                "diferenciaFrenteLimiteIlustrativa",
                escenarioObservado != null
                        ? escenarioObservado.get(
                        "diferenciaFrenteLimite")
                        : null);

        resumen.put(
                "superaLimiteEnEscenario",
                escenarioObservado != null
                        ? escenarioObservado.get(
                        "superaLimiteEnEscenario")
                        : null);

        // =================================================
        // SALDO DISPONIBLE
        // =================================================

        /*
         * El saldo del formato de referencia
         * no coincide con la resta simple de
         * ingresos menos descuentos.
         *
         * Mantener null hasta confirmar la formula
         * de FEMPROBIEN.
         */
        resumen.put(
                "saldoDisponible",
                null);

        // =================================================
        // OBSERVACIONES GENERALES
        // =================================================

        if (!nominaCompleta) {

            observaciones.add(
                    "No se dispone de dos quincenas " +
                            "consecutivas completas.");
        }

        if (nomina != null) {

            if (Boolean.TRUE.equals(
                    nomina.get(
                            "requiereValidacionDeducciones"))) {

                observaciones.add(
                        "Existen conceptos de nomina cuya " +
                                "naturaleza o periodicidad debe " +
                                "verificarse antes de su proyeccion.");
            }

            Object conceptosPendientes =
                    nomina.get(
                            "conceptosPendientesValidacion");

            if (conceptosPendientes instanceof List &&
                    !((List<?>) conceptosPendientes).isEmpty()) {

                observaciones.add(
                        "Existen conceptos que requieren " +
                                "validacion adicional: " +
                                conceptosPendientes.toString());
            }
        }

        observaciones.add(
                "Los importes de descuento corresponden " +
                        "a las dos quincenas consultadas. " +
                        "No son necesariamente obligaciones " +
                        "mensuales futuras confirmadas.");

        observaciones.add(
                "El salario corresponde al valor " +
                        "actual registrado en NovaWEB.");

        observaciones.add(
                "Las comisiones, horas extras y " +
                        "bonificaciones requieren confirmar " +
                        "su origen y tratamiento para " +
                        "el estudio financiero.");

        observaciones.add(
                "La formula del saldo disponible del " +
                        "formato FEMPROBIEN debe confirmarse.");

        // =================================================
        // VERIFICACION DE CREDITOS ACTIVOS
        // =================================================

        if (cartera != null) {

            Object creditos = cartera.get("creditos");

            if (creditos instanceof List) {

                for (Object elemento : (List<?>) creditos) {

                    if (!(elemento instanceof Map)) {
                        continue;
                    }

                    Map<String, Object> credito =
                            (Map<String, Object>) elemento;

                    String estado =
                            texto(credito.get("estado"));

                    BigDecimal saldo =
                            decimal(credito.get("valor_pagar"));

                    if ("ACTIVO".equalsIgnoreCase(estado) &&
                            saldo != null &&
                            saldo.signum() == 0) {

                        observaciones.add(
                                "Existe un credito FEMPROBIEN " +
                                        "registrado como ACTIVO con " +
                                        "saldo cero al corte. " +
                                        "Debe verificarse su estado " +
                                        "real y si aun genera " +
                                        "descuentos de nomina.");

                        break;
                    }
                }
            }
        }

        // =================================================
        // ESTADO DEL RESUMEN
        // =================================================

        // Estado provisional: evaluarSolicitud lo sustituye por el
        // resultado dinamico calculado a partir del motor financiero.
        resumen.put(
                "estado",
                "REQUIERE_VALIDACION");

        resumen.put(
                "observaciones",
                observaciones);

        return resumen;
    }

    // =====================================================
    // 3. SIMULAR CON CONDICIONES REGISTRADAS
    // =====================================================

    private Map<String, Object> simularSolicitudRegistrada(
            Map<String, Object> solicitud,
            BigDecimal monto,
            Integer plazo) {

        Map<String, Object> resultado =
                new LinkedHashMap<>();

        BigDecimal tasa = decimal(
                solicitud.get("tasa_interes_mensual"));

        BigDecimal cuotaRegistrada = decimal(
                solicitud.get("valor_cuota"));

        Integer cantidadRegistrada = entero(
                solicitud.get("cantidad_cuotas"));

        String frecuencia = texto(
                solicitud.get("frecuencia_pago"));

        if (frecuencia != null) {
            frecuencia =
                    frecuencia.toUpperCase(Locale.ROOT);
        }

        /*
         * Compatibilidad con solicitudes antiguas
         * que utilizan descuento_ambas_quincenas.
         */
        if (frecuencia == null) {

            Object ambas = solicitud.get(
                    "descuento_ambas_quincenas");

            if (ambas != null) {

                String valor =
                        ambas.toString().trim();

                boolean quincenal =
                        Boolean.TRUE.equals(ambas) ||
                                "1".equals(valor) ||
                                "true".equalsIgnoreCase(valor);

                frecuencia = quincenal
                        ? "QUINCENAL"
                        : "MENSUAL";
            }
        }

        resultado.put(
                "estado", "REQUIERE_VALIDACION");

        resultado.put("monto", monto);
        resultado.put("plazoMeses", plazo);
        resultado.put("frecuencia", frecuencia);
        resultado.put("tasaMensual", tasa);

        resultado.put(
                "cuotaRegistrada", cuotaRegistrada);

        resultado.put(
                "cantidadCuotasRegistrada",
                cantidadRegistrada);

        resultado.put("cuotaCalculada", null);
        resultado.put("cantidadCuotasCalculada", null);

        resultado.put(
                "cuotaMensualEquivalente", null);

        resultado.put("interesesTotales", null);
        resultado.put("totalPagar", null);

        resultado.put(
                "origenTasa", "SOLICITUD_CREDITO");

        /*
         * No sustituir la tasa historica con la
         * tasa vigente de nuevas solicitudes.
         */
        if (tasa == null) {

            resultado.put(
                    "estado",
                    "FALTA_TASA_REGISTRADA");

            resultado.put(
                    "observacion",
                    "La solicitud no conserva una tasa " +
                            "mensual verificable. No se puede " +
                            "recalcular con la tasa vigente.");

            return resultado;
        }

        if (frecuencia == null ||
                (!"MENSUAL".equals(frecuencia) &&
                        !"QUINCENAL".equals(frecuencia))) {

            resultado.put(
                    "estado",
                    "FALTA_FRECUENCIA");

            resultado.put(
                    "observacion",
                    "No se pudo identificar la frecuencia " +
                            "de pago de la solicitud.");

            return resultado;
        }

        if (cuotaRegistrada == null ||
                cantidadRegistrada == null) {

            resultado.put(
                    "estado",
                    "FALTAN_CUOTAS_REGISTRADAS");

            resultado.put(
                    "observacion",
                    "La solicitud no conserva el valor " +
                            "o cantidad de cuotas necesarios " +
                            "para verificar la simulación.");

            return resultado;
        }

        try {

            Map<String, Object> calculo =
                    simuladorService.calcular(
                            monto,
                            plazo,
                            frecuencia,
                            tasa);

            BigDecimal cuotaCalculada = decimal(
                    calculo.get("cuota"));

            Integer cantidadCalculada = entero(
                    calculo.get("cantidadCuotas"));

            resultado.put(
                    "cuotaCalculada",
                    cuotaCalculada);

            resultado.put(
                    "cantidadCuotasCalculada",
                    cantidadCalculada);

            resultado.put(
                    "interesesTotales",
                    calculo.get("interesesTotales"));

            resultado.put(
                    "totalPagar",
                    calculo.get("total"));

            resultado.put(
                    "requiereDeudor",
                    calculo.get("requiereDeudor"));

            boolean cuotaCoincide =
                    cuotaCalculada != null &&
                            cuotaRegistrada.compareTo(
                                    cuotaCalculada) == 0;

            boolean cantidadCoincide =
                    cantidadCalculada != null &&
                            cantidadRegistrada.equals(
                                    cantidadCalculada);

            resultado.put(
                    "cuotaCoincide", cuotaCoincide);

            resultado.put(
                    "cantidadCuotasCoincide",
                    cantidadCoincide);

            if (!cuotaCoincide || !cantidadCoincide) {

                resultado.put(
                        "estado",
                        "DIFERENCIA_CON_SOLICITUD");

                resultado.put(
                        "observacion",
                        "La cuota o cantidad de cuotas " +
                                "registradas no coincide con " +
                                "la simulación reconstruida. " +
                                "Se requiere revisión administrativa.");

                return resultado;
            }

            /*
             * Dos cuotas quincenales corresponden
             * a una equivalencia mensual.
             */
            BigDecimal cuotaMensual =
                    "QUINCENAL".equals(frecuencia)
                            ? cuotaRegistrada.multiply(DOS)
                            : cuotaRegistrada;

            resultado.put(
                    "cuotaMensualEquivalente",
                    cuotaMensual);

            resultado.put(
                    "estado", "VALIDADA");

            resultado.put(
                    "observacion",
                    "La cuota y cantidad de cuotas " +
                            "coinciden con las condiciones " +
                            "registradas en la solicitud.");

        } catch (IllegalArgumentException |
                 ArithmeticException e) {

            resultado.put(
                    "estado",
                    "ERROR_VALIDACION_SIMULACION");

            resultado.put(
                    "observacion",
                    e.getMessage());
        }

        return resultado;
    }

    private static Map<String, Object> controlVerificacion(
            String codigo, String estado, String detalle, String accionRequerida) {
        Map<String, Object> control = new LinkedHashMap<>();
        control.put("codigo", codigo);
        control.put("estado", estado);
        control.put("detalle", detalle);
        control.put("accionRequerida", accionRequerida);
        return control;
    }

    // =====================================================
    // 4. RESOLVER ESTADO DEL ESTUDIO SIN APROBACION AUTOMATICA
    // =====================================================

    static String resolverEstadoEstudio(
            String estadoMotor,
            boolean hayPendientesComplementarios,
            boolean tieneTopeEstimado) {

        // Un incumplimiento comprobado NO se oculta porque existan
        // otros datos pendientes o la solicitud figure APROBADA.
        if ("NO_CUMPLE".equals(estadoMotor)) {
            return "NO_CUMPLE";
        }

        // Solo se afirma CUMPLE cuando el motor confirma el resultado
        // y ya no quedan verificaciones complementarias.
        if ("CUMPLE".equals(estadoMotor)) {
            return hayPendientesComplementarios
                    ? "PRELIMINAR" : "CUMPLE";
        }

        // Con evidencia parcial se puede mostrar un estudio preliminar,
        // nunca un cumplimiento certificado. Incluye TOPE estimado > 50%:
        // se conserva como alerta orientativa, NO como incumplimiento probado.
        if ("REQUIERE_VALIDACION".equals(estadoMotor) &&
                tieneTopeEstimado) {
            return "PRELIMINAR";
        }

        return "REQUIERE_VALIDACION";
    }

    // Descripcion de negocio para las vistas; no cambia estadoEstudio
    // ni autoriza un credito. Un exceso estimado sigue siendo preliminar.
    static String describirResultadoEstudio(
            String estadoEstudio,
            boolean superaLimiteEstimado,
            boolean dentroLimiteEstimado) {
        if ("NO_CUMPLE".equals(estadoEstudio)) {
            return "No cumple criterios evaluados";
        }
        if ("CUMPLE".equals(estadoEstudio)) {
            return "Cumple criterios verificados";
        }
        if (superaLimiteEstimado) {
            return "Excede el límite en el escenario estimado";
        }
        if (dentroLimiteEstimado) {
            return "Dentro del límite en el escenario estimado";
        }
        return "PRELIMINAR".equals(estadoEstudio)
                ? "Estudio preliminar" : "Pendiente de verificación";
    }

    // =====================================================
    // 4. UTILIDADES
    // =====================================================

    private String texto(Object valor) {

        if (valor == null) {
            return null;
        }

        String resultado = valor.toString().trim();

        return resultado.isEmpty() ? null : resultado;
    }

    private BigDecimal decimal(Object valor) {

        if (valor == null ||
                valor.toString().trim().isEmpty()) {
            return null;
        }

        return new BigDecimal(valor.toString());
    }

    private Integer entero(Object valor) {

        if (valor == null ||
                valor.toString().trim().isEmpty()) {
            return null;
        }

        return new BigDecimal(
                valor.toString()).intValueExact();
    }

    private String normalizarEmpresa(String nombre) {

        if (nombre == null) {
            return null;
        }

        String valor =
                nombre.trim().toUpperCase(Locale.ROOT);

        if (valor.equals("WALI") ||
                valor.startsWith("WALI ")) {
            return "WALI";
        }

        if (valor.equals("IGB") ||
                valor.startsWith("IGB ")) {
            return "IGB";
        }

        if (valor.equals("MOTOZONE") ||
                valor.startsWith("MOTOZONE ")) {
            return "MTZ";
        }

        if (valor.equals("MTZ")) {
            return "MTZ";
        }

        if (valor.equals("MOTOREPUESTOS") ||
                valor.startsWith("MOTOREPUESTOS ")) {
            return "MOTOREPUESTOS";
        }

        // Empresas no normalizadas:
        // consultar las bases autorizadas NovaWEB.
        return null;
    }

    /**
     * Busca solo campos cuyo nombre indique de forma explicita el dato requerido.
     * Tolera diferencias de guiones, espacios o mayusculas entre esquemas.
     */
    private static Object campoAsociadoOpcional(Map<String, Object> fila,
                                                String... nombres) {
        if (fila == null) return null;
        for (String nombre : nombres) {
            String esperado = normalizarNombreColumna(nombre);
            for (Map.Entry<String, Object> campo : fila.entrySet()) {
                if (campo.getKey() != null &&
                        esperado.equals(normalizarNombreColumna(campo.getKey())) &&
                        campo.getValue() != null &&
                        !String.valueOf(campo.getValue()).trim().isEmpty()) {
                    return campo.getValue();
                }
            }
        }
        return null;
    }

    private static String normalizarNombreColumna(String valor) {
        return valor == null ? "" : valor.replaceAll("[^a-zA-Z0-9]", "")
                .toLowerCase(Locale.ROOT);
    }

    /**
     * Fuente secundaria autorizada SOLO para el cargo laboral.
     * Nunca utiliza la fecha de ingreso laboral de NovaWEB como fecha
     * de afiliacion a FEMPROBIEN.
     */
    private Object consultarCargoLaboralNovaweb(String empresa, String documento) {
        if (empresa == null || documento == null) return null;
        final String base;
        switch (empresa.toUpperCase(Locale.ROOT)) {
            case "IGB": base = "IGB_NOVAWEB"; break;
            case "WALI": base = "WALI_NOVAWEB"; break;
            case "MTZ": base = "MTZ_NOVAWEB"; break;
            case "MOTOREPUESTOS": base = "MOTOREPUESTOS_NOVAWEB"; break;
            default: return null;
        }
        try {
            List<Map<String, Object>> empleados = jdbc.queryForList(
                    "SELECT TOP (1) * FROM [" + base + "].[dbo].[rhh_emplea] " +
                            "WHERE cod_emp = ?", documento);
            if (empleados.isEmpty()) return null;
            return campoAsociadoOpcional(empleados.get(0),
                    "cargo", "nombre_cargo", "nom_cargo", "des_cargo",
                    "des_car", "nom_car", "cargo_actual", "nombreCargo",
                    "descripcion_cargo", "puesto", "ocupacion");
        } catch (Exception ex) {
            // Los esquemas NovaWEB pueden variar; no interrumpir la evaluacion.
            LOG.log(Level.WARNING,
                    "No se pudo consultar el cargo laboral en NovaWEB para {0}: {1}",
                    new Object[]{empresa, ex.getClass().getSimpleName()});
            return null;
        }
    }
}

