package com.IGB.BridgeApi.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Exporta el estudio FINANCIERO de FEMPROBIEN, sin alterar el estado del credito.
 * 1 numero de solicitud = 1 hoja con el formato original.
 * El navegador solo proporciona IDs; los datos se recalculan en BridgeApi.
 * Requiere Apache POI (poi-ooxml) compatible con Java 8.
 */
@Service
public class FemprobienEstudioExcelService {

    private static final String ARCHIVO_BASE =
            "/femprobien/Plantilla_Estudio_Credito_FEMPROBIEN_Base.xlsx";
    private static final int MAX_SOLICITUDES = 100;
    private static final int MAX_CONCEPTOS_NOVAWEB = 100;
    // Logo autenticado: extraido del archivo Excel de referencia suministrado por el usuario.
    private static final String LOGO_OFICIAL =
            "/femprobien/logo_femprobien.png";
    private final EvaluacionCreditoService evaluacion;

    public FemprobienEstudioExcelService(EvaluacionCreditoService evaluacion) {
        this.evaluacion = evaluacion;
    }

    public byte[] generar(List<Integer> numeros) {
        List<Integer> ids = validarNumeros(numeros);

        // Consulta autoritativa: JAMAS aceptar salario, descuentos o TOPES del cliente.
        List<Map<String, Object>> estudios = new ArrayList<Map<String, Object>>();
        int maxCuotas = 60;
        for (Integer id : ids) {
            Map<String, Object> estudio;
            try {
                estudio = evaluacion.evaluarSolicitud(id);
            } catch (Exception ex) {
                throw new IllegalStateException(
                        "No fue posible consultar la solicitud #" + id +
                                ". El Excel no se genero parcialmente.", ex);
            }
            if (estudio == null || !(estudio.get("solicitud") instanceof Map)) {
                throw new IllegalStateException("Estudio incompleto para solicitud #" + id);
            }
            estudios.add(estudio);
            maxCuotas = Math.max(maxCuotas, cuotasEsperadas(estudio));
        }

        try (InputStream entrada = getClass().getResourceAsStream(ARCHIVO_BASE)) {
            if (entrada == null) {
                throw new IllegalStateException("Plantilla FEMPROBIEN no incluida en resources.");
            }
            try (XSSFWorkbook libro = new XSSFWorkbook(entrada);
                 ByteArrayOutputStream salida = new ByteArrayOutputStream(1024 * 128)) {

                XSSFSheet base = libro.getSheet("PLANTILLA");
                if (base == null || libro.getSheet("Auxiliar") == null) {
                    throw new IllegalStateException("La plantilla requiere PLANTILLA y Auxiliar.");
                }

                // El ejemplo contiene formulas hasta la fila 408. Para 100 creditos
                // reducimos filas vacias por encima del maximo plazo del lote.
                int ultimaFila = Math.min(408, Math.max(79, 21 + maxCuotas + 5));
                for (int indice = base.getLastRowNum(); indice > ultimaFila; indice--) {
                    Row fila = base.getRow(indice);
                    if (fila != null) base.removeRow(fila);
                }

                // El logo queda embebido en el XLSX; Excel no necesita internet.
                // Logo obligatorio: no ocultar errores de despliegue con texto sustituto.
                byte[] logo = leerLogoObligatorio();
                // Se ancla en I2: forma parte del encabezado gris B2:I2.
                // Las coordenadas son fijas (94 x 53 px) y no dependen del tamaño
                // original de la imagen ni de las columnas de la derecha.
                int imagenId = libro.addPicture(logo, Workbook.PICTURE_TYPE_PNG);

                Set<String> nombres = new LinkedHashSet<String>();
                for (int i = 0; i < ids.size(); i++) {
                    Integer id = ids.get(i);
                    Map<String, Object> estudio = estudios.get(i);
                    XSSFSheet hoja = libro.cloneSheet(libro.getSheetIndex(base));
                    String nombre = nombreHoja(estudio, id, nombres);
                    libro.setSheetName(libro.getSheetIndex(hoja), nombre);
                    llenarHoja(hoja, estudio, id);
                    insertarLogo(hoja, imagenId);
                }

                libro.removeSheetAt(libro.getSheetIndex(base));
                int indiceAuxiliar = libro.getSheetIndex("Auxiliar");
                libro.setSheetOrder("Auxiliar", libro.getNumberOfSheets() - 1);
                libro.setSheetHidden(libro.getSheetIndex("Auxiliar"), true);
                libro.setActiveSheet(0);
                libro.setForceFormulaRecalculation(true);
                libro.write(salida);
                return salida.toByteArray();
            }
        } catch (IOException e) {
            throw new IllegalStateException("No fue posible crear el archivo Excel.", e);
        }
    }

    public static List<Integer> validarNumeros(List<Integer> numeros) {
        if (numeros == null || numeros.isEmpty()) {
            throw new IllegalArgumentException("Seleccione al menos una solicitud.");
        }
        LinkedHashSet<Integer> ids = new LinkedHashSet<Integer>();
        for (Integer id : numeros) {
            if (id == null || id.intValue() <= 0) {
                throw new IllegalArgumentException("El numero de solicitud debe ser positivo.");
            }
            ids.add(id);
        }
        if (ids.size() > MAX_SOLICITUDES) {
            throw new IllegalArgumentException("Puede exportar hasta 100 solicitudes.");
        }
        return new ArrayList<Integer>(ids);
    }

    private static int cuotasEsperadas(Map<String, Object> estudio) {
        Map<String, Object> solicitud = mapa(estudio.get("solicitud"));
        Map<String, Object> simulacion = mapa(estudio.get("simulacion"));
        Integer cuotas = entero(solicitud.get("cantidadCuotasRegistrada"));
        if (cuotas != null && cuotas.intValue() > 0) return Math.min(cuotas, 387);
        Integer meses = entero(solicitud.get("plazoMeses"));
        String frecuencia = texto(simulacion.get("frecuencia"));
        if (frecuencia == null) frecuencia = texto(solicitud.get("frecuenciaPagoRegistrada"));
        if (meses == null) return 60;
        return Math.min(meses * ("QUINCENAL".equalsIgnoreCase(frecuencia) ? 2 : 1), 387);
    }

    private static String nombreHoja(Map<String, Object> estudio, Integer id,
                                     Set<String> nombres) {
        String nombre = texto(mapa(estudio.get("asociado")).get("nombre"));
        if (nombre == null) nombre = "ASOCIADO";
        nombre = nombre.replaceAll("[\\[\\]\\*:/\\\\?]", " ").trim();
        String base = WorkbookUtil.createSafeSheetName("SOL-" + id + " " + nombre);
        base = base.substring(0, Math.min(31, base.length())).trim();
        String unico = base;
        int sufijo = 1;
        while (!nombres.add(unico.toLowerCase(Locale.ROOT))) {
            String ext = "-" + sufijo++;
            unico = base.substring(0, Math.min(base.length(), 31 - ext.length())) + ext;
        }
        return unico;
    }

    private static void llenarHoja(XSSFSheet hoja, Map<String, Object> estudio,
                                   Integer id) {
        Map<String, Object> solicitud = mapa(estudio.get("solicitud"));
        Map<String, Object> asociado = mapa(estudio.get("asociado"));
        Map<String, Object> nomina = mapa(estudio.get("nomina"));
        Map<String, Object> simulacion = mapa(estudio.get("simulacion"));
        Map<String, Object> resumen = mapa(estudio.get("resumenFinancieroAgente"));
        Map<String, Object> indicadores = mapa(estudio.get("indicadoresFinancieros"));
        Map<String, Object> escenario = mapa(indicadores.get("escenarioObservado"));

        escribirTexto(hoja, "D4", texto(asociado.get("nombre")));
        // D5 es AFILIACION AL FONDO, no fecha de inicio del contrato laboral.
        // No se inventan valores: si la base no los entrega quedan vacios.
        String fechaFondo = formatoFecha(primeroDisponible(
                asociado.get("fechaIngresoFondo"),
                asociado.get("fechaAfiliacionFondo"),
                asociado.get("fechaVinculacionFondo"),
                solicitud.get("fechaIngresoFondo")));
        escribirTexto(hoja, "D5", fechaFondo == null ? "No registrada" : fechaFondo);
        String cargoAsociado = texto(primeroDisponible(
                asociado.get("cargo"), asociado.get("nombreCargo"),
                nomina.get("cargo"), nomina.get("nombreCargo"),
                solicitud.get("cargo")));
        escribirTexto(hoja, "D7", cargoAsociado == null ? "No reportado" : cargoAsociado);
        escribirTexto(hoja, "D6", texto(solicitud.get("empresaRegistrada")));
        escribirTexto(hoja, "D8", texto(solicitud.get("destinoCredito")));
        escribirNumero(hoja, "D10", numero(solicitud.get("montoSolicitado")));
        Integer meses = entero(solicitud.get("plazoMeses"));
        if (meses != null) {
            escribirNumero(hoja, "D12", new BigDecimal(meses).divide(
                    new BigDecimal("12"), 8, RoundingMode.HALF_UP));
        }
        String frecuencia = texto(simulacion.get("frecuencia"));
        if (frecuencia == null) frecuencia = texto(solicitud.get("frecuenciaPagoRegistrada"));
        if (frecuencia != null) {
            if ("MENSUAL".equalsIgnoreCase(frecuencia)) escribirTexto(hoja, "D13", "Mensual");
            else if ("QUINCENAL".equalsIgnoreCase(frecuencia)) escribirTexto(hoja, "D13", "Quincenal");
        }
        // tasaInteresMensualRegistrada se expresa en porcentaje: 0.99 = 0.99%.
        // La plantilla usa tasa nominal anual (TNA): 0.99/100*12 = 0.1188.
        BigDecimal tasaMensualPorcentaje = numero(solicitud.get("tasaInteresMensualRegistrada"));
        if (tasaMensualPorcentaje != null &&
                tasaMensualPorcentaje.signum() >= 0 &&
                tasaMensualPorcentaje.compareTo(new BigDecimal("100")) <= 0) {
            escribirNumero(hoja, "D11", tasaMensualPorcentaje.multiply(
                    new BigDecimal("12")).divide(new BigDecimal("100"), 10,
                    RoundingMode.HALF_UP));
        }

        // Ingresos usados en el escenario del motor; no confundirlos con validados.
        BigDecimal salario = numero(nomina.get("salarioBasicoMensual"));
        BigDecimal variables = numero(nomina.get("ingresosVariablesParaEscenario"));
        if (variables == null) variables = numero(nomina.get("comisionesHorasExtrasMensuales"));
        escribirNumero(hoja, "O23", salario);
        escribirNumero(hoja, "O24", variables);
        escribirNumero(hoja, "O26", BigDecimal.ZERO); // No incluir bonificaciones no validadas.
        ponerFormula(hoja, "O27", "O25");

        BigDecimal cuotaMensual = numero(simulacion.get("cuotaMensualEquivalente"));
        int filaTotal = colocarDeducciones(hoja, resumen, id, cuotaMensual);

        // El cupo solo se llena si fue verificado; el historico no lo reemplaza.
        escribirNumero(hoja, "O16", numero(indicadores.get("cupoDisponible")));
        // Los gastos financieros O12 no estan provistos por el DTO: queda en blanco.
        // O13 mantiene el ejemplo de 4x1000 original; NO es un valor certificado.

        escribirTexto(hoja, "N17", "Solicitud #");
        escribirNumero(hoja, "O17", new BigDecimal(id));
        escribirTexto(hoja, "N18", "Validacion del estudio");
        String validacion = texto(estudio.get("estadoValidacionEstudio"));
        escribirTexto(hoja, "O18", "VERIFICADO".equals(validacion)
                ? "Verificado" : "Pendiente de verificacion");
        String resultado = texto(estudio.get("resultadoEstudio"));
        if (resultado != null) {
            escribirTexto(hoja, "N" + (filaTotal + 2), "Resultado del escenario");
            escribirTexto(hoja, "O" + (filaTotal + 2), resultado);
        }
        // Los porcentajes Excel se recalculan con el total de esta misma hoja.
        // Cualquier discrepancia frente al estudio API debe revisarse antes de usar.
        if (escenario.get("baseIngresosParaTope") == null &&
                (salario == null || variables == null)) {
            escribirTexto(hoja, "N" + (filaTotal + 3),
                    "Datos salariales incompletos: escenario no certificado");
        }
    }

    /**
     * Deducciones NovaWEB dentro de LA MISMA tabla N:O.
     * Cada concepto es una fila, sin agrupar ni repetir en N39:O70.
     * La ultima fila lleva el total de deducciones + nueva cuota.
     */
    private static int colocarDeducciones(XSSFSheet hoja,
                                          Map<String, Object> resumen,
                                          Integer id,
                                          BigDecimal cuotaMensual) {
        List<Map<String, Object>> detalle = listaMapas(resumen.get("deducciones"));
        BigDecimal totalApi = numero(resumen.get("totalDeduccionesObservadas"));
        BigDecimal totalDetalle = BigDecimal.ZERO;
        List<String> etiquetas = new ArrayList<String>();
        List<BigDecimal> importes = new ArrayList<BigDecimal>();

        for (Map<String, Object> concepto : detalle) {
            BigDecimal valor = numero(concepto.get("valorObservado"));
            if (valor == null || valor.signum() <= 0) continue;
            String codigo = texto(concepto.get("codigo"));
            String nombre = texto(concepto.get("concepto"));
            String etiqueta = ((codigo == null ? "" : codigo + " ") +
                    (nombre == null ? "Descuento de nomina" : nombre)).trim();
            etiquetas.add(etiqueta);
            importes.add(valor);
            totalDetalle = totalDetalle.add(valor);
        }
        if (etiquetas.size() > MAX_CONCEPTOS_NOVAWEB) {
            throw new IllegalStateException("Demasiados conceptos de nomina en solicitud #" + id);
        }

        if (etiquetas.isEmpty() && totalApi != null && totalApi.signum() > 0) {
            // Se muestra el total, SIN atribuirlo arbitrariamente a un concepto.
            etiquetas.add("Descuentos observados (sin desglose)");
            importes.add(totalApi);
        } else if (totalApi != null &&
                totalDetalle.subtract(totalApi).abs().compareTo(new BigDecimal("1.00")) > 0) {
            throw new IllegalStateException("Las deducciones no concilian en solicitud #" + id);
        }

        // Tomar estilos de las filas originales ANTES de limpiar datos.
        CellStyle estiloConcepto = celda(hoja, "N29").getCellStyle();
        CellStyle estiloValor = celda(hoja, "O29").getCellStyle();
        CellStyle estiloNuevaEtiqueta = celda(hoja, "N34").getCellStyle();
        CellStyle estiloNuevaCuota = celda(hoja, "O34").getCellStyle();
        CellStyle estiloTotal = celda(hoja, "O35").getCellStyle();
        CellStyle estiloTope = celda(hoja, "P35").getCellStyle();
        CellStyle estiloEtiquetaTope = celda(hoja, "Q35").getCellStyle();

        // Nueva cuota y resultado ya no estan atados a las filas 34, 35, 36.
        // Solo se limpian columnas N:Q; la amortizacion C:I queda intacta.
        int ultimaLimpieza = Math.max(100, 29 + etiquetas.size() + 8);
        for (int numeroFila = 29; numeroFila <= ultimaLimpieza; numeroFila++) {
            Row fila = hoja.getRow(numeroFila - 1);
            if (fila == null) continue;
            for (int columna = 13; columna <= 16; columna++) {
                if (columna == 16 && numeroFila == 29) continue; // "Saldo"
                Cell c = fila.getCell(columna);
                if (c != null) c.setCellType(CellType.BLANK);
            }
        }

        int fila = 29;
        CellStyle estiloAjustado = null;
        if (etiquetas.isEmpty()) {
            Cell nombre = celda(hoja, "N" + fila);
            nombre.setCellStyle(estiloConcepto);
            nombre.setCellValue("Sin deducciones informadas");
            celda(hoja, "O" + fila).setCellStyle(estiloValor);
            fila++;
        } else {
            for (int i = 0; i < etiquetas.size(); i++) {
                String referenciaTexto = "N" + fila;
                String referenciaValor = "O" + fila;
                Cell nombre = celda(hoja, referenciaTexto);
                nombre.setCellStyle(estiloConcepto);
                nombre.setCellValue(etiquetas.get(i));
                Cell importe = celda(hoja, referenciaValor);
                importe.setCellStyle(estiloValor);
                importe.setCellValue(importes.get(i).doubleValue());
                if (etiquetas.get(i).length() > 34) {
                    if (estiloAjustado == null) {
                        estiloAjustado = hoja.getWorkbook().createCellStyle();
                        estiloAjustado.cloneStyleFrom(estiloConcepto);
                        estiloAjustado.setWrapText(true);
                    }
                    nombre.setCellStyle(estiloAjustado);
                    hoja.getRow(fila - 1).setHeightInPoints(29);
                }
                fila++;
            }
        }

        int filaNuevaCuota = Math.max(34, fila);
        Cell etiquetaCuota = celda(hoja, "N" + filaNuevaCuota);
        etiquetaCuota.setCellStyle(estiloNuevaEtiqueta);
        etiquetaCuota.setCellValue("Nueva cuota (API)");
        Cell importeCuota = celda(hoja, "O" + filaNuevaCuota);
        importeCuota.setCellStyle(estiloNuevaCuota);
        if (cuotaMensual != null) importeCuota.setCellValue(cuotaMensual.doubleValue());

        int filaTotal = filaNuevaCuota + 1;
        Cell total = celda(hoja, "O" + filaTotal);
        total.setCellStyle(estiloTotal);
        total.setCellFormula("SUM(O29:O" + filaNuevaCuota + ")");
        Cell porcentaje = celda(hoja, "P" + filaTotal);
        porcentaje.setCellStyle(estiloTope);
        porcentaje.setCellFormula("IFERROR(O" + filaTotal + "*100/O27,\"\")");
        Cell tope = celda(hoja, "Q" + filaTotal);
        tope.setCellStyle(estiloEtiquetaTope);
        tope.setCellValue("TOPE");
        return filaTotal;
    }

    /** Logo genuino incluido en el proyecto BridgeApi bajo resources/femprobien. */
    private static byte[] leerLogoObligatorio() throws IOException {
        try (InputStream entrada = FemprobienEstudioExcelService.class
                .getResourceAsStream(LOGO_OFICIAL)) {
            if (entrada == null) {
                throw new IllegalStateException(
                        "Falta el archivo src/main/resources/femprobien/logo_femprobien.png. " +
                                "Incluya el PNG del paquete y reconstruya BridgeApi.");
            }
            ByteArrayOutputStream salida = new ByteArrayOutputStream(64 * 1024);
            byte[] buffer = new byte[8192];
            int cantidad;
            while ((cantidad = entrada.read(buffer)) != -1) {
                salida.write(buffer, 0, cantidad);
            }
            return salida.toByteArray();
        }
    }

    /**
     * El marcador #¡CAMPO! proviene de I2, que en la plantilla original
     * conserva el atributo OOXML vm=1 (XLRICHVALUE: antigua imagen en celda).
     * No es un texto ni un error normal, por eso limpiar N:U no lo quitaba.
     * Se elimina el vinculo a rich-data y se reemplaza por una imagen PNG
     * anclada dentro de I2, en el extremo derecho del encabezado gris.
     */
    private static void insertarLogo(XSSFSheet hoja, int imagenId) {
        XSSFCell antiguaImagen = hoja.getRow(1).getCell(8); // I2
        if (antiguaImagen == null) {
            antiguaImagen = hoja.getRow(1).createCell(8);
        }
        // POI soporta las estructuras CTCell a traves de XSSFCell.getCTCell().
        // El atributo vm=1 activa la imagen 'rica' que Excel representa #¡CAMPO!.
        if (antiguaImagen.getCTCell().isSetVm()) {
            antiguaImagen.getCTCell().unsetVm();
        }
        antiguaImagen.setCellType(CellType.BLANK);

        XSSFDrawing dibujo = hoja.createDrawingPatriarch();
        CreationHelper helper = hoja.getWorkbook().getCreationHelper();
        ClientAnchor ancla = helper.createClientAnchor();
        // I2: col=8, row=1 (indices desde 0). Dimensiones en pixeles,
        // no invadir las columnas N:P, donde van datos y deducciones.
        ancla.setCol1(8);
        ancla.setRow1(1);
        ancla.setDx1(Units.pixelToEMU(5));
        ancla.setDy1(Units.pixelToEMU(4));
        ancla.setCol2(8);
        ancla.setRow2(1);
        ancla.setDx2(Units.pixelToEMU(99));
        ancla.setDy2(Units.pixelToEMU(57));
        ancla.setAnchorType(ClientAnchor.AnchorType.MOVE_DONT_RESIZE);
        dibujo.createPicture(ancla, imagenId);
    }

    private static Object primeroDisponible(Object... valores) {
        for (Object valor : valores) {
            if (valor != null && !String.valueOf(valor).trim().isEmpty()) {
                return valor;
            }
        }
        return null;
    }

    private static String formatoFecha(Object fecha) {
        if (fecha == null) return null;
        if (fecha instanceof java.util.Date) {
            return new java.text.SimpleDateFormat("dd/MM/yyyy")
                    .format((java.util.Date) fecha);
        }
        String texto = String.valueOf(fecha).trim();
        if (texto.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
            return texto.substring(8, 10) + "/" + texto.substring(5, 7) +
                    "/" + texto.substring(0, 4);
        }
        return texto;
    }

    private static void escribirTexto(XSSFSheet hoja, String referencia, String valor) {
        if (valor == null) return;
        Cell celda = celda(hoja, referencia);
        celda.setCellValue(valor.length() > 240 ? valor.substring(0, 240) : valor);
    }
    private static void escribirNumero(XSSFSheet hoja, String referencia, BigDecimal valor) {
        if (valor == null) return;
        celda(hoja, referencia).setCellValue(valor.doubleValue());
    }
    private static void ponerFormula(XSSFSheet hoja, String referencia, String formula) {
        celda(hoja, referencia).setCellFormula(formula);
    }
    private static Cell celda(XSSFSheet hoja, String referencia) {
        org.apache.poi.ss.util.CellReference ref =
                new org.apache.poi.ss.util.CellReference(referencia);
        Row fila = hoja.getRow(ref.getRow());
        if (fila == null) fila = hoja.createRow(ref.getRow());
        Cell celda = fila.getCell(ref.getCol());
        if (celda == null) celda = fila.createCell(ref.getCol(), CellType.BLANK);
        return celda;
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapa(Object valor) {
        return valor instanceof Map ? (Map<String, Object>) valor :
                Collections.<String, Object>emptyMap();
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listaMapas(Object valor) {
        if (!(valor instanceof List)) return Collections.emptyList();
        List<Map<String, Object>> salida = new ArrayList<Map<String, Object>>();
        for (Object x : (List<?>) valor) if (x instanceof Map) salida.add((Map<String, Object>) x);
        return salida;
    }
    private static String texto(Object valor) {
        if (valor == null) return null;
        String s = valor.toString().trim();
        return s.isEmpty() ? null : s;
    }
    private static BigDecimal numero(Object valor) {
        if (valor == null || valor.toString().trim().isEmpty()) return null;
        try { return new BigDecimal(valor.toString()); }
        catch (NumberFormatException ex) { return null; }
    }
    private static Integer entero(Object valor) {
        BigDecimal v = numero(valor);
        if (v == null) return null;
        try { return v.intValueExact(); }
        catch (ArithmeticException e) { return null; }
    }
}
