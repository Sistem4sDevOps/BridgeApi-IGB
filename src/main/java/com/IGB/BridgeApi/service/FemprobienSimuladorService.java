
package com.IGB.BridgeApi.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class FemprobienSimuladorService {

    private final JdbcTemplate jdbc;

    public FemprobienSimuladorService(
            @Qualifier("sqlServerJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // =====================================================
    // CONFIGURACION DE TASAS EXISTENTE
    // =====================================================

    public Map<String, Object> configuracion() {

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT tasa_mensual AS tasaMensual, " +
                        "modificado_por AS modificadoPor, " +
                        "fecha_modificacion AS fechaModificacion " +
                        "FROM FEMPROBIEN.dbo.tblFemprobienConfiguracionCredito " +
                        "WHERE id = 1"
        );

        if (rows.isEmpty()) {
            throw new IllegalStateException(
                    "Debe ejecutar el script SQL de configuración " +
                            "del simulador FEMPROBIEN."
            );
        }

        return rows.get(0);
    }

    public BigDecimal tasaVigente() {
        return new BigDecimal(
                configuracion().get("tasaMensual").toString()
        );
    }

    public Map<String, Object> cambiarTasa(
            BigDecimal tasa,
            String administrador) {

        validarTasa(tasa);

        if (administrador == null ||
                administrador.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "El administrador es obligatorio."
            );
        }

        return new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getDataSource())
        ).execute(status -> {

            List<BigDecimal> anteriores = jdbc.query(
                    "SELECT tasa_mensual " +
                            "FROM FEMPROBIEN.dbo.tblFemprobienConfiguracionCredito " +
                            "WITH (UPDLOCK, HOLDLOCK) WHERE id = 1",
                    (rs, n) -> rs.getBigDecimal(1)
            );

            if (anteriores.isEmpty()) {
                throw new IllegalStateException(
                        "Debe ejecutar el script SQL de configuración."
                );
            }

            BigDecimal nueva = tasa.setScale(
                    6, RoundingMode.HALF_UP
            );

            if (anteriores.get(0).compareTo(nueva) != 0) {

                jdbc.update(
                        "UPDATE FEMPROBIEN.dbo.tblFemprobienConfiguracionCredito " +
                                "SET tasa_mensual = ?, modificado_por = ?, " +
                                "fecha_modificacion = GETDATE() WHERE id = 1",
                        nueva,
                        administrador
                );

                jdbc.update(
                        "INSERT INTO FEMPROBIEN.dbo.tblFemprobienTasaCreditoAuditoria " +
                                "(tasa_anterior, tasa_nueva, realizado_por, fecha_registro) " +
                                "VALUES (?, ?, ?, GETDATE())",
                        anteriores.get(0),
                        nueva,
                        administrador
                );
            }

            return configuracion();
        });
    }

    private void validarTasa(BigDecimal tasa) {
        if (tasa == null ||
                tasa.signum() < 0 ||
                tasa.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException(
                    "La tasa mensual debe estar entre 0 y 100 %."
            );
        }
    }

    // =====================================================
    // SIMULACION ORIGINAL PARA SOLICITUDES
    // NO MODIFICAR SUS CONDICIONES
    // =====================================================

    public Map<String, Object> simular(
            BigDecimal monto,
            int meses,
            String frecuencia) {

        return calcular(
                monto,
                meses,
                frecuencia,
                tasaVigente()
        );
    }

    public void fijarCondicionesSolicitud(
            Map<String, Object> datos) {

        Object ambas = datos.get("descuento_ambas_quincenas");

        boolean quincenal =
                Boolean.TRUE.equals(ambas) ||
                        "1".equals(String.valueOf(ambas)) ||
                        "true".equalsIgnoreCase(String.valueOf(ambas));

        Map<String, Object> calculo = simular(
                new BigDecimal(
                        datos.get("monto_solicitado").toString()
                ),
                new BigDecimal(
                        datos.get("plazo_meses").toString()
                ).intValueExact(),
                quincenal ? "QUINCENAL" : "MENSUAL"
        );

        Object confirmada = datos.remove("tasa_confirmada");

        if (confirmada == null ||
                new BigDecimal(confirmada.toString()).compareTo(
                        (BigDecimal) calculo.get("tasaMensual")
                ) != 0) {

            throw new IllegalArgumentException(
                    "La tasa cambió o falta confirmar la cuota. " +
                            "Actualiza la cuota y revisa las condiciones."
            );
        }

        if (datos.get("valor_cuota") == null ||
                new BigDecimal(
                        datos.get("valor_cuota").toString()
                ).compareTo(
                        (BigDecimal) calculo.get("cuota")
                ) != 0 ||
                datos.get("cantidad_cuotas") == null ||
                new BigDecimal(
                        datos.get("cantidad_cuotas").toString()
                ).intValueExact() !=
                        (Integer) calculo.get("cantidadCuotas")) {

            throw new IllegalArgumentException(
                    "Las cuotas no coinciden con la simulación. " +
                            "Actualiza la cuota y revisa las condiciones."
            );
        }

        datos.put(
                "tasa_interes_mensual",
                calculo.get("tasaMensual")
        );

        datos.put(
                "frecuencia_pago",
                calculo.get("frecuencia")
        );

        datos.put(
                "cantidad_cuotas",
                calculo.get("cantidadCuotas")
        );

        datos.put(
                "valor_cuota",
                calculo.get("cuota")
        );
    }

    public Map<String, Object> calcular(
            BigDecimal monto,
            int meses,
            String frecuencia,
            BigDecimal tasa) {

        validarTasa(tasa);

        if (monto == null ||
                monto.signum() <= 0 ||
                monto.scale() > 2 ||
                monto.precision() - monto.scale() > 16 ||
                meses <= 0 ||
                meses > Integer.MAX_VALUE / 2) {

            throw new IllegalArgumentException(
                    "Ingrese un monto positivo con máximo dos " +
                            "decimales y un plazo entero positivo."
            );
        }

        if (!"MENSUAL".equals(frecuencia) &&
                !"QUINCENAL".equals(frecuencia)) {
            throw new IllegalArgumentException(
                    "Seleccione pago mensual o quincenal."
            );
        }

        int cantidad = meses *
                ("QUINCENAL".equals(frecuencia) ? 2 : 1);

        double mensual = tasa.doubleValue() / 100.0;

        double i = "QUINCENAL".equals(frecuencia)
                ? Math.expm1(Math.log1p(mensual) / 2)
                : mensual;

        double c = i == 0
                ? monto.doubleValue() / cantidad
                : monto.doubleValue() * i /
                (-Math.expm1(-cantidad * Math.log1p(i)));

        BigDecimal cuota = BigDecimal.valueOf(c)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal saldo = monto.setScale(
                2, RoundingMode.HALF_UP
        );

        BigDecimal total = BigDecimal.ZERO;

        List<Map<String, Object>> pagos = new ArrayList<>();

        int mostrar = Math.min(cantidad, 240);

        for (int n = 1; n <= mostrar; n++) {

            BigDecimal interes = saldo.multiply(
                    BigDecimal.valueOf(i)
            ).setScale(2, RoundingMode.HALF_UP);

            BigDecimal pago = n == cantidad
                    ? saldo.add(interes)
                    : cuota.min(saldo.add(interes));

            BigDecimal capital = pago.subtract(interes);

            saldo = saldo.subtract(capital)
                    .max(BigDecimal.ZERO);

            Map<String, Object> fila = new LinkedHashMap<>();

            fila.put("numero", n);
            fila.put("cuota", pago);
            fila.put("capital", capital);
            fila.put("interes", interes);
            fila.put("saldo", saldo);

            pagos.add(fila);

            total = total.add(pago);
        }

        if (cantidad > mostrar) {
            total = BigDecimal.valueOf(c)
                    .multiply(BigDecimal.valueOf(cantidad))
                    .setScale(2, RoundingMode.HALF_UP)
                    .max(monto);
        }

        Map<String, Object> r = new LinkedHashMap<>();

        r.put("monto", monto);
        r.put("plazoMeses", meses);
        r.put("frecuencia", frecuencia);
        r.put("cantidadCuotas", cantidad);
        r.put("tasaMensual", tasa);
        r.put(
                "tasaPeriodoPorcentaje",
                BigDecimal.valueOf(i * 100)
        );
        r.put("cuota", cuota);
        r.put("interesesTotales", total.subtract(monto));
        r.put("total", total);
        r.put(
                "requiereDeudor",
                monto.compareTo(new BigDecimal("10000000")) >= 0
        );
        r.put("pagos", pagos);
        r.put("tablaParcial", cantidad > mostrar);

        return r;
    }

    // =====================================================
    // NUEVO: ESCENARIO DE EXCEL CON AMORTIZACION
    // =====================================================

    /**
     * Reproduce la fórmula de cuota fija de Excel:
     *
     * TasaPeriodo = TNA / PagosPorAnio
     * CantidadCuotas = Anios * PagosPorAnio
     * Cuota = -PMT(TasaPeriodo, CantidadCuotas, Monto)
     *
     * La TNA llega en porcentaje: 16.20 significa 16.20%.
     *
     * NO realiza INSERT, UPDATE ni modifica solicitudes.
     */
    public Map<String, Object> simularEscenarioExcel(
            BigDecimal monto,
            BigDecimal tnaPorcentaje,
            BigDecimal anios,
            String frecuencia) {

        // ================================================
        // VALIDACIONES
        // ================================================

        if (monto == null ||
                monto.signum() <= 0 ||
                monto.scale() > 2 ||
                monto.precision() - monto.scale() > 16) {

            throw new IllegalArgumentException(
                    "El monto debe ser positivo y tener " +
                            "máximo dos decimales."
            );
        }

        if (tnaPorcentaje == null ||
                tnaPorcentaje.signum() < 0 ||
                tnaPorcentaje.compareTo(
                        new BigDecimal("100")
                ) > 0) {

            throw new IllegalArgumentException(
                    "La TNA debe estar entre 0 y 100 por ciento."
            );
        }

        if (anios == null ||
                anios.signum() <= 0 ||
                anios.compareTo(
                        new BigDecimal("40")
                ) > 0) {

            throw new IllegalArgumentException(
                    "Los años deben ser mayores que cero " +
                            "y máximo 40."
            );
        }

        String f = frecuencia == null
                ? ""
                : frecuencia.trim().toUpperCase(Locale.ROOT);

        int pagosPorAnio;

        switch (f) {

            case "ANUAL":
                pagosPorAnio = 1;
                break;

            case "SEMESTRAL":
                pagosPorAnio = 2;
                break;

            case "CUATRIMESTRAL":
                pagosPorAnio = 3;
                break;

            case "TRIMESTRAL":
                pagosPorAnio = 4;
                break;

            case "BIMESTRAL":
                pagosPorAnio = 6;
                break;

            case "MENSUAL":
                pagosPorAnio = 12;
                break;

            case "QUINCENAL":
                pagosPorAnio = 24;
                break;

            default:
                throw new IllegalArgumentException(
                        "Frecuencia de pago no reconocida."
                );
        }

        int cantidadCuotas;

        try {
            cantidadCuotas = anios.multiply(
                    BigDecimal.valueOf(pagosPorAnio)
            ).intValueExact();

        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Los años y la frecuencia deben generar " +
                            "un número entero de cuotas."
            );
        }

        return calcularEscenarioTna(
                monto, tnaPorcentaje, anios, f, pagosPorAnio, cantidadCuotas);
    }

    /**
     * Nueva entrada por meses enteros para comparación de plazos.
     * Reutiliza EXACTAMENTE el motor de TNA/30-360 de simularEscenarioExcel.
     * No aproxima los meses al convertirlos en años para contar cuotas.
     */
    public Map<String, Object> simularEscenarioMeses(
            BigDecimal monto, BigDecimal tnaPorcentaje,
            int meses, String frecuencia) {

        if (meses < 1 || meses > 120) {
            throw new IllegalArgumentException(
                    "El plazo para esta simulación debe estar entre 1 y 120 meses.");
        }
        if (monto == null || monto.signum() <= 0 ||
                monto.scale() > 2 || monto.precision() - monto.scale() > 16) {
            throw new IllegalArgumentException(
                    "El monto debe ser positivo y tener máximo dos decimales.");
        }
        if (tnaPorcentaje == null || tnaPorcentaje.signum() < 0 ||
                tnaPorcentaje.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("La TNA debe estar entre 0 y 100 por ciento.");
        }
        String f = frecuencia == null ? "" : frecuencia.trim().toUpperCase(Locale.ROOT);
        if (!"MENSUAL".equals(f) && !"QUINCENAL".equals(f)) {
            throw new IllegalArgumentException("Seleccione pago mensual o quincenal.");
        }
        int pagosPorAnio = "QUINCENAL".equals(f) ? 24 : 12;
        int cantidadCuotas = meses * ("QUINCENAL".equals(f) ? 2 : 1);
        // Sólo metadato descriptivo; el número de cuotas es un entero exacto.
        BigDecimal anios = BigDecimal.valueOf(meses)
                .divide(new BigDecimal("12"), 10, RoundingMode.HALF_UP);
        return calcularEscenarioTna(
                monto, tnaPorcentaje, anios, f, pagosPorAnio, cantidadCuotas);
    }

    private Map<String, Object> calcularEscenarioTna(
            BigDecimal monto, BigDecimal tnaPorcentaje, BigDecimal anios,
            String f, int pagosPorAnio, int cantidadCuotas) {
        if (cantidadCuotas < 1 || cantidadCuotas > 240) {
            throw new IllegalArgumentException(
                    "El escenario debe contener entre 1 y 240 cuotas."
            );
        }

        // ================================================
        // TASA PERIODICA 30/360
        // ================================================

        BigDecimal tasaPeriodoDecimal =
                tnaPorcentaje.divide(
                        BigDecimal.valueOf(100L * pagosPorAnio),
                        16,
                        RoundingMode.HALF_UP
                );

        double i = tasaPeriodoDecimal.doubleValue();

        // ================================================
        // CUOTA FIJA PMT
        // ================================================

        double valorActual = monto.doubleValue();

        double cuotaExacta;

        if (i == 0.0) {
            cuotaExacta = valorActual / cantidadCuotas;
        } else {
            cuotaExacta = valorActual * i /
                    (-Math.expm1(
                            -cantidadCuotas * Math.log1p(i)
                    ));
        }

        if (!Double.isFinite(cuotaExacta) ||
                cuotaExacta <= 0) {

            throw new IllegalArgumentException(
                    "No fue posible calcular la cuota."
            );
        }

        BigDecimal cuota = BigDecimal.valueOf(cuotaExacta)
                .setScale(2, RoundingMode.HALF_UP);

        if (cuota.signum() <= 0) {
            throw new IllegalArgumentException(
                    "La cuota redondeada es inferior a un centavo."
            );
        }

        // ================================================
        // TABLA DE AMORTIZACION
        // ================================================

        List<Map<String, Object>> tablaAmortizacion =
                new ArrayList<>();

        BigDecimal cero = BigDecimal.ZERO.setScale(2);

        BigDecimal saldo = monto.setScale(
                2, RoundingMode.HALF_UP
        );

        BigDecimal totalPagado = cero;
        BigDecimal totalIntereses = cero;
        BigDecimal capitalTotal = cero;

        // Fila inicial (número cero).
        Map<String, Object> inicial = new LinkedHashMap<>();

        inicial.put("numeroCuota", 0);
        inicial.put("cuotaAPagar", null);
        inicial.put("pagoAnticipado", null);
        inicial.put("totalPagado", null);
        inicial.put("interes", null);
        inicial.put("capitalAmortizado", null);
        inicial.put("capitalVivo", saldo);

        tablaAmortizacion.add(inicial);

        // Cuotas individuales.
        for (int numero = 1;
             numero <= cantidadCuotas;
             numero++) {

            // Interés causado sobre el capital pendiente.
            BigDecimal interes = saldo.multiply(
                    tasaPeriodoDecimal
            ).setScale(2, RoundingMode.HALF_UP);

            BigDecimal totalExigible =
                    saldo.add(interes);

            // La última cuota liquida el saldo residual.
            BigDecimal pago = numero == cantidadCuotas
                    ? totalExigible
                    : cuota.min(totalExigible);

            BigDecimal capitalAmortizado =
                    pago.subtract(interes);

            if (capitalAmortizado.signum() < 0) {
                throw new IllegalArgumentException(
                        "La cuota no cubre los intereses del período."
                );
            }

            saldo = saldo.subtract(capitalAmortizado);

            if (saldo.signum() < 0) {
                saldo = cero;
            }

            // Los abonos extraordinarios aún no se habilitan.
            BigDecimal pagoAnticipado = cero;

            Map<String, Object> fila =
                    new LinkedHashMap<>();

            fila.put("numeroCuota", numero);
            fila.put("cuotaAPagar", pago);
            fila.put("pagoAnticipado", pagoAnticipado);
            fila.put("totalPagado", pago);
            fila.put("interes", interes);
            fila.put(
                    "capitalAmortizado",
                    capitalAmortizado
            );
            fila.put("capitalVivo", saldo);

            tablaAmortizacion.add(fila);

            totalPagado = totalPagado.add(pago);
            totalIntereses = totalIntereses.add(interes);
            capitalTotal = capitalTotal.add(capitalAmortizado);

            if (saldo.signum() == 0) {
                break;
            }
        }

        // ================================================
        // RESPUESTA DEL ESCENARIO
        // ================================================

        Map<String, Object> resultado =
                new LinkedHashMap<>();

        resultado.put(
                "tipo",
                "SIMULACION_EXCEL_SIN_GUARDAR"
        );

        resultado.put("monto", monto);
        resultado.put("tnaPorcentaje", tnaPorcentaje);
        resultado.put("anios", anios);
        resultado.put("frecuencia", f);
        resultado.put("pagosPorAnio", pagosPorAnio);
        resultado.put("cantidadCuotas", cantidadCuotas);

        resultado.put(
                "cantidadRealCuotas",
                tablaAmortizacion.size() - 1
        );

        resultado.put(
                "tasaPeriodoPorcentaje",
                tasaPeriodoDecimal.multiply(
                        new BigDecimal("100")
                ).setScale(8, RoundingMode.HALF_UP)
        );

        resultado.put("cuota", cuota);
        resultado.put("total", totalPagado);
        resultado.put("interesesTotales", totalIntereses);
        resultado.put(
                "capitalTotalAmortizado",
                capitalTotal
        );

        resultado.put(
                "tablaAmortizacion",
                tablaAmortizacion
        );

        resultado.put(
                "incluyeGastosPrimeraCuota",
                false
        );

        resultado.put("modificaSolicitud", false);
        resultado.put("decisionAutomatica", false);

        resultado.put(
                "observacion",
                "Escenario ilustrativo de cuota fija " +
                        "sin abonos anticipados ni gastos de primera cuota. " +
                        "La última cuota se ajusta por redondeo. " +
                        "No constituye aprobación del crédito."
        );

        return resultado;
    }

    // =====================================================
    // VALIDACION EXISTENTE DE ANTIGUEDAD
    // =====================================================

    public void validarAntiguedad(
            Map<String, Object> asociado) {

        Object fecha = asociado.get("fec_ing");

        if (fecha == null) {
            fecha = asociado.get("fecha_afiliacion");
        }

        if (fecha == null ||
                fecha.toString().length() < 10) {

            throw new IllegalArgumentException(
                    "No hay fecha de afiliación registrada " +
                            "para verificar los 6 meses en el fondo. " +
                            "Contacte a FEMPROBIEN."
            );
        }

        LocalDate afiliacion;

        try {
            afiliacion = LocalDate.parse(
                    fecha.toString().substring(0, 10)
            );
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "La fecha de afiliación registrada no es válida."
            );
        }

        if (afiliacion.plusMonths(6)
                .isAfter(LocalDate.now())) {

            throw new IllegalArgumentException(
                    "Debe cumplir mínimo 6 meses de afiliación " +
                            "al fondo para solicitar un crédito."
            );
        }
    }
}
