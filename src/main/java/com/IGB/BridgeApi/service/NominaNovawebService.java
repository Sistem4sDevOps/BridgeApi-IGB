package com.IGB.BridgeApi.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class NominaNovawebService {

    private static final ZoneId ZONA =
            ZoneId.of("America/Bogota");

    private static final int MAX_QUINCENAS = 24;
    // Limite tecnico de antiguedad para mostrar una referencia historica.
    private static final long MAX_EDAD_ESCENARIO_HISTORICO_DIAS = 45;

    private static final BigDecimal CERO =
            new BigDecimal("0.00");

    private final JdbcTemplate jdbc;
    private final ClasificadorDeduccionesService clasificador;

    private static final Map<String, String> BASES;

    static {
        Map<String, String> bases = new LinkedHashMap<>();

        bases.put("ALVEZ", "ALVEZ_NOVAWEB");
        bases.put("DSM", "DSM_NOVAWEB");
        bases.put("FOUNDR", "FOUNDR_NOVAWEB");
        bases.put("IGB", "IGB_NOVAWEB");
        bases.put("IMPO", "IMPO_NOVAWEB");
        bases.put("INVERSUR", "INVERSUR_NOVAWEB");
        bases.put("MODULA", "MODULA_NOVAWEB");
        bases.put("MOTOREPUESTOS", "MOTOREPUESTOS_NOVAWEB");
        bases.put("MTZ", "MTZ_NOVAWEB");
        bases.put("VILNA", "VILNA_NOVAWEB");
        bases.put("WALI", "WALI_NOVAWEB");

        BASES = Collections.unmodifiableMap(bases);
    }

    public NominaNovawebService(
            @Qualifier("sqlServerJdbcTemplate") JdbcTemplate jdbc,
            ClasificadorDeduccionesService clasificador) {

        this.jdbc = jdbc;
        this.clasificador = clasificador;
    }

    // =====================================================
    // BASES AUTORIZADAS
    // =====================================================

    private Map<String, String> seleccionarBases(String empresa) {

        if (empresa == null || empresa.trim().isEmpty()) {
            return BASES;
        }

        String clave =
                empresa.trim().toUpperCase(Locale.ROOT);

        if ("MOTOZONE".equals(clave)) {
            clave = "MTZ";
        }

        if (clave.endsWith("_NOVAWEB")) {
            clave = clave.substring(
                    0, clave.length() - "_NOVAWEB".length());
        }

        String base = BASES.get(clave);

        if (base == null) {
            throw new IllegalArgumentException(
                    "Empresa no configurada: " + empresa);
        }

        Map<String, String> seleccion = new LinkedHashMap<>();
        seleccion.put(clave, base);

        return seleccion;
    }

    private String tabla(String base, String nombre) {

        if (!BASES.containsValue(base)) {
            throw new IllegalArgumentException(
                    "Base de datos no autorizada");
        }

        return "[" + base + "].[dbo].[" + nombre + "]";
    }

    private boolean existeEmpleado(
            String base, String identificacion) {

        String sql =
                "SELECT COUNT(1) FROM " +
                        tabla(base, "rhh_emplea") +
                        " WHERE cod_emp = ?";

        Integer cantidad = jdbc.queryForObject(
                sql,
                new Object[]{identificacion},
                Integer.class);

        return cantidad != null && cantidad > 0;
    }

    // =====================================================
    // CALENDARIO DE NOMINA
    // =====================================================

    private LocalDate ajustarPago(LocalDate fecha) {

        DayOfWeek dia = fecha.getDayOfWeek();

        if (dia == DayOfWeek.SATURDAY) {
            return fecha.minusDays(1);
        }

        if (dia == DayOfWeek.SUNDAY) {
            return fecha.minusDays(2);
        }

        return fecha;
    }

    private LocalDate ultimaQuincena(LocalDate hoy) {

        LocalDate finMes = hoy.with(
                TemporalAdjusters.lastDayOfMonth());

        LocalDate quince = hoy.withDayOfMonth(15);

        if (hoy.isAfter(ajustarPago(finMes))) {
            return finMes;
        }

        if (hoy.isAfter(ajustarPago(quince))) {
            return quince;
        }

        return hoy.minusMonths(1).with(
                TemporalAdjusters.lastDayOfMonth());
    }

    private LocalDate quincenaAnterior(
            LocalDate fechaProgramada) {

        if (fechaProgramada.getDayOfMonth() == 15) {
            return fechaProgramada.minusMonths(1).with(
                    TemporalAdjusters.lastDayOfMonth());
        }

        return fechaProgramada.withDayOfMonth(15);
    }

    // =====================================================
    // CONSULTA SQL NOVAWEB
    // =====================================================

    private String sqlNomina(String base) {

        String movimientos =
                tabla(base, "rhh_intconta_detalle");

        String empleados =
                tabla(base, "rhh_emplea");

        return String.join(" ",
                "WITH Movimientos AS (",
                " SELECT d.cod_emp,",
                " RTRIM(d.cod_con) AS codigo,",
                " LTRIM(RTRIM(d.des_con)) AS concepto,",
                " SUM(COALESCE(d.deb_mov,0) -",
                " COALESCE(d.cre_mov,0)) AS valor",
                " FROM " + movimientos + " d",
                " WHERE d.cod_emp = ?",
                " AND d.fch_doc >= ?",
                " AND d.fch_doc < DATEADD(DAY,1,?)",
                " AND d.cod_con <> '999901'",
                " AND (d.des_con IS NULL OR",
                " LTRIM(RTRIM(d.des_con)) <> 'Neto a Pagar')",
                " GROUP BY d.cod_emp, RTRIM(d.cod_con),",
                " LTRIM(RTRIM(d.des_con))",
                " HAVING SUM(COALESCE(d.deb_mov,0) -",
                " COALESCE(d.cre_mov,0)) <> 0",
                "), Clasificados AS (",
                " SELECT cod_emp,codigo,concepto,valor,",
                " CASE",
                " WHEN codigo IN (",
                " '001050','001300','001500',",
                " '001146','001560','001565','001600',",
                " '999902','999905')",
                " THEN 'MOVIMIENTO_ESPECIAL'",
                " WHEN valor < 0 THEN 'DEDUCCION'",
                // Recargos y horas extras positivos: solo ingresos variables observados.
                " WHEN codigo IN (",
                " '100001','001060','001061','001062',",
                " '001063','001064','100020')",
                " OR concepto COLLATE Latin1_General_CI_AI",
                " LIKE '%comisi%'",
                " OR concepto COLLATE Latin1_General_CI_AI",
                " LIKE '%extra%'",
                " THEN 'COMISION_HE'",
                " ELSE 'OTRO' END AS categoria",
                " FROM Movimientos",
                "), Totales AS (",
                " SELECT cod_emp,",
                " SUM(CASE WHEN categoria='COMISION_HE'",
                " THEN valor ELSE 0 END) AS comisiones,",
                " SUM(CASE WHEN categoria IN",
                " ('DEDUCCION','COMISION_HE')",
                " OR (codigo='001050' AND valor > 0)",
                " THEN 1 ELSE 0 END) AS movimientosUtiles,",
                // Evidencia de salario en el período, no salario de ficha rhh_emplea.
                " MAX(CASE WHEN codigo='001050' AND valor > 0",
                " THEN 1 ELSE 0 END) AS salarioMovimientoRegistrado",
                " FROM Clasificados GROUP BY cod_emp",
                ")",
                " SELECT",
                " LTRIM(RTRIM(e.cod_emp)) AS codEmp,",
                " LTRIM(RTRIM(CONCAT(",
                " RTRIM(e.nom_emp),' ',",
                " RTRIM(e.ap1_emp),' ',",
                " RTRIM(e.ap2_emp)))) AS empleado,",
                " CAST(e.sal_bas AS DECIMAL(18,2))",
                " AS salarioBasico,",
                " CAST(COALESCE(t.comisiones,0)",
                " AS DECIMAL(18,2)) AS comisiones,",
                " t.salarioMovimientoRegistrado AS salarioMovimientoRegistrado,",
                " d.codigo AS codigoMovimiento,",
                " d.concepto AS conceptoMovimiento,",
                " d.categoria AS categoriaMovimiento,",
                " CAST(d.valor AS DECIMAL(18,2))",
                " AS valorMovimiento",
                " FROM " + empleados + " e",
                " INNER JOIN Totales t",
                " ON t.cod_emp=e.cod_emp",
                " AND t.movimientosUtiles > 0",
                " LEFT JOIN Clasificados d",
                " ON d.cod_emp=e.cod_emp",
                " AND d.categoria IN",
                " ('DEDUCCION','COMISION_HE','MOVIMIENTO_ESPECIAL')",
                " WHERE e.cod_emp=?",
                " ORDER BY d.codigo,d.concepto"
        );
    }

    private BigDecimal decimal(Object valor) {

        return valor == null
                ? CERO
                : new BigDecimal(valor.toString());
    }

    // =====================================================
    // CONSULTAR UNA QUINCENA
    // =====================================================

    private Map<String, Object> consultarPeriodo(
            String empresa,
            String base,
            String identificacion,
            LocalDate fechaDocumento) {

        Date fecha = Date.valueOf(fechaDocumento);

        List<Map<String, Object>> filas =
                jdbc.queryForList(
                        sqlNomina(base),
                        identificacion,
                        fecha,
                        fecha,
                        identificacion);

        if (filas.isEmpty()) {
            return null;
        }

        Map<String, Object> primera = filas.get(0);

        BigDecimal salario =
                decimal(primera.get("salarioBasico"));

        BigDecimal comisiones =
                decimal(primera.get("comisiones"));

        // La ficha de empleado no prueba por sí misma que se pagó el período.
        boolean salarioMovimientoRegistrado =
                decimal(primera.get("salarioMovimientoRegistrado"))
                        .signum() > 0;

        BigDecimal totalIngresos =
                salario.add(comisiones);

        BigDecimal totalDeducciones = CERO;

        List<Map<String, Object>> deducciones =
                new ArrayList<>();

        List<Map<String, Object>> especiales =
                new ArrayList<>();

        // Ingresos variables detallados por concepto de nómina.
        List<Map<String, Object>> comisionesDetalle =
                new ArrayList<>();

        for (Map<String, Object> fila : filas) {

            Object codigo = fila.get("codigoMovimiento");

            if (codigo == null) {
                continue;
            }

            String categoria = String.valueOf(
                    fila.get("categoriaMovimiento"));

            BigDecimal valor =
                    decimal(fila.get("valorMovimiento"));

            Map<String, Object> item =
                    new LinkedHashMap<>();

            item.put("codigo", codigo.toString().trim());
            item.put(
                    "concepto",
                    fila.get("conceptoMovimiento"));

            if ("DEDUCCION".equals(categoria)) {

                BigDecimal descuento = valor.negate();

                item.put("valor", descuento);

                /*
                 * Se conserva como deduccion observada
                 * segun la clasificacion contable.
                 *
                 * La validacion de naturaleza y
                 * periodicidad ocurre posteriormente.
                 */
                deducciones.add(item);

                totalDeducciones =
                        totalDeducciones.add(descuento);

            } else if ("COMISION_HE".equals(categoria)) {

                item.put("valor", valor);
                // Recargos, horas extras y comisiones: no son salario básico
                // ni descuentos; requieren validación para proyección.
                item.putAll(clasificador.clasificarIngresoVariable(
                        codigo.toString().trim()));
                comisionesDetalle.add(item);

            } else if ("MOVIMIENTO_ESPECIAL".equals(
                    categoria)) {

                item.put("valor", valor);
                especiales.add(item);
            }
        }

        Map<String, Object> resultado =
                new LinkedHashMap<>();

        resultado.put("estado", "ENCONTRADO");
        resultado.put("empresa", empresa);
        resultado.put("baseDatos", base);
        resultado.put("codEmp", primera.get("codEmp"));
        resultado.put("empleado", primera.get("empleado"));
        resultado.put("salarioBasico", salario);
        resultado.put("salarioMovimientoRegistrado",
                salarioMovimientoRegistrado);
        resultado.put("fuenteEvidenciaSalarioPeriodo",
                "MOVIMIENTO_NOMINA_001050");

        resultado.put(
                "comisionesHorasExtras",
                comisiones);

        resultado.put("totalIngresos", totalIngresos);

        // Detalle de ingresos variables sin modificar totales existentes.
        resultado.put("comisionesDetalle", comisionesDetalle);

        resultado.put(
                "deducciones",
                clasificador.clasificarLista(deducciones));

        resultado.put(
                "movimientosEspeciales",
                clasificador.clasificarLista(especiales));

        resultado.put(
                "totalDeducciones",
                totalDeducciones);

        /*
         * Compatibilidad con respuestas anteriores.
         * No equivale al neto real de una quincena.
         */
        resultado.put(
                "saldoCalculado",
                totalIngresos.subtract(totalDeducciones));

        resultado.put(
                "fechaNominaUtilizada",
                fechaDocumento.toString());

        return resultado;
    }

    private Map<String, Object> consultarQuincena(
            String empresa,
            String base,
            String identificacion,
            LocalDate programada) {

        LocalDate efectiva = ajustarPago(programada);

        Map<String, Object> nomina =
                consultarPeriodo(
                        empresa, base,
                        identificacion, efectiva);

        if (nomina == null &&
                !efectiva.equals(programada)) {

            nomina = consultarPeriodo(
                    empresa, base,
                    identificacion, programada);
        }

        if (nomina != null) {

            nomina.put(
                    "fechaProgramadaPago",
                    programada.toString());

            /*
             * Correccion:
             * Informar la fecha de los movimientos
             * efectivamente encontrados.
             */
            nomina.put(
                    "fechaEfectivaPago",
                    nomina.get("fechaNominaUtilizada"));
        }

        return nomina;
    }

    // =====================================================
    // BUSQUEDA DE QUINCENAS ANTERIORES
    // =====================================================

    private List<Map<String, Object>> buscarPeriodos(
            String empresa,
            String base,
            String identificacion,
            LocalDate hoy,
            int cantidad) {

        List<Map<String, Object>> encontrados =
                new ArrayList<>();

        LocalDate programada = ultimaQuincena(hoy);

        for (int i = 0; i < MAX_QUINCENAS; i++) {

            Map<String, Object> nomina =
                    consultarQuincena(
                            empresa, base,
                            identificacion, programada);

            if (nomina != null) {

                nomina.put(
                        "fechaConsulta",
                        hoy.toString());

                nomina.put(
                        "quincenasRetrocedidas",
                        i);

                LocalDate fechaNomina =
                        LocalDate.parse(
                                String.valueOf(
                                        nomina.get(
                                                "fechaNominaUtilizada")));

                nomina.put(
                        "diasAntiguedadNomina",
                        ChronoUnit.DAYS.between(
                                fechaNomina, hoy));

                encontrados.add(nomina);

                if (encontrados.size() >= cantidad) {
                    break;
                }
            }

            programada = quincenaAnterior(programada);
        }

        return encontrados;
    }

    // =====================================================
    // CONSOLIDAR DEDUCCIONES Y SU EVIDENCIA
    // =====================================================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> agruparDeducciones(
            List<Map<String, Object>> nominas,
            boolean periodosEsperadosCompletos) {

        Map<String, Map<String, Object>> agrupadas =
                new LinkedHashMap<>();

        Map<String, Set<Integer>> presencias =
                new LinkedHashMap<>();

        // Evidencia por cada una de las quincenas ESPERADAS.
        // Se registra por separado para facilitar la revisión.
        Map<String, Map<Integer, BigDecimal>> valoresPorPeriodo =
                new LinkedHashMap<>();

        for (Map<String, Object> nomina : nominas) {

            int indice = ((Number) nomina.get(
                    "quincenasRetrocedidas")).intValue();

            List<Map<String, Object>> deducciones =
                    (List<Map<String, Object>>)
                            nomina.get("deducciones");

            for (Map<String, Object> item : deducciones) {

                String codigo =
                        String.valueOf(item.get("codigo"));

                String concepto =
                        String.valueOf(item.get("concepto"));

                String clave = codigo + "|" + concepto;

                BigDecimal valor =
                        decimal(item.get("valor"));

                Map<String, Object> acumulado =
                        agrupadas.get(clave);

                if (acumulado == null) {

                    acumulado = new LinkedHashMap<>();

                    acumulado.put("codigo", codigo);
                    acumulado.put("concepto", concepto);
                    acumulado.put("valor", CERO);

                    agrupadas.put(clave, acumulado);

                    presencias.put(
                            clave, new LinkedHashSet<Integer>());
                    valoresPorPeriodo.put(
                            clave, new LinkedHashMap<Integer, BigDecimal>());
                }

                BigDecimal anterior =
                        decimal(acumulado.get("valor"));

                acumulado.put(
                        "valor",
                        anterior.add(valor));

                presencias.get(clave).add(indice);
                Map<Integer, BigDecimal> porPeriodo =
                        valoresPorPeriodo.get(clave);
                BigDecimal valorAnterior = porPeriodo.get(indice);
                porPeriodo.put(indice,
                        (valorAnterior == null ? CERO : valorAnterior)
                                .add(valor));
            }
        }

        for (Map.Entry<String, Map<String, Object>> entrada :
                agrupadas.entrySet()) {

            Map<String, Object> acumulado =
                    entrada.getValue();

            Set<Integer> indices =
                    presencias.get(entrada.getKey());

            boolean primera = indices.contains(0);
            boolean segunda = indices.contains(1);
            boolean ambas = primera && segunda;

            Map<Integer, BigDecimal> porPeriodo =
                    valoresPorPeriodo.get(entrada.getKey());
            acumulado.put("valorPrimeraQuincenaObservado",
                    porPeriodo.get(0));
            acumulado.put("valorSegundaQuincenaObservado",
                    porPeriodo.get(1));
            acumulado.put("evidenciaDosQuincenas",
                    ambas);

            acumulado.put(
                    "quincenasConMovimiento",
                    indices.size());

            acumulado.put(
                    "observadoEnAmbasQuincenasEsperadas",
                    ambas);

            acumulado.put(
                    "evidenciaRecurrencia",
                    ambas
                            ? "OBSERVADO_EN_AMBAS_QUINCENAS"
                            : "NO_OBSERVADO_EN_AMBAS_QUINCENAS");

            /*
             * La evidencia de dos quincenas
             * no certifica recurrencia futura.
             */
            acumulado.put(
                    "recurrenciaFuturaConfirmada",
                    false);
        }

        // El clasificador reconoce el tipo de descuento usando el código.
        // La periodicidad observada solo puede determinarse DESPUÉS de
        // comprobar qué quincenas esperadas se encontraron realmente.
        List<Map<String, Object>> detalle = clasificador.clasificarLista(
                new ArrayList<>(agrupadas.values()));

        for (Map<String, Object> item : detalle) {
            boolean reconocido = Boolean.TRUE.equals(
                    item.get("codigoReconocido"));
            boolean esDeduccion = Boolean.TRUE.equals(
                    item.get("esDeduccionNomina"));
            boolean enAmbas = Boolean.TRUE.equals(
                    item.get("observadoEnAmbasQuincenasEsperadas"));
            // La evidencia histórica se informa por separado: por ejemplo,
            // octubre 15 y 31 son períodos consecutivos, pero no sustituyen
            // el pago esperado del 15 de noviembre.
            String clave = String.valueOf(item.get("codigo")) + "|" +
                    String.valueOf(item.get("concepto"));
            Set<Integer> indices = presencias.get(clave);
            boolean historicoConsecutivo = indices != null &&
                    indices.size() == 2 &&
                    Collections.max(indices) - Collections.min(indices) == 1;
            item.put("evidenciaHistoricaConsecutiva",
                    historicoConsecutivo);
            boolean periodicidadObservada =
                    periodosEsperadosCompletos && reconocido &&
                            esDeduccion && enAmbas;

            // Dos registros de las últimas quincenas RECUPERADAS no
            // acreditan periodicidad si se omitió alguna quincena esperada.
            item.put("periodicidadObservadaEnNomina",
                    periodicidadObservada);

            if (periodicidadObservada) {
                // Evidencia de periodicidad, NO garantía de
                // que la deducción se repetirá en el futuro.
                item.put("periodicidad", "QUINCENAL_OBSERVADA");
                item.put("requiereValidacionPeriodicidad", false);
                item.put("observacionPeriodicidad",
                        "Se encontró el descuento en las dos quincenas " +
                                "consecutivas esperadas. Falta certificar vigencia futura.");
                // Las etiquetas de tratamiento también deben ser coherentes
                // con la evidencia: ya no falta comprobar la repetición
                // histórica, pero sigue faltando autorizar su proyección.
                if (Boolean.TRUE.equals(item.get("requiereValidacion"))) {
                    item.put("tratamientoProyeccion",
                            "VALIDAR_NATURALEZA_Y_VIGENCIA");
                    item.put("observacionClasificacion",
                            "Descuento reconocido y repetido en las " +
                                    "quincenas esperadas. Confirmar tratamiento " +
                                    "y vigencia antes de proyectarlo.");
                } else {
                    item.put("tratamientoProyeccion",
                            "CONFIRMAR_VIGENCIA_FUTURA");
                    item.put("observacionClasificacion",
                            "Descuento reconocido y repetido en las " +
                                    "quincenas esperadas; no constituye " +
                                    "certificación de descuento futuro.");
                }
            } else {
                item.put("observacionPeriodicidad",
                        historicoConsecutivo && !periodosEsperadosCompletos
                                ? "Se observaron dos quincenas históricas " +
                                "consecutivas; falta completar las dos " +
                                "quincenas vigentes esperadas."
                                : "No hay evidencia suficiente en las dos " +
                                "quincenas consecutivas esperadas.");
            }

            // IMPORTANTE: reconocer periodicidad observada nunca equivale
            // a aprobar el descuento para una proyección automática.
            item.put("aptoParaProyeccionAutomatica", false);
            item.put("recurrenciaFuturaConfirmada", false);
        }

        return detalle;
    }

    // =====================================================
    // CONSOLIDAR COMISIONES / HORAS EXTRAS OBSERVADAS
    // =====================================================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> agruparComisiones(
            List<Map<String, Object>> nominas,
            boolean periodosEsperadosCompletos) {

        Map<String, Map<String, Object>> agrupadas =
                new LinkedHashMap<>();
        Map<String, Set<Integer>> presencias =
                new LinkedHashMap<>();
        Map<String, Map<Integer, BigDecimal>> valoresPorPeriodo =
                new LinkedHashMap<>();

        for (Map<String, Object> nomina : nominas) {

            int indice = ((Number) nomina.get(
                    "quincenasRetrocedidas")).intValue();

            List<Map<String, Object>> movimientos =
                    (List<Map<String, Object>>)
                            nomina.get("comisionesDetalle");

            if (movimientos == null) {
                continue;
            }

            for (Map<String, Object> item : movimientos) {

                String codigo = String.valueOf(item.get("codigo"));
                String concepto = String.valueOf(item.get("concepto"));
                String clave = codigo + "|" + concepto;
                BigDecimal valor = decimal(item.get("valor"));

                Map<String, Object> acumulado = agrupadas.get(clave);

                if (acumulado == null) {
                    acumulado = new LinkedHashMap<>();
                    acumulado.put("codigo", codigo);
                    acumulado.put("concepto", concepto);
                    acumulado.put("valor", CERO);
                    acumulado.put("grupo", "INGRESO_VARIABLE");
                    acumulado.put("tipoIngresoVariable",
                            item.get("tipoIngresoVariable"));
                    acumulado.put("origenEvidenciaIngreso",
                            "NOMINA_OBSERVADA");
                    agrupadas.put(clave, acumulado);
                    presencias.put(clave, new LinkedHashSet<Integer>());
                    valoresPorPeriodo.put(
                            clave, new LinkedHashMap<Integer, BigDecimal>());
                }

                acumulado.put("valor",
                        decimal(acumulado.get("valor")).add(valor));
                presencias.get(clave).add(indice);

                Map<Integer, BigDecimal> porPeriodo =
                        valoresPorPeriodo.get(clave);
                BigDecimal anterior = porPeriodo.get(indice);
                porPeriodo.put(indice,
                        (anterior == null ? CERO : anterior).add(valor));
            }
        }

        for (Map.Entry<String, Map<String, Object>> entrada :
                agrupadas.entrySet()) {

            String clave = entrada.getKey();
            Map<String, Object> acumulado = entrada.getValue();
            Set<Integer> indices = presencias.get(clave);
            Map<Integer, BigDecimal> porPeriodo =
                    valoresPorPeriodo.get(clave);

            acumulado.put("valorPrimeraQuincenaObservado",
                    porPeriodo.get(0));
            acumulado.put("valorSegundaQuincenaObservado",
                    porPeriodo.get(1));
            boolean enAmbas = periodosEsperadosCompletos &&
                    indices.contains(0) && indices.contains(1);
            acumulado.put("quincenasConMovimiento", indices.size());
            acumulado.put("observadoEnAmbasQuincenasEsperadas", enAmbas);
            acumulado.put("evidenciaRecurrencia",
                    enAmbas ? "OBSERVADO_EN_AMBAS_QUINCENAS" :
                            "NO_OBSERVADO_EN_AMBAS_QUINCENAS");
            acumulado.put("validacionIngreso", "PENDIENTE_VALIDACION");
            acumulado.put("motivoValidacionIngreso",
                    !periodosEsperadosCompletos ?
                            "Falta una de las dos quincenas esperadas." :
                            enAmbas ? "Ingreso variable observado en ambas " +
                                    "quincenas; falta confirmar recurrencia futura." :
                                    "Ingreso variable no observado en ambas " +
                                            "quincenas esperadas.");
            acumulado.put("aptoParaProyeccionAutomatica", false);
        }

        return new ArrayList<>(agrupadas.values());
    }

    // =====================================================
    // MOVIMIENTOS ESPECIALES POR REVISAR
    // =====================================================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>>
    movimientosEspecialesPendientes(
            List<Map<String, Object>> nominas) {

        Map<String, Map<String, Object>> pendientes =
                new LinkedHashMap<>();

        for (Map<String, Object> nomina : nominas) {

            List<Map<String, Object>> especiales =
                    (List<Map<String, Object>>)
                            nomina.get("movimientosEspeciales");

            for (Map<String, Object> item : especiales) {

                String tipo = String.valueOf(
                        item.get("tipoMovimiento"));

                /*
                 * El salario identificado como ingreso
                 * no es una deduccion pendiente.
                 */
                // El salario, el auxilio de transporte y los ingresos
                // extraordinarios identificados no son descuentos pendientes.
                // No se les suma al ingreso base validado.
                if (tipo.startsWith("INGRESO_") && Boolean.FALSE.equals(
                        item.get("esDeduccionNomina"))) {
                    continue;
                }

                String codigo = String.valueOf(
                        item.get("codigo"));

                String concepto = String.valueOf(
                        item.get("concepto"));

                String clave = codigo + "|" + concepto;

                Map<String, Object> acumulado =
                        pendientes.get(clave);

                if (acumulado == null) {

                    acumulado = new LinkedHashMap<>();

                    acumulado.put("codigo", codigo);
                    acumulado.put("concepto", concepto);
                    acumulado.put("valorObservado", CERO);
                    acumulado.put(
                            "motivo",
                            "Confirmar si el movimiento " +
                                    "es ingreso, deduccion o ajuste.");

                    pendientes.put(clave, acumulado);
                }

                BigDecimal anterior = decimal(
                        acumulado.get("valorObservado"));

                acumulado.put(
                        "valorObservado",
                        anterior.add(
                                decimal(item.get("valor"))));
            }
        }

        return new ArrayList<>(pendientes.values());
    }

    // =====================================================
    // CONSOLIDAR DOS QUINCENAS
    // =====================================================

    private Map<String, Object> consolidarEnBase(
            String empresa,
            String base,
            String identificacion,
            LocalDate hoy) {

        List<Map<String, Object>> nominas =
                buscarPeriodos(
                        empresa, base,
                        identificacion, hoy, 2);

        if (nominas.isEmpty()) {
            return null;
        }

        Map<String, Object> primera = nominas.get(0);

        LocalDate esperada1 = ultimaQuincena(hoy);
        LocalDate esperada2 =
                quincenaAnterior(esperada1);

        boolean tienePrimera = false;
        boolean tieneSegunda = false;
        boolean salarioPrimera = false;
        boolean salarioSegunda = false;

        BigDecimal totalEncontrado = CERO;
        BigDecimal totalComisiones = CERO;

        for (Map<String, Object> nomina : nominas) {

            int indice = ((Number) nomina.get(
                    "quincenasRetrocedidas")).intValue();

            boolean salarioEnPeriodo = Boolean.TRUE.equals(
                    nomina.get("salarioMovimientoRegistrado"));

            if (indice == 0) {
                tienePrimera = true;
                salarioPrimera = salarioEnPeriodo;
            }

            if (indice == 1) {
                tieneSegunda = true;
                salarioSegunda = salarioEnPeriodo;
            }

            totalEncontrado = totalEncontrado.add(
                    decimal(nomina.get("totalDeducciones")));

            totalComisiones = totalComisiones.add(
                    decimal(
                            nomina.get("comisionesHorasExtras")));
        }

        // No basta con movimientos de descuentos: exigir evidencia
        // de salario 001050 en ambas quincenas esperadas.
        boolean completa =
                tienePrimera && tieneSegunda &&
                        salarioPrimera && salarioSegunda;

        // Escenario HISTORICO: no sustituye el control de dos quincenas
        // esperadas. Solo usa dos periodos consecutivos RECUPERADOS,
        // ambos con evidencia de salario y una antiguedad maxima de 45 dias.
        boolean escenarioHistoricoDisponible = false;
        List<String> fechasBaseEscenarioHistorico = new ArrayList<>();
        Long antiguedadUltimaNominaEscenario = null;

        if (!completa && nominas.size() == 2) {
            Map<String, Object> reciente = nominas.get(0);
            Map<String, Object> anterior = nominas.get(1);
            int indiceReciente = ((Number) reciente.get(
                    "quincenasRetrocedidas")).intValue();
            int indiceAnterior = ((Number) anterior.get(
                    "quincenasRetrocedidas")).intValue();

            boolean ambosConSalario = Boolean.TRUE.equals(
                    reciente.get("salarioMovimientoRegistrado")) &&
                    Boolean.TRUE.equals(
                            anterior.get("salarioMovimientoRegistrado"));

            LocalDate fechaReciente = LocalDate.parse(String.valueOf(
                    reciente.get("fechaProgramadaPago")));
            long antiguedadDias = ChronoUnit.DAYS.between(fechaReciente, hoy);

            escenarioHistoricoDisponible = ambosConSalario &&
                    indiceAnterior == indiceReciente + 1 &&
                    antiguedadDias >= 0 && antiguedadDias <= MAX_EDAD_ESCENARIO_HISTORICO_DIAS;

            if (escenarioHistoricoDisponible) {
                fechasBaseEscenarioHistorico.add(String.valueOf(
                        anterior.get("fechaProgramadaPago")));
                fechasBaseEscenarioHistorico.add(String.valueOf(
                        reciente.get("fechaProgramadaPago")));
                antiguedadUltimaNominaEscenario = antiguedadDias;
            }
        }

        BigDecimal deduccionesHistoricasParaEscenario =
                escenarioHistoricoDisponible ? totalEncontrado : null;

        BigDecimal deduccionesMensuales =
                completa ? totalEncontrado : null;

        BigDecimal comisionesMensuales =
                completa ? totalComisiones : null;

        // Las dos quincenas vigentes o historicas consecutivas admitidas
        // determinan el ingreso variable OBSERVADO para el escenario.
        // No usar una sola quincena aislada como ingreso mensual.
        BigDecimal variablesParaEscenario =
                completa || escenarioHistoricoDisponible
                        ? totalComisiones : null;

        BigDecimal salario =
                decimal(primera.get("salarioBasico"));

        List<Map<String, Object>> detalle =
                agruparDeducciones(nominas, completa);

        List<Map<String, Object>> comisionesDetalle =
                agruparComisiones(nominas, completa);

        List<Map<String, Object>> especialesPendientes =
                movimientosEspecialesPendientes(nominas);

        // =================================================
        // ANALIZAR CONCEPTOS PENDIENTES
        // =================================================

        List<String> codigosPendientes =
                new ArrayList<>();

        List<Map<String, Object>> detallePendientes =
                new ArrayList<>();

        boolean periodicidadSinConfirmar = false;

        for (Map<String, Object> item : detalle) {

            String codigo =
                    String.valueOf(item.get("codigo"));

            boolean requiereValidacion =
                    Boolean.TRUE.equals(
                            item.get("requiereValidacion"));

            boolean apareceEnAmbas =
                    Boolean.TRUE.equals(
                            item.get(
                                    "observadoEnAmbasQuincenasEsperadas"));

            boolean periodicidadDesconocida =
                    Boolean.TRUE.equals(
                            item.get("requiereValidacionPeriodicidad"));

            if (periodicidadDesconocida) {
                periodicidadSinConfirmar = true;
            }

            /*
             * Priorizar:
             * 1. Conceptos cuya naturaleza sigue por validar.
             * 2. Conceptos sin periodicidad observada suficiente.
             * 3. Descuentos ausentes en una quincena esperada.
             */
            if (requiereValidacion ||
                    periodicidadDesconocida ||
                    (completa && !apareceEnAmbas)) {

                Map<String, Object> pendiente =
                        new LinkedHashMap<>();

                pendiente.put("codigo", codigo);

                pendiente.put(
                        "concepto",
                        item.get("concepto"));

                pendiente.put(
                        "valorObservado",
                        item.get("valor"));
                pendiente.put("grupo", item.get("grupo"));
                pendiente.put("periodicidad", item.get("periodicidad"));
                pendiente.put("quincenasConMovimiento",
                        item.get("quincenasConMovimiento"));
                pendiente.put("valorPrimeraQuincenaObservado",
                        item.get("valorPrimeraQuincenaObservado"));
                pendiente.put("valorSegundaQuincenaObservado",
                        item.get("valorSegundaQuincenaObservado"));

                pendiente.put(
                        "evidenciaRecurrencia",
                        item.get("evidenciaRecurrencia"));

                List<String> motivos = new ArrayList<>();

                if (requiereValidacion) {
                    motivos.add(
                            "Validar naturaleza y tratamiento " +
                                    "del concepto.");
                }

                if (completa && !apareceEnAmbas) {
                    motivos.add(
                            "No aparece en las dos " +
                                    "quincenas esperadas.");
                }
                if (periodicidadDesconocida) {
                    motivos.add(
                            "Falta evidencia de periodicidad en las dos " +
                                    "quincenas esperadas o una fuente " +
                                    "autorizada que la confirme.");
                }
                if (!periodicidadDesconocida && requiereValidacion) {
                    motivos.add("La periodicidad está observada, pero " +
                            "es necesario verificar la naturaleza o vigencia " +
                            "del concepto antes de proyectarlo.");
                }

                pendiente.put("motivos", motivos);

                detallePendientes.add(pendiente);

                if (!codigosPendientes.contains(codigo)) {
                    codigosPendientes.add(codigo);
                }
            }
        }

        // Diagnostico por concepto: observado NO equivale a certificado.
        List<Map<String, Object>> evidenciaConceptos = new ArrayList<>();
        for (Map<String, Object> concepto : detalle) {
            Map<String, Object> evidencia = new LinkedHashMap<>();
            evidencia.put("codigo", concepto.get("codigo"));
            evidencia.put("concepto", concepto.get("concepto"));
            evidencia.put("grupo", concepto.get("grupo"));
            evidencia.put("valorObservado", concepto.get("valor"));
            evidencia.put("quincenasConMovimiento",
                    concepto.get("quincenasConMovimiento"));
            evidencia.put("evidenciaRecurrencia",
                    concepto.get("evidenciaRecurrencia"));
            evidencia.put("estado", completa
                    ? (Boolean.TRUE.equals(concepto.get(
                    "observadoEnAmbasQuincenasEsperadas"))
                    ? "OBSERVADO_EN_AMBAS" : "OBSERVADO_PARCIAL")
                    : "PERIODOS_ESPERADOS_INCOMPLETOS");
            evidencia.put("verificadoParaProyeccion", false);
            evidencia.put("requiereConfirmacionVigencia", true);
            evidenciaConceptos.add(evidencia);
        }

        List<String> motivosCertificacion = new ArrayList<>();
        if (!completa) motivosCertificacion.add("NOMINA_ESPERADA_INCOMPLETA");
        if (!codigosPendientes.isEmpty()) {
            motivosCertificacion.add("CONCEPTOS_DESCUENTO_POR_VALIDAR");
        }
        if (!especialesPendientes.isEmpty()) {
            motivosCertificacion.add("MOVIMIENTOS_NO_IDENTIFICADOS");
        }
        if (periodicidadSinConfirmar) {
            motivosCertificacion.add("PERIODICIDAD_SIN_CONFIRMAR");
        }
        // NovaWEB evidencia descuentos realizados, no el saldo ni la
        // continuidad contractual de obligaciones futuras.
        motivosCertificacion.add("CERTIFICACION_EXTERNA_DE_DEDUCCIONES_PENDIENTE");

        boolean requiereRevision =
                !completa ||
                        !codigosPendientes.isEmpty() ||
                        !especialesPendientes.isEmpty() ||
                        periodicidadSinConfirmar;

        // =================================================
        // PERIODOS ESPERADOS
        // =================================================

        List<Map<String, Object>> esperados =
                new ArrayList<>();

        Map<String, Object> periodo1 =
                new LinkedHashMap<>();

        periodo1.put(
                "fechaProgramada",
                esperada1.toString());

        periodo1.put(
                "fechaEfectiva",
                ajustarPago(esperada1).toString());

        periodo1.put(
                "encontrado",
                tienePrimera);
        periodo1.put("salarioMovimientoEncontrado", salarioPrimera);

        esperados.add(periodo1);

        Map<String, Object> periodo2 =
                new LinkedHashMap<>();

        periodo2.put(
                "fechaProgramada",
                esperada2.toString());

        periodo2.put(
                "fechaEfectiva",
                ajustarPago(esperada2).toString());

        periodo2.put(
                "encontrado",
                tieneSegunda);
        periodo2.put("salarioMovimientoEncontrado", salarioSegunda);

        esperados.add(periodo2);

        // =================================================
        // RESPUESTA CONSOLIDADA
        // =================================================

        Map<String, Object> resultado =
                new LinkedHashMap<>();

        resultado.put("estado", "ENCONTRADO");
        resultado.put("empresa", empresa);
        resultado.put("baseDatos", base);

        resultado.put(
                "codEmp",
                primera.get("codEmp"));

        resultado.put(
                "empleado",
                primera.get("empleado"));

        resultado.put(
                "fechaConsulta",
                hoy.toString());

        resultado.put(
                "tipoConsolidacion",
                "DOS_ULTIMAS_QUINCENAS_MOVILES");

        resultado.put(
                "salarioBasicoMensual",
                salario);

        resultado.put(
                "periodosEsperados",
                esperados);

        resultado.put("nominas", nominas);

        resultado.put(
                "cantidadPeriodosEncontrados",
                nominas.size());

        resultado.put(
                "nominaMensualCompleta",
                completa);
        resultado.put("salarioEnAmbasQuincenasEsperadas",
                salarioPrimera && salarioSegunda);
        resultado.put("salarioPrimeraQuincenaEsperada", salarioPrimera);
        resultado.put("salarioSegundaQuincenaEsperada", salarioSegunda);

        resultado.put(
                "totalDeduccionesPeriodosEncontrados",
                totalEncontrado);

        /*
         * Se mantiene el campo original
         * para compatibilidad.
         *
         * Corresponde a dos quincenas moviles,
         * no a una certificacion de obligaciones.
         */
        resultado.put(
                "deduccionesMensuales",
                deduccionesMensuales);

        resultado.put(
                "deduccionesMensualesObservadas",
                deduccionesMensuales);

        // Campo independiente: una referencia historica NO es nomina
        // mensual completa ni deduccion validada para el motor.
        resultado.put("escenarioHistoricoDisponible",
                escenarioHistoricoDisponible);
        resultado.put("deduccionesHistoricasParaEscenario",
                deduccionesHistoricasParaEscenario);
        resultado.put("fechasBaseEscenarioHistorico",
                fechasBaseEscenarioHistorico);
        resultado.put("antiguedadUltimaNominaEscenarioDias",
                antiguedadUltimaNominaEscenario);

        /*
         * No certificar una proyeccion mensual
         * mientras no exista una validacion
         * de tratamiento y periodicidad.
         */
        resultado.put(
                "deduccionesValidadasParaProyeccion",
                null);

        resultado.put(
                "comisionesHorasExtrasMensuales",
                comisionesMensuales);

        // Campo adicional para el motor de capacidad de pago.
        // Incluye comisiones de venta, horas extras y recargos clasificados
        // como COMISION_HE, sin contar el salario ni otros conceptos.
        resultado.put("ingresosVariablesParaEscenario", variablesParaEscenario);
        resultado.put("ingresosVariablesHistoricosParaEscenario",
                escenarioHistoricoDisponible ? variablesParaEscenario : null);
        resultado.put("baseIngresosEscenarioEstimado",
                variablesParaEscenario != null
                        ? salario.add(variablesParaEscenario) : null);
        resultado.put("ingresosVariablesValidadosParaMotor", false);
        resultado.put("ingresosVariablesMensualesValidados", null);
        resultado.put("origenIngresosVariablesEscenario",
                completa ? "DOS_QUINCENAS_ESPERADAS_OBSERVADAS"
                        : escenarioHistoricoDisponible
                        ? "DOS_QUINCENAS_HISTORICAS_OBSERVADAS"
                        : "SIN_BASE_MENSUAL_SUFICIENTE");

        resultado.put(
                "comisionesConsolidadasDetalle",
                comisionesDetalle);

        resultado.put(
                "deduccionesConsolidadasDetalle",
                detalle);
        resultado.put("evidenciaConceptosDeducciones", evidenciaConceptos);
        resultado.put("motivosPendientesCertificacionDeducciones",
                motivosCertificacion);
        resultado.put("fuenteCertificacionDeducciones", null);
        resultado.put("deduccionesCertificadasPorFuenteAutorizada", false);

        resultado.put(
                "conceptosPendientesValidacion",
                codigosPendientes);

        resultado.put(
                "detalleConceptosPendientesValidacion",
                detallePendientes);

        resultado.put(
                "movimientosEspecialesPendientes",
                especialesPendientes);

        resultado.put(
                "requiereValidacionDeducciones",
                requiereRevision);

        // Campos adicionales y compatibles con los consumidores actuales.
        // Ninguno convierte una observación en obligación certificada.
        resultado.put("estadoValidacionDeducciones",
                requiereRevision ? "REQUIERE_REVISION" : "PENDIENTE_CERTIFICACION");
        resultado.put("cantidadConceptosPendientesValidacion",
                detallePendientes.size());
        resultado.put("cantidadMovimientosEspecialesPendientes",
                especialesPendientes.size());
        resultado.put("periodicidadPendienteConfirmacion",
                periodicidadSinConfirmar);
        resultado.put("origenValidacionDeducciones",
                "NOMINA_OBSERVADA_NO_CERTIFICADA");

        // Estado de comprobacion AUTOMATICA de los movimientos de nomina.
        // No se debe confundir con certificacion de obligaciones futuras.
        int conceptosEnAmbas = 0;
        int conceptosConNaturalezaPendiente = 0;
        int conceptosSinPeriodicidad = 0;
        for (Map<String, Object> concepto : detalle) {
            if (Boolean.TRUE.equals(concepto.get(
                    "observadoEnAmbasQuincenasEsperadas"))) {
                conceptosEnAmbas++;
            }
            if (Boolean.TRUE.equals(concepto.get("requiereValidacion"))) {
                conceptosConNaturalezaPendiente++;
            }
            if (Boolean.TRUE.equals(concepto.get(
                    "requiereValidacionPeriodicidad"))) {
                conceptosSinPeriodicidad++;
            }
        }

        String estadoObservacional = !completa
                ? "NOMINA_INCOMPLETA"
                : (!detallePendientes.isEmpty() ||
                !especialesPendientes.isEmpty())
                ? "REVISION_CONCEPTOS_PENDIENTE"
                : "MOVIMIENTOS_OBSERVADOS_CONCILIADOS";
        Map<String, Object> diagnosticoObservacional = new LinkedHashMap<>();
        diagnosticoObservacional.put("estado", estadoObservacional);
        diagnosticoObservacional.put("dosQuincenasConSalario", completa);
        diagnosticoObservacional.put("cantidadConceptosDescuento", detalle.size());
        diagnosticoObservacional.put("conceptosObservadosEnAmbasQuincenas",
                conceptosEnAmbas);
        diagnosticoObservacional.put("conceptosConNaturalezaPendiente",
                conceptosConNaturalezaPendiente);
        diagnosticoObservacional.put("conceptosSinPeriodicidadObservada",
                conceptosSinPeriodicidad);
        diagnosticoObservacional.put("movimientosEspecialesSinClasificar",
                especialesPendientes.size());
        diagnosticoObservacional.put("deduccionesObservadas",
                deduccionesMensuales);
        diagnosticoObservacional.put("deduccionesCertificadas", false);
        diagnosticoObservacional.put("accionParaCerrar",
                "Validar con fuente autorizada la vigencia y el importe futuro " +
                        "de las deducciones; NovaWEB solo evidencia descuentos realizados.");
        resultado.put("diagnosticoObservacionalNomina", diagnosticoObservacional);
        resultado.put("estadoObservacionalNomina", estadoObservacional);

        resultado.put(
                "fechaNominaMasReciente",
                primera.get("fechaNominaUtilizada"));

        resultado.put(
                "origenSalario",
                "RHH_EMPLEA_SAL_BAS_ACTUAL");

        resultado.put(
                "ingresosMensualesEstimados",
                completa
                        ? salario.add(comisionesMensuales)
                        : null);

        List<String> observaciones =
                new ArrayList<>();

        if (!completa) {
            observaciones.add(
                    "No están confirmadas las dos quincenas esperadas " +
                            "con movimiento de salario 001050. Los importes " +
                            "parciales no representan un mensual completo.");
        }
        if ((tienePrimera && !salarioPrimera) ||
                (tieneSegunda && !salarioSegunda)) {
            observaciones.add(
                    "Se encontraron movimientos de nómina sin evidencia " +
                            "de salario 001050 en alguna quincena esperada. " +
                            "Debe verificarse la fuente.");
        }

        if (!codigosPendientes.isEmpty()) {
            observaciones.add(
                    "Existen conceptos que requieren " +
                            "validacion adicional antes de proyectar " +
                            "las deducciones futuras.");
        }

        if (!especialesPendientes.isEmpty()) {
            observaciones.add(
                    "Existen movimientos especiales cuya " +
                            "naturaleza financiera debe confirmarse.");
        }

        if (periodicidadSinConfirmar) {
            observaciones.add(
                    "La presencia de un descuento en una o " +
                            "dos quincenas no demuestra por si sola " +
                            "que sea una obligacion mensual recurrente.");
        }

        observaciones.add(
                "El salario basico corresponde a la " +
                        "ficha actual del empleado en NovaWEB.");

        observaciones.add(
                "Las deducciones observadas se presentan " +
                        "para analisis. No constituyen una " +
                        "proyeccion mensual certificada.");

        if (escenarioHistoricoDisponible) {
            observaciones.add(
                    "Hay dos quincenas historicas consecutivas con evidencia " +
                            "de salario. Sus descuentos sirven solamente para un " +
                            "escenario ilustrativo; falta la nomina esperada. " +
                            "Fechas: " + fechasBaseEscenarioHistorico + ".");
        }

        resultado.put("observaciones", observaciones);

        return resultado;
    }

    // =====================================================
    // CONSULTA DE ULTIMA NOMINA
    // =====================================================

    private Map<String, Object> buscarUltimaNomina(
            String empresa,
            String base,
            String identificacion,
            LocalDate hoy) {

        List<Map<String, Object>> nominas =
                buscarPeriodos(
                        empresa, base,
                        identificacion, hoy, 1);

        return nominas.isEmpty()
                ? null
                : nominas.get(0);
    }

    // =====================================================
    // CONSULTA MULTIEMPRESA
    // =====================================================

    private List<Map<String, Object>> consultarInterno(
            String empresa,
            List<String> identificaciones,
            boolean consolidar) {

        if (identificaciones == null ||
                identificaciones.isEmpty()) {

            throw new IllegalArgumentException(
                    "Debe proporcionar identificaciones.");
        }

        Map<String, String> bases =
                seleccionarBases(empresa);

        LocalDate hoy = LocalDate.now(ZONA);

        List<Map<String, Object>> resultados =
                new ArrayList<>();

        for (String identificacion : identificaciones) {

            if (identificacion == null ||
                    identificacion.trim().isEmpty()) {

                throw new IllegalArgumentException(
                        "La identificacion es obligatoria.");
            }

            String id = identificacion.trim();

            boolean encontrado = false;
            boolean huboErrores = false;

            for (Map.Entry<String, String> entrada :
                    bases.entrySet()) {

                String codigoEmpresa =
                        entrada.getKey();

                String base =
                        entrada.getValue();

                try {

                    if (!existeEmpleado(base, id)) {
                        continue;
                    }

                    Map<String, Object> nomina;

                    if (consolidar) {

                        nomina = consolidarEnBase(
                                codigoEmpresa,
                                base,
                                id,
                                hoy);

                    } else {

                        nomina = buscarUltimaNomina(
                                codigoEmpresa,
                                base,
                                id,
                                hoy);
                    }

                    if (nomina != null) {
                        resultados.add(nomina);
                        encontrado = true;
                    }

                } catch (DataAccessException e) {

                    huboErrores = true;

                    System.err.println(
                            "[NOMINA] Error consultando " +
                                    base + " / empleado " + id);

                    e.printStackTrace();

                    Map<String, Object> error =
                            new LinkedHashMap<>();

                    error.put(
                            "estado",
                            "ERROR_CONSULTA");

                    error.put("codEmp", id);
                    error.put("empresa", codigoEmpresa);
                    error.put("baseDatos", base);

                    error.put(
                            "mensaje",
                            "No fue posible consultar esta base.");

                    resultados.add(error);
                }
            }

            if (!encontrado) {

                Map<String, Object> sinDatos =
                        new LinkedHashMap<>();

                sinDatos.put("codEmp", id);

                sinDatos.put(
                        "estado",
                        huboErrores
                                ? "CONSULTA_INCOMPLETA"
                                : "SIN_INFORMACION");

                sinDatos.put(
                        "mensaje",
                        huboErrores
                                ? "No fue posible verificar todas " +
                                "las bases seleccionadas."
                                : "No se encontro nomina disponible " +
                                "en los periodos consultados.");

                resultados.add(sinDatos);
            }
        }

        return resultados;
    }

    public List<Map<String, Object>> consultarEmpleados(
            String empresa,
            List<String> identificaciones) {

        return consultarInterno(
                empresa,
                identificaciones,
                false);
    }

    public List<Map<String, Object>>
    consultarNominaConsolidada(
            String empresa,
            List<String> identificaciones) {

        return consultarInterno(
                empresa,
                identificaciones,
                true);
    }
}