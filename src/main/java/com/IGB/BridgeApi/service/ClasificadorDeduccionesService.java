package com.IGB.BridgeApi.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ClasificadorDeduccionesService {

    private static final String DEDUCCION = "DEDUCCION";
    private static final String INGRESO_SALARIAL = "INGRESO_SALARIAL";
    private static final String INGRESO_NO_SALARIAL = "INGRESO_NO_SALARIAL";
    private static final String INGRESO_EXTRAORDINARIO = "INGRESO_EXTRAORDINARIO";
    private static final String ESPECIAL_POR_VERIFICAR =
            "ESPECIAL_POR_VERIFICAR";
    private static final String DESCONOCIDO = "DESCONOCIDO";

    private static class Regla {

        final String grupo;
        final boolean requiereValidacion;
        final String tipoMovimiento;
        final Boolean esDeduccionNomina;

        Regla(
                String grupo,
                boolean requiereValidacion,
                String tipoMovimiento,
                Boolean esDeduccionNomina) {

            this.grupo = grupo;
            this.requiereValidacion = requiereValidacion;
            this.tipoMovimiento = tipoMovimiento;
            this.esDeduccionNomina = esDeduccionNomina;
        }
    }

    private static final Map<String, Regla> REGLAS;

    static {

        Map<String, Regla> reglas = new HashMap<>();

        // Descuentos legales
        registrar(reglas, "DESCUENTO_LEGAL", false,
                "002205", "002210", "002215", "003300");

        // Ajustes anticipados
        registrar(reglas, "LEGAL_ANTICIPADO", true,
                "002231", "002233");

        // Embargos
        registrar(reglas, "EMBARGO", true,
                "003410");

        // Ahorro FEMPROBIEN
        registrar(reglas, "AHORRO_FEMPROBIEN", false,
                "200134");

        // Créditos FEMPROBIEN
        registrar(reglas, "CREDITO_FEMPROBIEN", true,
                "200135", "200144");

        // Convenios FEMPROBIEN.
        // 200138: "Convenio Femprobien" observado en MTZ_NOVAWEB.
        // Se reconoce como descuento, pero su vigencia y tratamiento
        // para proyecciones siguen requiriendo comprobación.
        registrar(reglas, "CONVENIO_FEMPROBIEN", true,
                "200136", "200138", "200139", "200140");

        // Créditos externos
        registrar(reglas, "CREDITO_EXTERNO", true,
                "200100", "200101", "200102",
                "200103", "200106", "200123");

        // Créditos por compras
        registrar(reglas, "PRESTAMO_COMPRAS", true,
                "200104", "200109", "200132");

        // Otros préstamos
        registrar(reglas, "OTROS_PRESTAMOS", true,
                "002500", "200105", "200129");

        // Anticipos de nómina
        registrar(reglas, "ANTICIPO_NOMINA", true,
                "200147");

        // Seguros
        registrar(reglas, "SEGUROS", true,
                "200001", "200002", "200124");

        // Ahorros externos
        registrar(reglas, "AHORRO_EXTERNO", true,
                "200113", "200125", "200126", "200142");

        // Otros descuentos
        registrar(reglas, "OTROS_DESCUENTOS", true,
                "200110", "200118", "200119", "200120");

        // Ajustes especiales
        registrar(reglas, "AJUSTE_ESPECIAL", true,
                "002999", "200141");

        /*
         * El código 001050 aparece en NovaWEB
         * identificado como salario.
         *
         * No debe contabilizarse como deducción.
         *
         * Se conserva requiereValidacion = true
         * para confirmar su interpretación antes
         * de utilizarlo en cálculos definitivos.
         */
        reglas.put(
                "001050",
                new Regla(
                        "MOVIMIENTO_ESPECIAL",
                        true,
                        INGRESO_SALARIAL,
                        Boolean.FALSE
                )
        );

        /*
         * Los demás movimientos especiales no tienen
         * naturaleza suficientemente verificada.
         *
         * No se consideran automáticamente
         * descuentos ni ingresos.
         */
        // Ingresos identificados: no son deducciones de nomina y
        // NO se incorporan al ingreso validado para capacidad de pago.
        reglas.put("001300", new Regla("AUXILIO_TRANSPORTE", false,
                INGRESO_NO_SALARIAL, Boolean.FALSE));
        reglas.put("001500", new Regla("PRIMA_SERVICIOS", false,
                INGRESO_EXTRAORDINARIO, Boolean.FALSE));
        reglas.put("001560", new Regla("CESANTIAS", false,
                INGRESO_EXTRAORDINARIO, Boolean.FALSE));
        reglas.put("001565", new Regla("INTERESES_CESANTIAS", false,
                INGRESO_EXTRAORDINARIO, Boolean.FALSE));
        reglas.put("001146", new Regla("VACACIONES_LIQUIDACION", false,
                INGRESO_EXTRAORDINARIO, Boolean.FALSE));
        reglas.put("001600", new Regla("INDEMNIZACION", false,
                INGRESO_EXTRAORDINARIO, Boolean.FALSE));

        // Estos codigos siguen sin clasificacion segura.
        registrarEspecial(reglas, "999902", "999905");

        REGLAS = Collections.unmodifiableMap(reglas);
    }

    private static void registrar(
            Map<String, Regla> reglas,
            String grupo,
            boolean requiereValidacion,
            String... codigos) {

        for (String codigo : codigos) {

            reglas.put(
                    codigo,
                    new Regla(
                            grupo,
                            requiereValidacion,
                            DEDUCCION,
                            Boolean.TRUE
                    )
            );
        }
    }

    private static void registrarEspecial(
            Map<String, Regla> reglas,
            String... codigos) {

        for (String codigo : codigos) {

            reglas.put(
                    codigo,
                    new Regla(
                            "MOVIMIENTO_ESPECIAL",
                            true,
                            ESPECIAL_POR_VERIFICAR,
                            null
                    )
            );
        }
    }

    /*
     * Clasifica el código sin asumir recurrencia.
     *
     * Conserva los campos originales:
     * - grupo
     * - codigoReconocido
     * - requiereValidacion
     * - periodicidad
     *
     * Agrega metadatos para la evaluación financiera.
     */
    public Map<String, Object> clasificar(String codigo) {

        String limpio =
                codigo == null ? "" : codigo.trim();

        Regla regla = REGLAS.get(limpio);

        boolean reconocido = regla != null;

        if (regla == null) {

            regla = new Regla(
                    "SIN_CLASIFICAR",
                    true,
                    DESCONOCIDO,
                    null
            );
        }

        Map<String, Object> resultado =
                new LinkedHashMap<>();

        resultado.put(
                "grupo",
                regla.grupo
        );

        resultado.put(
                "codigoReconocido",
                reconocido
        );

        resultado.put(
                "requiereValidacion",
                regla.requiereValidacion
        );

        /*
         * Se conserva el valor original.
         * Un código aislado no permite inferir
         * si el movimiento es mensual, quincenal
         * o extraordinario.
         */
        resultado.put(
                "periodicidad",
                "NO_DETERMINADA"
        );

        /*
         * Nuevos campos de clasificación.
         */
        resultado.put(
                "tipoMovimiento",
                regla.tipoMovimiento
        );

        resultado.put(
                "esDeduccionNomina",
                regla.esDeduccionNomina
        );

        /*
         * Solo los códigos reconocidos como
         * deducciones pueden sumarse al total
         * observado de descuentos de nómina.
         *
         * Esto NO autoriza todavía una
         * proyección mensual futura.
         */
        boolean incluirDescuentoObservado =
                Boolean.TRUE.equals(
                        regla.esDeduccionNomina
                );

        resultado.put(
                "incluirEnDescuentosObservados",
                incluirDescuentoObservado
        );

        boolean requiereValidacionPeriodicidad =
                !Boolean.FALSE.equals(
                        regla.esDeduccionNomina
                );

        resultado.put(
                "requiereValidacionPeriodicidad",
                requiereValidacionPeriodicidad
        );

        /*
         * La clasificación por código nunca basta
         * para certificar que un descuento se
         * repetirá en períodos futuros.
         */
        resultado.put(
                "aptoParaProyeccionAutomatica",
                false
        );

        // La clasificación por código no constituye certificación
        // de recurrencia o de saldo futuro.
        resultado.put("estadoValidacionParaProyeccion",
                regla.tipoMovimiento.startsWith("INGRESO_")
                        ? "NO_APLICA_DEDUCCION"
                        : "PENDIENTE_EVIDENCIA_EXTERNA");

        resultado.put(
                "tratamientoProyeccion",
                obtenerTratamiento(regla, reconocido)
        );

        resultado.put(
                "observacionClasificacion",
                obtenerObservacion(regla, reconocido)
        );

        return resultado;
    }

    private String obtenerTratamiento(
            Regla regla,
            boolean reconocido) {

        if (!reconocido) {
            return "VALIDAR_NATURALEZA";
        }

        if (regla.tipoMovimiento.startsWith("INGRESO_")) {
            return "NO_SUMAR_A_DEDUCCIONES";
        }

        if (ESPECIAL_POR_VERIFICAR.equals(
                regla.tipoMovimiento)) {

            return "VALIDAR_TIPO_MOVIMIENTO";
        }

        if (regla.requiereValidacion) {

            return "VALIDAR_NATURALEZA_Y_PERIODICIDAD";
        }

        return "VALIDAR_PERIODICIDAD";
    }

    private String obtenerObservacion(
            Regla regla,
            boolean reconocido) {

        if (!reconocido) {

            return "Código no reconocido. " +
                    "Requiere verificar su naturaleza " +
                    "antes de utilizarlo en el análisis.";
        }

        if (INGRESO_SALARIAL.equals(
                regla.tipoMovimiento)) {

            return "Movimiento identificado como salario " +
                    "en la nómina analizada. No debe sumarse " +
                    "como descuento. Confirmar su naturaleza " +
                    "con la fuente de nómina.";
        }

        if (INGRESO_NO_SALARIAL.equals(regla.tipoMovimiento) ||
                INGRESO_EXTRAORDINARIO.equals(regla.tipoMovimiento)) {
            return "Ingreso identificado en nomina. No se suma a deducciones " +
                    "ni se incluye automaticamente como ingreso mensual " +
                    "validado para capacidad de pago.";
        }

        if (ESPECIAL_POR_VERIFICAR.equals(
                regla.tipoMovimiento)) {

            return "Movimiento especial cuya naturaleza " +
                    "debe confirmarse antes de incluirlo " +
                    "en los cálculos financieros.";
        }

        if (regla.requiereValidacion) {

            return "Descuento reconocido que requiere " +
                    "validar su naturaleza, tratamiento " +
                    "y periodicidad para la proyección.";
        }

        return "Descuento reconocido. La periodicidad " +
                "no puede determinarse únicamente " +
                "mediante el código de nómina.";
    }

    /**
     * Metadatos de ingresos variables OBSERVADOS en NovaWEB.
     * No altera el catalogo de descuentos y nunca certifica
     * recurrencia, naturaleza salarial ni disponibilidad futura.
     */
    public Map<String, Object> clasificarIngresoVariable(String codigo) {
        String limpio = codigo == null ? "" : codigo.trim();
        String tipo;

        if ("001060".equals(limpio) || "100020".equals(limpio)) {
            tipo = "RECARGO_NOCTURNO";
        } else if ("001061".equals(limpio) ||
                "001062".equals(limpio) ||
                "001063".equals(limpio) ||
                "001064".equals(limpio)) {
            tipo = "HORAS_EXTRAS";
        } else if ("100001".equals(limpio)) {
            tipo = "COMISION";
        } else {
            tipo = "INGRESO_VARIABLE_OTRO";
        }

        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("tipoIngresoVariable", tipo);
        resultado.put("origenEvidenciaIngreso", "NOMINA_OBSERVADA");
        resultado.put("validacionIngreso", "PENDIENTE_VALIDACION");
        resultado.put("aptoParaProyeccionAutomatica", false);
        return resultado;
    }

    /*
     * Clasifica una lista conservando los campos
     * originales: código, concepto, valor, etc.
     */
    public List<Map<String, Object>> clasificarLista(
            List<Map<String, Object>> deducciones) {

        List<Map<String, Object>> resultado =
                new ArrayList<>();

        if (deducciones == null) {
            return resultado;
        }

        for (Map<String, Object> deduccion : deducciones) {

            Map<String, Object> item =
                    deduccion == null
                            ? new LinkedHashMap<String, Object>()
                            : new LinkedHashMap<>(deduccion);

            Object codigo = item.get("codigo");

            Map<String, Object> clasificacion =
                    clasificar(
                            codigo == null
                                    ? null
                                    : codigo.toString()
                    );

            item.putAll(clasificacion);

            resultado.add(item);
        }

        return resultado;
    }
}