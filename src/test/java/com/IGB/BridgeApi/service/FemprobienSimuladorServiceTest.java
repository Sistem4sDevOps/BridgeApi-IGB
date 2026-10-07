package com.IGB.BridgeApi.service;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class FemprobienSimuladorServiceTest {
    private FemprobienSimuladorService service = new FemprobienSimuladorService(mock(JdbcTemplate.class));
    @Test void mensualAmortizaSaldoYExigeDeudorDesdeDiezMillones() {
        Map<String,Object> r = service.calcular(new BigDecimal("10000000"), 24, "MENSUAL", new BigDecimal("1.35"));
        assertEquals(24, r.get("cantidadCuotas"));
        assertEquals(true, r.get("requiereDeudor"));
        List<Map<String,Object>> pagos = (List<Map<String,Object>>)r.get("pagos");
        assertEquals(new BigDecimal("0.00"), pagos.get(23).get("saldo"));
        assertTrue(((BigDecimal)pagos.get(1).get("interes")).compareTo((BigDecimal)pagos.get(0).get("interes")) < 0);
        assertEquals(new BigDecimal("10000000.00"), pagos.stream().map(p -> (BigDecimal)p.get("capital")).reduce(BigDecimal.ZERO, BigDecimal::add));
    }
    @Test void quincenalEsEquivalenteMensualYDuplicaCuotas() {
        Map<String,Object> r = service.calcular(new BigDecimal("9999999"), 12, "QUINCENAL", new BigDecimal("1.35"));
        assertEquals(24, r.get("cantidadCuotas")); assertEquals(false, r.get("requiereDeudor"));
        double i = ((BigDecimal)r.get("tasaPeriodoPorcentaje")).doubleValue()/100;
        assertEquals(1.0135, (1+i)*(1+i), 0.00000001);
    }
    @Test void tasaCeroNoGeneraIntereses() {
        Map<String,Object> r = service.calcular(new BigDecimal("1200000"), 12, "MENSUAL", BigDecimal.ZERO);
        assertEquals(new BigDecimal("100000.00"), r.get("cuota"));
        assertEquals(new BigDecimal("0.00"), r.get("interesesTotales"));
    }
    @Test void tablaParcialNoGeneraInteresesNegativosPorRedondeo() {
        Map<String,Object> r=service.calcular(BigDecimal.ONE,1000,"MENSUAL",BigDecimal.ZERO);
        assertEquals(new BigDecimal("1.00"),r.get("total"));
        assertEquals(new BigDecimal("0.00"),r.get("interesesTotales"));
        assertEquals(true,r.get("tablaParcial"));
    }
    @Test void rechazaMontoPlazoFrecuenciaYTasaInvalidos() {
        assertThrows(IllegalArgumentException.class, () -> service.calcular(BigDecimal.ZERO, 12, "MENSUAL", BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> service.calcular(BigDecimal.ONE, 0, "MENSUAL", BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> service.calcular(BigDecimal.ONE, 12, "OTRA", BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> service.calcular(BigDecimal.ONE, 12, "MENSUAL", new BigDecimal("-1")));
    }
    @Test void verificaSeisMesesUsandoFechaDelFondoYNoIngresoLaboral() {
        Map<String,Object> asociado = new HashMap<>();
        asociado.put("fec_ing", "2010-01-01");
        assertThrows(IllegalArgumentException.class, () -> service.validarAntiguedad(asociado));
        asociado.put("fec_afi", java.time.LocalDate.now().minusMonths(6).toString());
        assertDoesNotThrow(() -> service.validarAntiguedad(asociado));
        asociado.put("fec_afi", java.time.LocalDate.now().minusMonths(6).plusDays(1).toString());
        assertThrows(IllegalArgumentException.class, () -> service.validarAntiguedad(asociado));
    }
    @Test void solicitudConservaTasaDelServidorEIgnoraValorManipulado() {
        FemprobienSimuladorService s = spy(service);
        doReturn(new BigDecimal("1.35")).when(s).tasaVigente();
        Map<String,Object> datos = new HashMap<>();
        datos.put("monto_solicitado", "10000000"); datos.put("plazo_meses", 12);
        datos.put("descuento_ambas_quincenas", true); datos.put("tasa_interes_mensual", 99);
        datos.put("tasa_confirmada", "1.35");
        Map<String,Object> calculo = s.calcular(new BigDecimal("10000000"),12,"QUINCENAL",new BigDecimal("1.35"));
        datos.put("cantidad_cuotas", 24); datos.put("valor_cuota", calculo.get("cuota"));
        s.fijarCondicionesSolicitud(datos);
        assertEquals(new BigDecimal("1.35"), datos.get("tasa_interes_mensual"));
        assertEquals("QUINCENAL", datos.get("frecuencia_pago")); assertEquals(24, datos.get("cantidad_cuotas"));
        assertTrue(((BigDecimal)datos.get("valor_cuota")).compareTo(BigDecimal.ONE)>0);
    }
    @Test void tasaCambiadaExigeNuevaConfirmacion() {
        FemprobienSimuladorService s=spy(service);
        doReturn(new BigDecimal("1.50")).when(s).tasaVigente();
        Map<String,Object> d=new HashMap<>();d.put("monto_solicitado",10000000);d.put("plazo_meses",12);d.put("tasa_confirmada",1.35);
        assertThrows(IllegalArgumentException.class,()->s.fijarCondicionesSolicitud(d));
        assertFalse(d.containsKey("tasa_interes_mensual"));
    }
    @Test void cambioDeTasaYAuditoriaSeConfirmanJuntos() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        javax.sql.DataSource ds=mock(javax.sql.DataSource.class);
        java.sql.Connection conexion=mock(java.sql.Connection.class);
        when(ds.getConnection()).thenReturn(conexion);when(jdbc.getDataSource()).thenReturn(ds);
        when(jdbc.query(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowMapper.class))).thenReturn(Arrays.asList(new BigDecimal("1.35")));
        Map<String,Object> config=new HashMap<>();config.put("tasaMensual",new BigDecimal("1.50"));
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString())).thenReturn(Arrays.asList(config));
        new FemprobienSimuladorService(jdbc).cambiarTasa(new BigDecimal("1.50"),"admin");
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("tblFemprobienTasaCreditoAuditoria"),org.mockito.ArgumentMatchers.eq(new BigDecimal("1.35")),org.mockito.ArgumentMatchers.eq(new BigDecimal("1.500000")),org.mockito.ArgumentMatchers.eq("admin"));
        verify(conexion).commit();
    }
    @Test void falloDeAuditoriaRevierteCambioDeTasa() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        javax.sql.DataSource ds=mock(javax.sql.DataSource.class);
        java.sql.Connection conexion=mock(java.sql.Connection.class);
        when(ds.getConnection()).thenReturn(conexion);when(jdbc.getDataSource()).thenReturn(ds);
        when(jdbc.query(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowMapper.class))).thenReturn(Arrays.asList(new BigDecimal("1.35")));
        doThrow(new IllegalStateException("Auditoria no disponible")).when(jdbc).update(org.mockito.ArgumentMatchers.contains("tblFemprobienTasaCreditoAuditoria"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        assertThrows(IllegalStateException.class,()->new FemprobienSimuladorService(jdbc).cambiarTasa(new BigDecimal("1.50"),"admin"));
        verify(conexion).rollback(); verify(conexion,never()).commit();
    }
}
