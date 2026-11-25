package com.IGB.BridgeApi.service;

import com.IGB.BridgeApi.util.ReportPathUtil;
import net.sf.jasperreports.engine.*;
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource;
import net.sf.jasperreports.engine.util.JRLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import javax.imageio.ImageIO;
import java.io.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class FemprobienEstadoCuentaCreditoService {
    private final FemprobienCreditoHistoricoService historico;
    @Value("${femprobien.creditos.estado-cuenta-reporte:}")
    private String reportePath = "";

    public FemprobienEstadoCuentaCreditoService(FemprobienCreditoHistoricoService historico) {
        this.historico = historico;
    }

    @SuppressWarnings("unchecked")
    public byte[] generar(Integer id) throws Exception {
        Map<String, Object> credito = historico.consultarCreditoPorId(id);
        List<Map<String, Object>> saldos = new ArrayList<>((List<Map<String, Object>>) credito.get("saldos"));
        saldos.sort(Comparator.comparing(s -> fecha(s.get("periodo"))));
        List<Map<String, ?>> filas = new ArrayList<>();
        for (Map<String, Object> saldo : saldos) {
            Map<String, Object> fila = new HashMap<>();
            fila.put("periodo", fecha(saldo.get("periodo")));
            fila.put("saldo", decimal(saldo.get("saldo")));
            fila.put("valor_cuota", decimal(credito.get("valor_cuota")));
            int numero = cuota(credito, saldo.get("periodo"));
            int plazo = entero(credito.get("plazo"));
            fila.put("cuota_programada", numero <= 0 ? "—" : plazo > 0 && numero > plazo ? "Fuera del plazo original" : "Cuota " + numero);
            filas.add(fila);
        }
        Map<String, Object> params = parametros(credito, saldos);
        params.put(JRParameter.REPORT_LOCALE, new Locale("es", "CO"));
        try (InputStream logo = new ClassPathResource("templates/reports/femprobien-logo.png").getInputStream()) {
            params.put("LOGO", ImageIO.read(logo));
        }
        return JasperExportManager.exportReportToPdf(JasperFillManager.fillReport(reporte(), params, new JRMapCollectionDataSource(filas)));
    }

    Map<String, Object> parametros(Map<String, Object> c, List<Map<String, Object>> saldos) {
        Map<String, Object> p = new HashMap<>();
        p.put("ASOCIADO", c.get("nom_aso"));
        p.put("DOCUMENTO", c.get("cod_asp"));
        p.put("CREDITO", String.valueOf(c.get("id")));
        p.put("MONTO", decimal(c.get("val_desm")));
        p.put("FECHA_DESEMBOLSO", fecha(c.get("fecha_desem")));
        p.put("PLAZO", c.get("plazo") == null ? null : entero(c.get("plazo")));
        p.put("CUOTA", decimal(c.get("valor_cuota")));
        Map<String, Object> ultimo = saldos.isEmpty() ? Collections.emptyMap() : saldos.get(saldos.size() - 1);
        int numero = cuota(c, ultimo.get("periodo")), plazo = entero(c.get("plazo"));
        p.put("CUOTAS_CORTE", numero > 0 && plazo > 0 && numero <= plazo ? numero + "/" + plazo : "Sin información");
        p.put("SALDO_CORTE", decimal(ultimo.get("saldo")));
        p.put("FECHA_CORTE", fecha(ultimo.get("periodo")));
        p.put("FECHA_GENERACION", new Date());
        p.put(JRParameter.REPORT_TIME_ZONE, TimeZone.getTimeZone("America/Bogota"));
        return p;
    }

    private JasperReport reporte() throws Exception {
        File base = new File(ReportPathUtil.getAccountStatementJasper()).getParentFile();
        String path = reportePath.trim().isEmpty() ? new File(base, "estadoCuentaCredito").getPath() : reportePath.trim();
        String sinExtension = path.replaceFirst("\\.(jasper|jrxml)$", "");
        File jrxml = new File(sinExtension + ".jrxml"), jasper = new File(sinExtension + ".jasper");
        if (jrxml.isFile() && (!jasper.isFile() || jrxml.lastModified() > jasper.lastModified())) {
            return JasperCompileManager.compileReport(jrxml.getPath());
        }
        if (jasper.isFile()) { return (JasperReport) JRLoader.loadObject(jasper); }
        if (!reportePath.trim().isEmpty()) { throw new FileNotFoundException("No se encontró la plantilla estadoCuentaCredito configurada."); }
        try (InputStream in = new ClassPathResource("templates/reports/estadoCuentaCredito.jrxml").getInputStream()) {
            return JasperCompileManager.compileReport(in);
        }
    }

    private int cuota(Map<String, Object> c, Object periodo) {
        Date inicio = fecha(c.get("inicio_dcto")), corte = fecha(periodo);
        if (inicio == null || corte == null) { return 0; }
        LocalDate desde = new java.sql.Date(inicio.getTime()).toLocalDate().withDayOfMonth(1);
        LocalDate hasta = new java.sql.Date(corte.getTime()).toLocalDate().withDayOfMonth(1);
        return desde.getYear() <= 1900 ? 0 : (int) ChronoUnit.MONTHS.between(desde, hasta) + 1;
    }

    private Date fecha(Object valor) { return valor == null ? null : (Date) valor; }
    private BigDecimal decimal(Object valor) { return valor == null ? null : new BigDecimal(valor.toString()); }
    private int entero(Object valor) { return valor == null ? 0 : Integer.parseInt(valor.toString()); }
}
