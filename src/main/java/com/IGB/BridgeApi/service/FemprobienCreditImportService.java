package com.IGB.BridgeApi.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

@Service
public class FemprobienCreditImportService {

    private static final String HOJA_CREDITOS = "Hoja1";
    private static final ZoneId ZONA_NEGOCIO = ZoneId.of("America/Bogota");
    private static final TimeZone TIME_ZONE_NEGOCIO = TimeZone.getTimeZone("America/Bogota");

    private static final String INSERTAR_CREDITO = "INSERTAR_CREDITO";
    private static final String ACTUALIZAR_CREDITO = "ACTUALIZAR_CREDITO";
    private static final String FUERA_CORTE = "FUERA_CORTE";

    private static final String OMITIDO_NO_ASOCIADO =
            "OMITIDO_NO_ASOCIADO";

    private static final String ERROR = "ERROR";

    private static final int PRIMERA_COLUMNA_SALDOS_DEFAULT = 11; // Columna L

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public FemprobienCreditImportService(
            @Qualifier("sqlServerJdbcTemplate") JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate = jdbcTemplate;

        DataSource dataSource =
                jdbcTemplate.getDataSource();

        if (dataSource == null) {
            throw new IllegalStateException(
                    "sqlServerJdbcTemplate no tiene DataSource configurado."
            );
        }

        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(
                        dataSource
                );

        this.transactionTemplate =
                new TransactionTemplate(
                        transactionManager
                );

        this.transactionTemplate.setIsolationLevel(
                TransactionDefinition.ISOLATION_SERIALIZABLE
        );
    }

    public Map<String, Object> validarArchivo(
            MultipartFile archivo) throws Exception {

        Analisis analisis = analizarArchivo(
                archivo,
                resolverPeriodoCorte()
        );

        return construirRespuestaValidacion(analisis);
    }

    public Map<String, Object> procesarArchivo(
            MultipartFile archivo,
            String usuario) throws Exception {

        final Analisis analisis =
                analizarArchivo(
                        archivo,
                        resolverPeriodoCorte()
                );

        if (analisis.errores > 0) {
            throw new IllegalArgumentException(
                    "El archivo contiene "
                            + analisis.errores
                            + " error(es). Debes corregirlos antes de procesar."
            );
        }

        final Map<String, String> columnasTabla =
                obtenerColumnasTabla();

        validarColumnasObligatoriasTabla(
                columnasTabla
        );

        final String usuarioProceso =
                texto(usuario);

        try {

            return transactionTemplate.execute(
                    status -> procesarCreditosDentroTransaccion(
                            analisis,
                            columnasTabla,
                            usuarioProceso
                    )
            );

        } catch (RuntimeException e) {

            if (
                    e.getCause() != null
                            && e.getCause().getMessage() != null
            ) {
                throw new IllegalStateException(
                        e.getCause().getMessage(),
                        e
                );
            }

            throw e;
        }
    }


    private Map<String, Object> procesarCreditosDentroTransaccion(
            Analisis analisis,
            Map<String, String> columnasTabla,
            String usuario) {

        int insertados = 0;
        int actualizados = 0;
        int omitidos = 0;

        for (FilaCredito fila : analisis.filas) {

            if (!fila.procesar) {

                if (
                        FUERA_CORTE.equals(
                                fila.operacion
                        )
                                || OMITIDO_NO_ASOCIADO.equals(
                                fila.operacion
                        )
                ) {
                    omitidos++;
                }

                continue;
            }

            try {

                Map<String, Object> datos =
                        construirDatosCredito(
                                fila,
                                columnasTabla
                        );

                if (INSERTAR_CREDITO.equals(fila.operacion)) {

                    insertarCredito(
                            datos,
                            columnasTabla
                    );

                    insertados++;

                } else if (ACTUALIZAR_CREDITO.equals(fila.operacion)) {

                    actualizarCredito(
                            fila.idCreditoExistente,
                            datos,
                            columnasTabla
                    );

                    actualizados++;
                }

            } catch (Exception e) {

                throw new IllegalStateException(
                        "Error procesando la fila "
                                + fila.filaExcel
                                + " - documento "
                                + fila.documento
                                + ": "
                                + e.getMessage(),
                        e
                );
            }
        }

        Map<String, Object> response =
                new LinkedHashMap<>();

        response.put(
                "status",
                200
        );

        response.put(
                "message",
                "Cartera de créditos actualizada correctamente con el corte "
                        + etiquetaPeriodo(analisis.periodo)
                        + "."
        );

        response.put(
                "periodoCorte",
                analisis.periodo.toString()
        );

        response.put(
                "fechaCorte",
                analisis.periodo
                        .atEndOfMonth()
                        .toString()
        );

        response.put(
                "etiquetaPeriodo",
                etiquetaPeriodo(
                        analisis.periodo
                )
        );

        response.put(
                "columnaExcel",
                CellReference.convertNumToColString(
                        analisis.columnaPeriodo
                )
        );

        response.put(
                "procesados",
                insertados + actualizados
        );

        response.put(
                "insertados",
                insertados
        );

        response.put(
                "actualizados",
                actualizados
        );

        response.put(
                "omitidos",
                omitidos
        );

        response.put(
                "omitidosFueraCorte",
                analisis.omitidosFueraCorte
        );

        response.put(
                "omitidosNoAsociado",
                analisis.omitidosNoAsociado
        );

        response.put(
                "errores",
                0
        );

        response.put(
                "usuario",
                usuario
        );

        response.put(
                "detalle",
                construirDetalle(
                        analisis.filas
                )
        );

        return response;
    }

    private Analisis analizarArchivo(
            MultipartFile archivo,
            YearMonth periodo) throws Exception {

        validarArchivoBasico(archivo);
        validarEstructuraTablaBase();

        try (Workbook workbook = WorkbookFactory.create(archivo.getInputStream())) {

            Sheet sheet = workbook.getSheet(HOJA_CREDITOS);

            if (sheet == null) {
                if (workbook.getNumberOfSheets() == 1) {
                    sheet = workbook.getSheetAt(0);
                } else {
                    throw new IllegalArgumentException(
                            "No se encontró la hoja '" + HOJA_CREDITOS + "'."
                    );
                }
            }

            DataFormatter formatter = new DataFormatter(new Locale("es", "CO"));
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            int filaEncabezados = encontrarFilaEncabezados(sheet, formatter, evaluator);
            Row header = sheet.getRow(filaEncabezados);

            Map<String, Integer> columnas = obtenerColumnasTexto(header, formatter, evaluator);

            int colEmpresa = obtenerColumna(columnas, false, "EMPRESA");
            int colObligacion = obtenerColumna(
                    columnas,
                    false,
                    "N OLIGACION",
                    "N OBLIGACION",
                    "NO OLIGACION",
                    "NO OBLIGACION"
            );
            int colTipoDoc = obtenerColumna(columnas, false, "TIPO DOC", "TIPO DOCUMENTO");
            int colDocumento = obtenerColumna(columnas, true, "N DOCUMENTO", "NO DOCUMENTO", "DOCUMENTO");
            int colNombre = obtenerColumna(columnas, true, "NOMBRE");
            int colValorDesembolsado = obtenerColumna(columnas, true, "VALOR DESEMBOLSADO");
            int colFechaDesembolso = obtenerColumna(columnas, true, "FECHA DESEMBOLSO");
            int colInicioDcto = obtenerColumna(columnas, true, "INICIO DCTO", "INICIO DESCUENTO");
            int colFechaFin = obtenerColumna(
                    columnas,
                    true,
                    "FECHA FINALIZACION",
                    "FECHA FINALIZACIÓN",
                    "FECHA FINAL"
            );
            int colPlazo = obtenerColumna(columnas, true, "PLAZO");
            int colValorCuota = obtenerColumna(
                    columnas,
                    true,
                    "VALOR CUOTA MENSUAL",
                    "VALOR CUOTA"
            );

            PeriodoExcel periodoExcel = encontrarColumnaPeriodo(
                    header,
                    periodo,
                    formatter,
                    evaluator
            );

            Map<String, AsociadoDb> asociados = cargarAsociados();
            CreditosDb creditosDb = cargarCreditosExistentes();

            Analisis analisis = new Analisis();
            analisis.periodo = periodo;
            analisis.columnaPeriodo = periodoExcel.columnaPeriodo;
            analisis.primeraColumnaSaldo = periodoExcel.primeraColumnaSaldo;
            analisis.filas = new ArrayList<>();

            Set<String> llavesCreditoExcel = new HashSet<>();

            for (int r = filaEncabezados + 1; r <= sheet.getLastRowNum(); r++) {

                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }

                if (esFilaVaciaOPlantilla(
                        row,
                        colObligacion,
                        colDocumento,
                        colNombre,
                        colValorDesembolsado,
                        colFechaDesembolso,
                        formatter,
                        evaluator
                )) {
                    continue;
                }

                analisis.filasLeidas++;

                FilaCredito fila = new FilaCredito();
                fila.filaExcel = r + 1;
                fila.empresa = leerTexto(row, colEmpresa, formatter, evaluator);
                fila.numeroObligacion = normalizarObligacion(
                        leerTexto(row, colObligacion, formatter, evaluator)
                );
                fila.tipoDoc = leerTexto(row, colTipoDoc, formatter, evaluator);
                fila.documento = normalizarDocumento(
                        leerTexto(row, colDocumento, formatter, evaluator)
                );
                fila.nombreExcel = leerTexto(row, colNombre, formatter, evaluator);

                try {
                    fila.valorDesembolsado = leerDecimalObligatorio(
                            row,
                            colValorDesembolsado,
                            evaluator,
                            "Valor Desembolsado"
                    );
                    fila.fechaDesembolso = leerFechaObligatoria(
                            row,
                            colFechaDesembolso,
                            formatter,
                            evaluator,
                            "Fecha desembolso"
                    );
                    fila.inicioDcto = leerFechaMesAnioObligatoria(
                            row,
                            colInicioDcto,
                            formatter,
                            evaluator,
                            "Inicio Dcto"
                    );
                    fila.fechaFin = leerFechaObligatoria(
                            row,
                            colFechaFin,
                            formatter,
                            evaluator,
                            "Fecha Finalización"
                    );
                    fila.plazo = leerEnteroObligatorio(
                            row,
                            colPlazo,
                            evaluator,
                            "Plazo"
                    );
                    fila.valorCuota = leerDecimalObligatorio(
                            row,
                            colValorCuota,
                            evaluator,
                            "Valor cuota mensual"
                    );

                } catch (IllegalArgumentException e) {
                    marcarError(fila, e.getMessage());
                    analisis.errores++;
                    analisis.filas.add(fila);
                    continue;
                }

                if (fila.documento.isEmpty()) {
                    marcarError(fila, "La fila no contiene N° Documento.");
                    analisis.errores++;
                    analisis.filas.add(fila);
                    continue;
                }

                if (fila.nombreExcel.isEmpty()) {
                    marcarError(fila, "La fila no contiene Nombre.");
                    analisis.errores++;
                    analisis.filas.add(fila);
                    continue;
                }

                String llaveCreditoExcel = llaveCredito(
                        fila.documento,
                        fila.valorDesembolsado,
                        fila.fechaDesembolso
                );

                if (!llavesCreditoExcel.add(llaveCreditoExcel)) {
                    marcarError(
                            fila,
                            "El archivo contiene más de un crédito con el mismo documento, "
                                    + "valor desembolsado y fecha de desembolso. "
                                    + "Con la estructura actual de tblAporteCredito no es posible distinguirlos."
                    );
                    analisis.errores++;
                    analisis.filas.add(fila);
                    continue;
                }

                AsociadoDb asociado = asociados.get(fila.documento);

                if (asociado == null) {

                    fila.asociadoEncontrado = false;
                    fila.nombreDb = fila.nombreExcel;
                    fila.activoAsociado = null;
                    fila.fechaRetiroAsociado = null;

                    fila.operacion =
                            OMITIDO_NO_ASOCIADO;

                    fila.valido =
                            true;

                    fila.procesar =
                            false;

                    fila.mensaje =
                            "El documento "
                                    + fila.documento
                                    + " no existe en tblAsociado. "
                                    + "El crédito se omite porque tblAporteCredito "
                                    + "requiere un cod_asp existente.";

                    analisis.omitidosNoAsociado++;

                    analisis.filas.add(
                            fila
                    );

                    continue;
                }

                fila.asociadoEncontrado = true;
                fila.nombreDb = asociado.nombre;
                fila.activoAsociado = asociado.activo;
                fila.fechaRetiroAsociado = asociado.fechaRetiro;

                SaldoResultado saldo = resolverSaldoCorte(
                        row,
                        periodo,
                        periodoExcel.primeraColumnaSaldo,
                        periodoExcel.columnaPeriodo,
                        fila.fechaDesembolso,
                        fila.inicioDcto,
                        evaluator,
                        formatter
                );

                if (saldo.error) {
                    marcarError(fila, saldo.mensaje);
                    analisis.errores++;
                    analisis.filas.add(fila);
                    continue;
                }

                if (saldo.omitir) {
                    fila.saldoCorte = null;
                    fila.operacion = FUERA_CORTE;
                    fila.valido = true;
                    fila.procesar = false;
                    fila.mensaje = saldo.mensaje;
                    analisis.omitidosFueraCorte++;
                    analisis.filas.add(fila);
                    continue;
                }

                fila.saldoCorte = saldo.valor.setScale(2, RoundingMode.HALF_UP);

                String llaveCredito = llaveCredito(
                        fila.documento,
                        fila.valorDesembolsado,
                        fila.fechaDesembolso
                );

                List<CreditoDb> coincidencias = creditosDb.porLlave.get(llaveCredito);

                if (coincidencias != null && coincidencias.size() == 1) {
                    fila.idCreditoExistente = coincidencias.get(0).id;
                    fila.operacion = ACTUALIZAR_CREDITO;
                    fila.valido = true;
                    fila.procesar = true;
                    fila.mensaje =
                            "Se encontró el crédito por documento + valor desembolsado + fecha de desembolso; "
                                    + "se actualizará tblAporteCredito.";
                    analisis.actualizar++;
                    analisis.creditosEnCorte++;
                    analisis.filas.add(fila);
                    continue;
                }

                if (coincidencias != null && coincidencias.size() > 1) {
                    marcarError(
                            fila,
                            "Existen varios registros en tblAporteCredito con el mismo documento, "
                                    + "valor desembolsado y fecha de desembolso. "
                                    + "No es seguro decidir cuál actualizar."
                    );
                    analisis.errores++;
                    analisis.filas.add(fila);
                    continue;
                }

                fila.operacion = INSERTAR_CREDITO;
                fila.valido = true;
                fila.procesar = true;
                fila.mensaje =
                        "Se insertará un nuevo registro en tblAporteCredito.";
                analisis.nuevos++;
                analisis.creditosEnCorte++;
                analisis.filas.add(fila);
            }

            return analisis;
        }
    }

    private YearMonth resolverPeriodoCorte() {
        return YearMonth.now(ZONA_NEGOCIO).minusMonths(1);
    }


    private String etiquetaPeriodo(YearMonth periodo) {
        String[] meses = {
                "ene", "feb", "mar", "abr", "may", "jun",
                "jul", "ago", "sep", "oct", "nov", "dic"
        };

        return meses[periodo.getMonthValue() - 1]
                + String.format("%02d", periodo.getYear() % 100);
    }

    private PeriodoExcel encontrarColumnaPeriodo(
            Row header,
            YearMonth periodo,
            DataFormatter formatter,
            FormulaEvaluator evaluator) {

        int primeraColumnaSaldo = -1;
        int columnaPeriodo = -1;
        List<String> periodosDisponibles = new ArrayList<>();

        for (int c = PRIMERA_COLUMNA_SALDOS_DEFAULT; c < header.getLastCellNum(); c++) {

            Cell cell = header.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            YearMonth periodoCelda = leerPeriodoEncabezado(cell, formatter, evaluator);

            if (periodoCelda == null) {
                continue;
            }

            if (primeraColumnaSaldo < 0) {
                primeraColumnaSaldo = c;
            }

            periodosDisponibles.add(etiquetaPeriodo(periodoCelda));

            if (periodo.equals(periodoCelda)) {
                columnaPeriodo = c;
            }
        }

        if (columnaPeriodo < 0) {
            throw new IllegalArgumentException(
                    "No se encontró en el Excel la columna correspondiente al corte "
                            + etiquetaPeriodo(periodo)
                            + ". Periodos disponibles: "
                            + String.join(", ", periodosDisponibles)
            );
        }

        PeriodoExcel resultado = new PeriodoExcel();
        resultado.primeraColumnaSaldo = primeraColumnaSaldo;
        resultado.columnaPeriodo = columnaPeriodo;
        return resultado;
    }


    private YearMonth leerPeriodoEncabezado(
            Cell cell,
            DataFormatter formatter,
            FormulaEvaluator evaluator) {

        if (cell == null) {
            return null;
        }

        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                double valor = cell.getNumericCellValue();
                if (DateUtil.isValidExcelDate(valor)) {
                    Date fecha = DateUtil.getJavaDate(valor, false, TIME_ZONE_NEGOCIO);
                    LocalDate localDate = fecha.toInstant()
                            .atZone(ZONA_NEGOCIO)
                            .toLocalDate();
                    return YearMonth.from(localDate);
                }
            }

            if (cell.getCellType() == CellType.FORMULA) {
                CellValue value = evaluator.evaluate(cell);
                if (value != null && value.getCellType() == CellType.NUMERIC) {
                    double valor = value.getNumberValue();
                    if (DateUtil.isValidExcelDate(valor)) {
                        Date fecha = DateUtil.getJavaDate(valor, false, TIME_ZONE_NEGOCIO);
                        LocalDate localDate = fecha.toInstant()
                                .atZone(ZONA_NEGOCIO)
                                .toLocalDate();
                        return YearMonth.from(localDate);
                    }
                }
            }

            String texto = formatter.formatCellValue(cell, evaluator).trim();
            return parsePeriodoTexto(texto);

        } catch (Exception e) {
            return null;
        }
    }


    private YearMonth parsePeriodoTexto(String valor) {

        if (valor == null || valor.trim().isEmpty()) {
            return null;
        }

        String texto = normalizar(valor).replace(" ", "");
        texto = texto.replace("-", "").replace("/", "").replace(".", "");

        String[] meses = {
                "ENE", "FEB", "MAR", "ABR", "MAY", "JUN",
                "JUL", "AGO", "SEP", "OCT", "NOV", "DIC"
        };

        for (int i = 0; i < meses.length; i++) {
            if (texto.startsWith(meses[i])) {
                String anioTexto = texto.substring(meses[i].length());
                if (anioTexto.matches("\\d{2}")) {
                    return YearMonth.of(2000 + Integer.parseInt(anioTexto), i + 1);
                }
                if (anioTexto.matches("\\d{4}")) {
                    return YearMonth.of(Integer.parseInt(anioTexto), i + 1);
                }
            }
        }

        return null;
    }

    private SaldoResultado resolverSaldoCorte(
            Row row,
            YearMonth periodo,
            int primeraColumnaSaldo,
            int columnaPeriodo,
            LocalDate fechaDesembolso,
            LocalDate inicioDcto,
            FormulaEvaluator evaluator,
            DataFormatter formatter) {

        NumeroCelda actual = leerNumeroCelda(
                row.getCell(columnaPeriodo, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL),
                evaluator,
                formatter
        );

        if (actual.error) {
            return SaldoResultado.error(actual.mensaje);
        }

        if (actual.tieneNumero) {
            if (actual.valor.compareTo(BigDecimal.ZERO) < 0) {
                return SaldoResultado.error("El saldo del periodo no puede ser negativo.");
            }
            return SaldoResultado.valor(actual.valor);
        }

        if (actual.esNA) {
            return SaldoResultado.valor(BigDecimal.ZERO);
        }

        for (int c = columnaPeriodo - 1; c >= primeraColumnaSaldo; c--) {

            NumeroCelda anterior = leerNumeroCelda(
                    row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL),
                    evaluator,
                    formatter
            );

            if (anterior.error) {
                continue;
            }

            if (anterior.esNA) {
                return SaldoResultado.valor(BigDecimal.ZERO);
            }

            if (!anterior.tieneNumero) {
                continue;
            }

            if (anterior.valor.compareTo(BigDecimal.ZERO) == 0) {
                return SaldoResultado.valor(BigDecimal.ZERO);
            }

            if (anterior.valor.compareTo(BigDecimal.ZERO) > 0) {
                return SaldoResultado.error(
                        "La columna " + etiquetaPeriodo(periodo)
                                + " está vacía, pero el último saldo histórico es positivo ("
                                + anterior.valor.setScale(2, RoundingMode.HALF_UP)
                                + "). Revisa la fila en el Excel."
                );
            }
        }

        YearMonth mesInicio = inicioDcto == null ? null : YearMonth.from(inicioDcto);

        if ((mesInicio != null && mesInicio.isAfter(periodo))
                || (fechaDesembolso != null && fechaDesembolso.isAfter(periodo.atEndOfMonth()))) {

            return SaldoResultado.omitir(
                    "Crédito fuera del corte " + etiquetaPeriodo(periodo)
                            + ": aún no presenta saldo para ese periodo."
            );
        }

        return SaldoResultado.error(
                "No se encontró saldo para el corte " + etiquetaPeriodo(periodo)
                        + " ni un saldo histórico que permita determinar el valor."
        );
    }


    private NumeroCelda leerNumeroCelda(
            Cell cell,
            FormulaEvaluator evaluator,
            DataFormatter formatter) {

        NumeroCelda resultado = new NumeroCelda();

        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return resultado;
        }

        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                resultado.tieneNumero = true;
                resultado.valor = BigDecimal.valueOf(cell.getNumericCellValue());
                return resultado;
            }

            if (cell.getCellType() == CellType.FORMULA) {
                CellValue value = evaluator.evaluate(cell);

                if (value == null) {
                    return resultado;
                }

                if (value.getCellType() == CellType.NUMERIC) {
                    resultado.tieneNumero = true;
                    resultado.valor = BigDecimal.valueOf(value.getNumberValue());
                    return resultado;
                }

                if (value.getCellType() == CellType.ERROR) {
                    if (value.getErrorValue() == FormulaError.NA.getCode()) {
                        resultado.esNA = true;
                        return resultado;
                    }
                    resultado.error = true;
                    resultado.mensaje = "La celda contiene un error de Excel: "
                            + FormulaError.forInt(value.getErrorValue()).getString();
                    return resultado;
                }

                if (value.getCellType() == CellType.STRING) {
                    return parseNumeroTexto(value.getStringValue());
                }
            }

            if (cell.getCellType() == CellType.ERROR) {
                if (cell.getErrorCellValue() == FormulaError.NA.getCode()) {
                    resultado.esNA = true;
                    return resultado;
                }
                resultado.error = true;
                resultado.mensaje = "La celda contiene un error de Excel: "
                        + FormulaError.forInt(cell.getErrorCellValue()).getString();
                return resultado;
            }

            return parseNumeroTexto(formatter.formatCellValue(cell, evaluator));

        } catch (Exception e) {
            resultado.error = true;
            resultado.mensaje = "No fue posible interpretar el saldo: " + e.getMessage();
            return resultado;
        }
    }


    private NumeroCelda parseNumeroTexto(String texto) {

        NumeroCelda resultado = new NumeroCelda();
        String valor = texto == null ? "" : texto.trim();

        if (valor.isEmpty() || "-".equals(valor) || ".".equals(valor)) {
            return resultado;
        }

        if ("#N/A".equalsIgnoreCase(valor) || "N/A".equalsIgnoreCase(valor)) {
            resultado.esNA = true;
            return resultado;
        }

        try {
            String limpio = valor
                    .replace("$", "")
                    .replace("COP", "")
                    .replace(" ", "")
                    .replace(",", "");

            resultado.valor = new BigDecimal(limpio);
            resultado.tieneNumero = true;
            return resultado;

        } catch (NumberFormatException e) {
            resultado.error = true;
            resultado.mensaje = "Valor no numérico en saldo: '" + valor + "'.";
            return resultado;
        }
    }

    private Map<String, AsociadoDb> cargarAsociados() {

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT cod_asp, nom_aso, activo, fecha_retiro "
                        + "FROM FEMPROBIEN.dbo.tblAsociado"
        );

        Map<String, AsociadoDb> resultado = new HashMap<>();

        for (Map<String, Object> row : rows) {
            String documento = normalizarDocumento(texto(row.get("cod_asp")));
            if (documento.isEmpty()) {
                continue;
            }

            AsociadoDb asociado = new AsociadoDb();
            asociado.documento = documento;
            asociado.nombre = texto(row.get("nom_aso"));
            asociado.activo = toBoolean(row.get("activo"));
            asociado.fechaRetiro = toLocalDate(row.get("fecha_retiro"));
            resultado.put(documento, asociado);
        }

        return resultado;
    }


    private CreditosDb cargarCreditosExistentes() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, cod_asp, val_desm, fecha_desem "
                        + "FROM FEMPROBIEN.dbo.tblAporteCredito"
        );

        CreditosDb resultado = new CreditosDb();
        resultado.porLlave = new HashMap<>();

        for (Map<String, Object> row : rows) {

            CreditoDb credito = new CreditoDb();
            credito.id = toInteger(row.get("id"));
            credito.documento = normalizarDocumento(texto(row.get("cod_asp")));
            credito.valorDesembolsado = toBigDecimal(row.get("val_desm"));
            credito.fechaDesembolso = toLocalDate(row.get("fecha_desem"));

            if (!credito.documento.isEmpty()
                    && credito.valorDesembolsado != null
                    && credito.fechaDesembolso != null) {

                String llave = llaveCredito(
                        credito.documento,
                        credito.valorDesembolsado,
                        credito.fechaDesembolso
                );

                List<CreditoDb> lista = resultado.porLlave.get(llave);
                if (lista == null) {
                    lista = new ArrayList<>();
                    resultado.porLlave.put(llave, lista);
                }
                lista.add(credito);
            }
        }

        return resultado;
    }


    private String llaveCredito(
            String documento,
            BigDecimal valor,
            LocalDate fecha) {

        return normalizarDocumento(documento)
                + "|"
                + valor.setScale(2, RoundingMode.HALF_UP).toPlainString()
                + "|"
                + fecha.toString();
    }

    private Map<String, String> obtenerColumnasTabla() {

        List<String> columnas = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME "
                        + "FROM FEMPROBIEN.INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_SCHEMA = 'dbo' "
                        + "AND TABLE_NAME = 'tblAporteCredito'",
                String.class
        );

        Map<String, String> resultado = new HashMap<>();
        for (String columna : columnas) {
            resultado.put(columna.toLowerCase(Locale.ROOT), columna);
        }
        return resultado;
    }


    private void validarColumnasObligatoriasTabla(Map<String, String> columnas) {

        List<String> obligatorias = Arrays.asList(
                "id",
                "cod_asp",
                "nom_aso",
                "val_desm",
                "fecha_desem",
                "inicio_dcto",
                "fec_fin",
                "plazo",
                "valor_cuota",
                "valor_pagar"
        );

        List<String> faltantes = new ArrayList<>();

        for (String columna : obligatorias) {
            if (!columnas.containsKey(columna.toLowerCase(Locale.ROOT))) {
                faltantes.add(columna);
            }
        }

        if (!faltantes.isEmpty()) {
            throw new IllegalArgumentException(
                    "tblAporteCredito no tiene las columnas base esperadas por FEMPROBIEN: "
                            + String.join(", ", faltantes)
                            + ". No se realizará ninguna modificación de estructura automáticamente."
            );
        }
    }


    private void validarEstructuraTablaBase() {
        validarColumnasObligatoriasTabla(obtenerColumnasTabla());
    }


    private Map<String, Object> construirDatosCredito(
            FilaCredito fila,
            Map<String, String> columnas) {

        Map<String, Object> datos = new LinkedHashMap<>();

        ponerSiExiste(datos, columnas, "cod_asp", fila.documento);
        ponerSiExiste(
                datos,
                columnas,
                "nom_aso",
                fila.nombreDb == null || fila.nombreDb.trim().isEmpty()
                        ? fila.nombreExcel.trim()
                        : fila.nombreDb.trim()
        );
        ponerSiExiste(datos, columnas, "val_desm", fila.valorDesembolsado.setScale(2, RoundingMode.HALF_UP));
        ponerSiExiste(datos, columnas, "fecha_desem", java.sql.Date.valueOf(fila.fechaDesembolso));
        ponerSiExiste(datos, columnas, "inicio_dcto", java.sql.Date.valueOf(fila.inicioDcto));
        ponerSiExiste(datos, columnas, "fec_fin", java.sql.Date.valueOf(fila.fechaFin));
        ponerSiExiste(datos, columnas, "plazo", fila.plazo);
        ponerSiExiste(datos, columnas, "valor_cuota", fila.valorCuota.setScale(2, RoundingMode.HALF_UP));
        ponerSiExiste(datos, columnas, "valor_pagar", fila.saldoCorte.setScale(2, RoundingMode.HALF_UP));

        if (fila.asociadoEncontrado) {

            ponerSiExiste(
                    datos,
                    columnas,
                    "activo",
                    fila.activoAsociado
            );

            ponerSiExiste(
                    datos,
                    columnas,
                    "fecha_retiro",
                    fila.fechaRetiroAsociado == null
                            ? null
                            : java.sql.Date.valueOf(
                            fila.fechaRetiroAsociado
                    )
            );
        }

        ponerSiExiste(datos, columnas, "empresa", fila.empresa);
        ponerSiExiste(datos, columnas, "tipo_doc", fila.tipoDoc);

        return datos;
    }


    private void ponerSiExiste(
            Map<String, Object> datos,
            Map<String, String> columnas,
            String nombre,
            Object valor) {

        String real = columnas.get(nombre.toLowerCase(Locale.ROOT));
        if (real != null) {
            datos.put(real, valor);
        }
    }


    private void insertarCredito(
            Map<String, Object> datos,
            Map<String, String> columnasTabla) {

        StringBuilder cols = new StringBuilder();
        StringBuilder values = new StringBuilder();
        List<Object> params = new ArrayList<>();

        for (Map.Entry<String, Object> entry : datos.entrySet()) {
            if ("id".equalsIgnoreCase(entry.getKey())) {
                continue;
            }

            if (cols.length() > 0) {
                cols.append(", ");
                values.append(", ");
            }

            cols.append("[").append(entry.getKey()).append("]");
            values.append("?");
            params.add(entry.getValue());
        }

        jdbcTemplate.update(
                "INSERT INTO FEMPROBIEN.dbo.tblAporteCredito ("
                        + cols + ") VALUES (" + values + ")",
                params.toArray()
        );
    }


    private void actualizarCredito(
            Integer id,
            Map<String, Object> datos,
            Map<String, String> columnasTabla) {

        if (id == null) {
            throw new IllegalArgumentException(
                    "No se encontró el id del crédito a actualizar."
            );
        }


        String columnaValorPagar =
                columnasTabla.get(
                        "valor_pagar"
                );


        if (
                columnaValorPagar == null
                        || columnaValorPagar.trim().isEmpty()
        ) {

            throw new IllegalStateException(
                    "tblAporteCredito no contiene la columna valor_pagar."
            );
        }


        Object valorPagar =
                null;


        for (
                Map.Entry<String, Object> entry
                : datos.entrySet()
        ) {

            if (
                    "valor_pagar".equalsIgnoreCase(
                            entry.getKey()
                    )
            ) {

                valorPagar =
                        entry.getValue();

                break;
            }
        }


        if (valorPagar == null) {

            throw new IllegalArgumentException(
                    "No se encontró el saldo del período para actualizar el crédito."
            );
        }


        jdbcTemplate.update(
                "UPDATE FEMPROBIEN.dbo.tblAporteCredito "
                        + "SET ["
                        + columnaValorPagar
                        + "] = ? "
                        + "WHERE id = ?",
                valorPagar,
                id
        );
    }

    private Map<String, Object> construirRespuestaValidacion(Analisis analisis) {

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", 200);
        response.put(
                "message",
                "Validación completada. No se realizaron cambios en la base de datos."
        );
        response.put("periodoCorte", analisis.periodo.toString());
        response.put("fechaCorte", analisis.periodo.atEndOfMonth().toString());
        response.put("etiquetaPeriodo", etiquetaPeriodo(analisis.periodo));
        response.put("columnaExcel", CellReference.convertNumToColString(analisis.columnaPeriodo));
        response.put("filasLeidas", analisis.filasLeidas);
        response.put("creditosEnCorte", analisis.creditosEnCorte);
        response.put("nuevos", analisis.nuevos);
        response.put("actualizar", analisis.actualizar);
        response.put("omitidosFueraCorte", analisis.omitidosFueraCorte);
        response.put("omitidosNoAsociado", analisis.omitidosNoAsociado);
        response.put("errores", analisis.errores);
        response.put("detalle", construirDetalle(analisis.filas));

        return response;
    }


    private List<Map<String, Object>> construirDetalle(List<FilaCredito> filas) {

        List<Map<String, Object>> detalle = new ArrayList<>();

        for (FilaCredito fila : filas) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fila", fila.filaExcel);
            item.put("obligacion", fila.numeroObligacion);
            item.put("documento", fila.documento);
            item.put("nombre", fila.nombreExcel);
            item.put("fechaDesembolso", fila.fechaDesembolso == null ? null : fila.fechaDesembolso.toString());
            item.put("valorDesembolsado", fila.valorDesembolsado);
            item.put("saldoCorte", fila.saldoCorte);
            item.put("operacion", fila.operacion);
            item.put("valido", fila.valido);
            item.put("procesar", fila.procesar);
            item.put("mensaje", fila.mensaje);
            detalle.add(item);
        }

        return detalle;
    }


    private void marcarError(FilaCredito fila, String mensaje) {
        fila.operacion = ERROR;
        fila.valido = false;
        fila.procesar = false;
        fila.mensaje = mensaje;
    }

    private int encontrarFilaEncabezados(
            Sheet sheet,
            DataFormatter formatter,
            FormulaEvaluator evaluator) {

        int limite = Math.min(sheet.getLastRowNum(), 10);

        for (int r = 0; r <= limite; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }

            Map<String, Integer> columnas = obtenerColumnasTexto(row, formatter, evaluator);

            boolean documento = columnas.containsKey("N DOCUMENTO")
                    || columnas.containsKey("NO DOCUMENTO")
                    || columnas.containsKey("DOCUMENTO");

            boolean desembolso = columnas.containsKey("VALOR DESEMBOLSADO");

            if (documento && desembolso) {
                return r;
            }
        }

        throw new IllegalArgumentException(
                "No se encontró la fila de encabezados del archivo de cartera."
        );
    }


    private Map<String, Integer> obtenerColumnasTexto(
            Row row,
            DataFormatter formatter,
            FormulaEvaluator evaluator) {

        Map<String, Integer> resultado = new HashMap<>();

        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell == null) {
                continue;
            }

            String valor = normalizar(leerTextoCelda(cell, formatter, evaluator));
            if (!valor.isEmpty()) {
                resultado.put(valor, c);
            }
        }

        return resultado;
    }


    private int obtenerColumna(
            Map<String, Integer> columnas,
            boolean obligatoria,
            String... aliases) {

        for (String alias : aliases) {
            Integer index = columnas.get(normalizar(alias));
            if (index != null) {
                return index;
            }
        }

        if (obligatoria) {
            throw new IllegalArgumentException(
                    "No se encontró la columna requerida: " + aliases[0]
            );
        }

        return -1;
    }


    private boolean esFilaVaciaOPlantilla(
            Row row,
            int colObligacion,
            int colDocumento,
            int colNombre,
            int colValorDesembolsado,
            int colFechaDesembolso,
            DataFormatter formatter,
            FormulaEvaluator evaluator) {

        String obligacion = leerTexto(row, colObligacion, formatter, evaluator);
        String documento = leerTexto(row, colDocumento, formatter, evaluator);
        String nombre = leerTexto(row, colNombre, formatter, evaluator);
        String valor = leerTexto(row, colValorDesembolsado, formatter, evaluator);
        String fecha = leerTexto(row, colFechaDesembolso, formatter, evaluator);

        if (obligacion.isEmpty()
                && documento.isEmpty()
                && nombre.isEmpty()
                && fecha.isEmpty()) {

            return true;
        }

        return !obligacion.isEmpty()
                && documento.isEmpty()
                && nombre.isEmpty()
                && valor.isEmpty()
                && fecha.isEmpty();
    }

    private String leerTexto(
            Row row,
            int columna,
            DataFormatter formatter,
            FormulaEvaluator evaluator) {

        if (row == null || columna < 0) {
            return "";
        }

        Cell cell = row.getCell(columna, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return leerTextoCelda(cell, formatter, evaluator);
    }


    private String leerTextoCelda(
            Cell cell,
            DataFormatter formatter,
            FormulaEvaluator evaluator) {

        if (cell == null) {
            return "";
        }

        try {
            return formatter.formatCellValue(cell, evaluator).trim();
        } catch (Exception e) {
            return formatter.formatCellValue(cell).trim();
        }
    }


    private BigDecimal leerDecimalObligatorio(
            Row row,
            int columna,
            FormulaEvaluator evaluator,
            String nombreCampo) {

        Cell cell = row.getCell(columna, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        BigDecimal valor = leerDecimal(cell, evaluator);

        if (valor == null) {
            throw new IllegalArgumentException(nombreCampo + " es obligatorio.");
        }

        return valor.setScale(2, RoundingMode.HALF_UP);
    }


    private BigDecimal leerDecimal(Cell cell, FormulaEvaluator evaluator) {

        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }

        if (cell.getCellType() == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue());
        }

        if (cell.getCellType() == CellType.FORMULA) {
            CellValue value = evaluator.evaluate(cell);
            if (value == null) {
                return null;
            }
            if (value.getCellType() == CellType.NUMERIC) {
                return BigDecimal.valueOf(value.getNumberValue());
            }
            if (value.getCellType() == CellType.ERROR
                    && value.getErrorValue() == FormulaError.NA.getCode()) {
                return BigDecimal.ZERO;
            }
            if (value.getCellType() == CellType.STRING) {
                return parseBigDecimal(value.getStringValue());
            }
        }

        if (cell.getCellType() == CellType.ERROR
                && cell.getErrorCellValue() == FormulaError.NA.getCode()) {
            return BigDecimal.ZERO;
        }

        return parseBigDecimal(cell.toString());
    }


    private BigDecimal parseBigDecimal(String texto) {

        if (texto == null) {
            return null;
        }

        String valor = texto.trim();

        if (valor.isEmpty() || "-".equals(valor) || ".".equals(valor)) {
            return null;
        }

        if ("#N/A".equalsIgnoreCase(valor) || "N/A".equalsIgnoreCase(valor)) {
            return BigDecimal.ZERO;
        }

        String limpio = valor
                .replace("$", "")
                .replace("COP", "")
                .replace(" ", "")
                .replace(",", "");

        try {
            return new BigDecimal(limpio);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Valor numérico inválido: '" + valor + "'.");
        }
    }


    private Integer leerEnteroObligatorio(
            Row row,
            int columna,
            FormulaEvaluator evaluator,
            String nombreCampo) {

        BigDecimal valor = leerDecimal(
                row.getCell(columna, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL),
                evaluator
        );

        if (valor == null) {
            throw new IllegalArgumentException(nombreCampo + " es obligatorio.");
        }

        return valor.intValue();
    }


    private LocalDate leerFechaObligatoria(
            Row row,
            int columna,
            DataFormatter formatter,
            FormulaEvaluator evaluator,
            String nombreCampo) {

        LocalDate fecha = leerFecha(
                row.getCell(columna, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL),
                formatter,
                evaluator
        );

        if (fecha == null) {
            throw new IllegalArgumentException(nombreCampo + " es obligatoria.");
        }

        return fecha;
    }


    private LocalDate leerFechaMesAnioObligatoria(
            Row row,
            int columna,
            DataFormatter formatter,
            FormulaEvaluator evaluator,
            String nombreCampo) {

        Cell cell = row.getCell(columna, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);

        LocalDate fecha = leerFecha(cell, formatter, evaluator);
        if (fecha != null) {
            return fecha;
        }

        String texto = leerTextoCelda(cell, formatter, evaluator);
        YearMonth mes = parseMesAnioEspanol(texto);

        if (mes == null) {
            throw new IllegalArgumentException(
                    nombreCampo + " no tiene un mes/año válido: '" + texto + "'."
            );
        }

        return mes.atDay(1);
    }


    private LocalDate leerFecha(
            Cell cell,
            DataFormatter formatter,
            FormulaEvaluator evaluator) {

        if (cell == null) {
            return null;
        }

        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                double valor = cell.getNumericCellValue();
                if (DateUtil.isValidExcelDate(valor)) {
                    Date fecha = DateUtil.getJavaDate(valor, false, TIME_ZONE_NEGOCIO);
                    return fecha.toInstant().atZone(ZONA_NEGOCIO).toLocalDate();
                }
            }

            if (cell.getCellType() == CellType.FORMULA) {
                CellValue value = evaluator.evaluate(cell);
                if (value != null && value.getCellType() == CellType.NUMERIC
                        && DateUtil.isValidExcelDate(value.getNumberValue())) {
                    Date fecha = DateUtil.getJavaDate(
                            value.getNumberValue(),
                            false,
                            TIME_ZONE_NEGOCIO
                    );
                    return fecha.toInstant().atZone(ZONA_NEGOCIO).toLocalDate();
                }
            }

            String texto = formatter.formatCellValue(cell, evaluator).trim();
            if (texto.isEmpty()) {
                return null;
            }

            List<DateTimeFormatter> formatos = Arrays.asList(
                    DateTimeFormatter.ofPattern("d/M/yyyy"),
                    DateTimeFormatter.ofPattern("dd/MM/yyyy"),
                    DateTimeFormatter.ISO_LOCAL_DATE
            );

            for (DateTimeFormatter formato : formatos) {
                try {
                    return LocalDate.parse(texto, formato);
                } catch (DateTimeParseException ignored) {
                }
            }

        } catch (Exception ignored) {
        }

        return null;
    }


    private YearMonth parseMesAnioEspanol(String texto) {

        if (texto == null || texto.trim().isEmpty()) {
            return null;
        }

        String normal = normalizar(texto);
        String[] partes = normal.split(" ");

        if (partes.length < 2) {
            return null;
        }

        Integer mes = numeroMesEspanol(partes[0]);
        if (mes == null) {
            return null;
        }

        try {
            int anio = Integer.parseInt(partes[partes.length - 1]);
            if (anio < 100) {
                anio += 2000;
            }
            return YearMonth.of(anio, mes);
        } catch (NumberFormatException e) {
            return null;
        }
    }


    private Integer numeroMesEspanol(String mes) {

        String valor = normalizar(mes).replace(" ", "");

        if (valor.startsWith("ENE")) return 1;
        if (valor.startsWith("FEB")) return 2;
        if (valor.startsWith("MAR")) return 3;
        if (valor.startsWith("ABR")) return 4;
        if (valor.startsWith("MAY")) return 5;
        if (valor.startsWith("JUN")) return 6;
        if (valor.startsWith("JUL")) return 7;
        if (valor.startsWith("AGO")) return 8;
        if (valor.startsWith("SEP")) return 9;
        if (valor.startsWith("OCT")) return 10;
        if (valor.startsWith("NOV")) return 11;
        if (valor.startsWith("DIC")) return 12;

        return null;
    }

    private void validarArchivoBasico(MultipartFile archivo) {

        if (archivo == null || archivo.isEmpty()) {
            throw new IllegalArgumentException("Debes seleccionar un archivo de Excel.");
        }

        String nombre = archivo.getOriginalFilename();
        if (nombre == null || nombre.trim().isEmpty()) {
            throw new IllegalArgumentException("El archivo no tiene un nombre válido.");
        }

        String lower = nombre.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls")) {
            throw new IllegalArgumentException("Solo se permiten archivos .xlsx o .xls.");
        }
    }


    private String normalizar(String texto) {

        if (texto == null) {
            return "";
        }

        String valor = Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace("\u00A0", " ")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");

        return valor;
    }


    private String normalizarDocumento(String valor) {

        if (valor == null) {
            return "";
        }

        String texto = valor.trim();

        if (texto.matches("\\d+\\.0+")) {
            texto = texto.substring(0, texto.indexOf('.'));
        }

        return texto.replaceAll("[^0-9]", "");
    }


    private String normalizarObligacion(String valor) {

        if (valor == null) {
            return "";
        }

        return valor.trim();
    }


    private String texto(Object valor) {
        return valor == null ? "" : valor.toString().trim();
    }


    private Integer toInteger(Object valor) {
        if (valor == null) return null;
        if (valor instanceof Number) return ((Number) valor).intValue();
        String texto = valor.toString().trim();
        if (texto.isEmpty()) return null;
        return Integer.valueOf(texto);
    }


    private BigDecimal toBigDecimal(Object valor) {
        if (valor == null) return null;
        if (valor instanceof BigDecimal) return (BigDecimal) valor;
        if (valor instanceof Number) return BigDecimal.valueOf(((Number) valor).doubleValue());
        String texto = valor.toString().trim();
        if (texto.isEmpty()) return null;
        return new BigDecimal(texto);
    }


    private Boolean toBoolean(Object valor) {
        if (valor == null) return null;
        if (valor instanceof Boolean) return (Boolean) valor;
        if (valor instanceof Number) return ((Number) valor).intValue() != 0;
        String texto = valor.toString().trim();
        return "1".equals(texto)
                || "TRUE".equalsIgnoreCase(texto)
                || "Y".equalsIgnoreCase(texto)
                || "SI".equalsIgnoreCase(texto);
    }


    private LocalDate toLocalDate(Object valor) {

        if (valor == null) return null;

        if (valor instanceof java.sql.Date) {
            return ((java.sql.Date) valor).toLocalDate();
        }

        if (valor instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) valor).toLocalDateTime().toLocalDate();
        }

        if (valor instanceof Date) {
            return ((Date) valor).toInstant().atZone(ZONA_NEGOCIO).toLocalDate();
        }

        try {
            return LocalDate.parse(valor.toString().substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    private static class Analisis {
        YearMonth periodo;
        int columnaPeriodo;
        int primeraColumnaSaldo;
        int filasLeidas;
        int creditosEnCorte;
        int nuevos;
        int actualizar;
        int omitidosFueraCorte;
        int omitidosNoAsociado;
        int errores;
        List<FilaCredito> filas;
    }

    private static class PeriodoExcel {
        int primeraColumnaSaldo;
        int columnaPeriodo;
    }

    private static class AsociadoDb {
        String documento;
        String nombre;
        Boolean activo;
        LocalDate fechaRetiro;
    }

    private static class CreditoDb {
        Integer id;
        String documento;
        BigDecimal valorDesembolsado;
        LocalDate fechaDesembolso;
    }

    private static class CreditosDb {
        Map<String, List<CreditoDb>> porLlave;
    }

    private static class FilaCredito {
        int filaExcel;
        String empresa;
        String numeroObligacion = "";
        String tipoDoc;
        String documento = "";
        String nombreExcel = "";
        String nombreDb = "";
        BigDecimal valorDesembolsado;
        LocalDate fechaDesembolso;
        LocalDate inicioDcto;
        LocalDate fechaFin;
        Integer plazo;
        BigDecimal valorCuota;
        BigDecimal saldoCorte;
        boolean asociadoEncontrado;
        Boolean activoAsociado;
        LocalDate fechaRetiroAsociado;
        Integer idCreditoExistente;
        String operacion = "";
        boolean valido;
        boolean procesar;
        String mensaje = "";
    }

    private static class NumeroCelda {
        boolean tieneNumero;
        boolean esNA;
        boolean error;
        BigDecimal valor;
        String mensaje;
    }

    private static class SaldoResultado {
        boolean error;
        boolean omitir;
        BigDecimal valor;
        String mensaje;

        static SaldoResultado valor(BigDecimal valor) {
            SaldoResultado r = new SaldoResultado();
            r.valor = valor;
            return r;
        }

        static SaldoResultado error(String mensaje) {
            SaldoResultado r = new SaldoResultado();
            r.error = true;
            r.mensaje = mensaje;
            return r;
        }

        static SaldoResultado omitir(String mensaje) {
            SaldoResultado r = new SaldoResultado();
            r.omitir = true;
            r.mensaje = mensaje;
            return r;
        }
    }
}
