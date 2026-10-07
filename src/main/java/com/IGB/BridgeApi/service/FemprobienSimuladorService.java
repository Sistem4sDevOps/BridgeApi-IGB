package com.IGB.BridgeApi.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@Service
public class FemprobienSimuladorService {
    private final JdbcTemplate jdbc;
    public FemprobienSimuladorService(@Qualifier("sqlServerJdbcTemplate") JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String,Object> configuracion() {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT tasa_mensual AS tasaMensual, modificado_por AS modificadoPor, fecha_modificacion AS fechaModificacion FROM FEMPROBIEN.dbo.tblFemprobienConfiguracionCredito WHERE id = 1");
        if (rows.isEmpty()) throw new IllegalStateException("Debe ejecutar el script SQL de configuración del simulador FEMPROBIEN.");
        return rows.get(0);
    }
    public BigDecimal tasaVigente() { return new BigDecimal(configuracion().get("tasaMensual").toString()); }
    public Map<String,Object> cambiarTasa(BigDecimal tasa, String administrador) {
        validarTasa(tasa);
        if (administrador == null || administrador.trim().isEmpty()) throw new IllegalArgumentException("El administrador es obligatorio.");
        return new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).execute(status -> {
            List<BigDecimal> anteriores = jdbc.query("SELECT tasa_mensual FROM FEMPROBIEN.dbo.tblFemprobienConfiguracionCredito WITH (UPDLOCK, HOLDLOCK) WHERE id = 1", (rs,n) -> rs.getBigDecimal(1));
            if (anteriores.isEmpty()) throw new IllegalStateException("Debe ejecutar el script SQL de configuración del simulador FEMPROBIEN.");
            BigDecimal nueva = tasa.setScale(6, RoundingMode.HALF_UP);
            if (anteriores.get(0).compareTo(nueva) != 0) {
                jdbc.update("UPDATE FEMPROBIEN.dbo.tblFemprobienConfiguracionCredito SET tasa_mensual = ?, modificado_por = ?, fecha_modificacion = GETDATE() WHERE id = 1", nueva, administrador);
                jdbc.update("INSERT INTO FEMPROBIEN.dbo.tblFemprobienTasaCreditoAuditoria (tasa_anterior,tasa_nueva,realizado_por,fecha_registro) VALUES (?,?,?,GETDATE())", anteriores.get(0), nueva, administrador);
            }
            return configuracion();
        });
    }
    private void validarTasa(BigDecimal tasa) {
        if (tasa == null || tasa.signum() < 0 || tasa.compareTo(new BigDecimal("100")) > 0) throw new IllegalArgumentException("La tasa mensual debe estar entre 0 y 100 %.");
    }
    public Map<String,Object> simular(BigDecimal monto, int meses, String frecuencia) { return calcular(monto, meses, frecuencia, tasaVigente()); }
    public void fijarCondicionesSolicitud(Map<String,Object> datos) {
        Object ambas = datos.get("descuento_ambas_quincenas");
        boolean quincenal = Boolean.TRUE.equals(ambas) || "1".equals(String.valueOf(ambas)) || "true".equalsIgnoreCase(String.valueOf(ambas));
        Map<String,Object> calculo = simular(new BigDecimal(datos.get("monto_solicitado").toString()),
                new BigDecimal(datos.get("plazo_meses").toString()).intValueExact(), quincenal ? "QUINCENAL" : "MENSUAL");
        Object confirmada = datos.remove("tasa_confirmada");
        if (confirmada == null || new BigDecimal(confirmada.toString()).compareTo((BigDecimal)calculo.get("tasaMensual")) != 0) {
            throw new IllegalArgumentException("La tasa cambió o falta confirmar la cuota. Actualiza la cuota y revisa las condiciones antes de enviar nuevamente.");
        }
        if (datos.get("valor_cuota") == null || new BigDecimal(datos.get("valor_cuota").toString()).compareTo((BigDecimal)calculo.get("cuota")) != 0 ||
                datos.get("cantidad_cuotas") == null || new BigDecimal(datos.get("cantidad_cuotas").toString()).intValueExact() != (Integer)calculo.get("cantidadCuotas")) {
            throw new IllegalArgumentException("Las cuotas no coinciden con la simulación. Actualiza la cuota y revisa las condiciones antes de enviar.");
        }
        datos.put("tasa_interes_mensual", calculo.get("tasaMensual"));
        datos.put("frecuencia_pago", calculo.get("frecuencia"));
        datos.put("cantidad_cuotas", calculo.get("cantidadCuotas"));
        datos.put("valor_cuota", calculo.get("cuota"));
    }
    public Map<String,Object> calcular(BigDecimal monto, int meses, String frecuencia, BigDecimal tasa) {
        validarTasa(tasa);
        if (monto == null || monto.signum() <= 0 || monto.scale() > 2 || monto.precision()-monto.scale() > 16 || meses <= 0 || meses > Integer.MAX_VALUE/2) throw new IllegalArgumentException("Ingrese un monto positivo con máximo dos decimales y un plazo entero positivo.");
        if (!"MENSUAL".equals(frecuencia) && !"QUINCENAL".equals(frecuencia)) throw new IllegalArgumentException("Seleccione pago mensual o quincenal.");
        int cantidad = meses * ("QUINCENAL".equals(frecuencia) ? 2 : 1);
        double mensual = tasa.doubleValue()/100.0;
        double i = "QUINCENAL".equals(frecuencia) ? Math.expm1(Math.log1p(mensual)/2) : mensual;
        double c = i == 0 ? monto.doubleValue()/cantidad : monto.doubleValue()*i/(-Math.expm1(-cantidad*Math.log1p(i)));
        BigDecimal cuota = BigDecimal.valueOf(c).setScale(2,RoundingMode.HALF_UP);
        BigDecimal saldo = monto.setScale(2,RoundingMode.HALF_UP), total = BigDecimal.ZERO;
        List<Map<String,Object>> pagos = new ArrayList<>();
        int mostrar = Math.min(cantidad,240);
        for(int n=1;n<=mostrar;n++) {
            BigDecimal interes = saldo.multiply(BigDecimal.valueOf(i)).setScale(2,RoundingMode.HALF_UP);
            BigDecimal pago = n == cantidad ? saldo.add(interes) : cuota.min(saldo.add(interes));
            BigDecimal capital = pago.subtract(interes); saldo = saldo.subtract(capital).max(BigDecimal.ZERO);
            Map<String,Object> fila = new LinkedHashMap<>();
            fila.put("numero",n); fila.put("cuota",pago); fila.put("capital",capital); fila.put("interes",interes); fila.put("saldo",saldo);
            pagos.add(fila); total = total.add(pago);
        }
        if(cantidad>mostrar) total=BigDecimal.valueOf(c).multiply(BigDecimal.valueOf(cantidad)).setScale(2,RoundingMode.HALF_UP).max(monto);
        Map<String,Object> r = new LinkedHashMap<>();
        r.put("monto",monto); r.put("plazoMeses",meses); r.put("frecuencia",frecuencia); r.put("cantidadCuotas",cantidad);
        r.put("tasaMensual",tasa); r.put("tasaPeriodoPorcentaje",BigDecimal.valueOf(i*100));
        r.put("cuota",cuota); r.put("interesesTotales",total.subtract(monto)); r.put("total",total);
        r.put("requiereDeudor",monto.compareTo(new BigDecimal("10000000"))>=0); r.put("pagos",pagos); r.put("tablaParcial",cantidad>mostrar);
        return r;
    }
    public void validarAntiguedad(Map<String,Object> asociado) {
        Object fecha = asociado.get("fec_afi");
        if (fecha == null) fecha = asociado.get("fecha_afiliacion");
        if (fecha == null || fecha.toString().length() < 10) throw new IllegalArgumentException("No hay fecha de afiliación registrada para verificar los 6 meses en el fondo. Contacte a FEMPROBIEN.");
        LocalDate afiliacion;
        try { afiliacion = LocalDate.parse(fecha.toString().substring(0,10)); }
        catch (RuntimeException e) { throw new IllegalArgumentException("La fecha de afiliación registrada no es válida."); }
        if (afiliacion.plusMonths(6).isAfter(LocalDate.now())) throw new IllegalArgumentException("Debe cumplir mínimo 6 meses de afiliación al fondo para solicitar un crédito.");
    }
}
