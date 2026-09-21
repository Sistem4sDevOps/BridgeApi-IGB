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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.text.Normalizer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class FemprobienImportService {

    private static final String HOJA_BASE = "BASE";

    public static final String NUEVO_ASOCIADO_APORTE =
            "NUEVO_ASOCIADO_APORTE";

    public static final String NUEVO_APORTE =
            "NUEVO_APORTE";

    public static final String ACTUALIZAR_APORTE =
            "ACTUALIZAR_APORTE";

    public static final String ERROR =
            "ERROR";


    /* =========================================================
       ENCABEZADOS MÍNIMOS DEL PLANO
       ========================================================= */
    private static final List<String> ENCABEZADOS_REQUERIDOS =
            Arrays.asList(
                    "DOCUMENTO",
                    "NOMBRE",
                    "FECHA DE INGRESO",
                    "FECHA DE RETIRO",
                    "SALDO APORTE SOCIAL CONSOLIDADO (3 AÑOS + NUEVO CICLO)",
                    "SALDO AHORRO PERMANENTE CONSOLIDADO (3 AÑOS + NUEVO CICLO)",
                    "SALDO RENDIMIENTO AHORRO PERMANENTE CONSOLIDADO (3 AÑOS + NUEVO CICLO)",
                    "SALDOS APORTE EMPRESA",
                    "SALDOS RENDIMIENTOS APORTE EMPRESA",
                    "SALDO AHORRO NAVIDEÑO",
                    "TOTAL CRUCES/RETIROS 2025-2026"
            );


    /* =========================================================
       COLUMNAS QUE FEMPROBIEN YA UTILIZA EN ESTADO DE CUENTA

       Se exige que estas siete columnas puedan mapearse antes
       de habilitar el procesamiento real.
       ========================================================= */
    private static final List<String> COLUMNAS_APORTES_CLAVE =
            Arrays.asList(
                    "SaldoAporteSocialConsolidado_3AniosNuevoCiclo",
                    "SaldoAhorroPermanenteConsolidado_3AniosNuevoCiclo",
                    "SaldoRendimientoAhorroPermanenteConsolidado_3AniosNuevoCiclo",
                    "SaldosAporteEmpresa",
                    "SaldosRendimientosAporteEmpresa",
                    "SaldoAhorroNavideno",
                    "TotalCrucesRetiros_2025_2026"
            );


    private final JdbcTemplate sqlServerJdbcTemplate;
    private final TransactionTemplate transactionTemplate;


    public FemprobienImportService(
            @Qualifier("sqlServerJdbcTemplate")
            JdbcTemplate sqlServerJdbcTemplate) {

        this.sqlServerJdbcTemplate =
                sqlServerJdbcTemplate;


        DataSource dataSource =
                sqlServerJdbcTemplate.getDataSource();


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

        /*
         * El proceso completo del Excel será atómico.
         * Si una fila falla durante INSERT/UPDATE, se revierte
         * todo el archivo.
         */
        this.transactionTemplate.setIsolationLevel(
                TransactionDefinition.ISOLATION_SERIALIZABLE
        );
    }


    /* =========================================================
       VALIDAR ARCHIVO

       NO modifica tblAsociado.
       NO modifica tblAportes.
       ========================================================= */
    public Map<String, Object> validarArchivo(
            MultipartFile archivo) throws Exception {

        AnalisisArchivo analisis =
                analizarArchivo(
                        archivo
                );


        return construirRespuestaValidacion(
                archivo,
                analisis
        );
    }


    /* =========================================================
       PROCESAR ARCHIVO

       REGLAS:

       1) No existe tblAsociado
          -> INSERT tblAsociado
          -> INSERT tblAportes

       2) Existe tblAsociado pero no tblAportes
          -> INSERT tblAportes

       3) Existen ambos
          -> UPDATE tblAportes

       Los asociados existentes NO se actualizan.
       ========================================================= */
    public Map<String, Object> procesarArchivo(
            MultipartFile archivo,
            String usuario) throws Exception {

        final AnalisisArchivo analisis =
                analizarArchivo(
                        archivo
                );


        if (analisis.errores > 0) {

            throw new IllegalArgumentException(
                    "El archivo contiene "
                            + analisis.errores
                            + " error(es). Debes corregirlos antes de procesar."
            );
        }


        final String usuarioProceso =
                usuario == null
                        ? ""
                        : usuario.trim();


        try {

            return transactionTemplate.execute(
                    status -> procesarDentroTransaccion(
                            archivo,
                            analisis,
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


    /* =========================================================
       PROCESAR DENTRO DE TRANSACCIÓN
       ========================================================= */
    private Map<String, Object> procesarDentroTransaccion(
            MultipartFile archivo,
            AnalisisArchivo analisis,
            String usuario) {

        int asociadosCreados = 0;
        int aportesCreados = 0;
        int aportesActualizados = 0;


        List<Map<String, Object>> detalleProceso =
                new ArrayList<>();


        for (
                FilaImportacion fila
                : analisis.filas
        ) {

            if (!fila.valido) {
                continue;
            }


            try {

                AsociadoDb asociado =
                        obtenerAsociado(
                                fila.documento
                        );


                boolean asociadoCreado =
                        false;


                if (asociado == null) {

                    asociado =
                            insertarAsociadoNuevo(
                                    fila
                            );

                    asociadosCreados++;
                    asociadoCreado = true;
                }


                boolean aporteExiste =
                        existeAporte(
                                fila.documento
                        );


                Map<String, Object> datosAporte =
                        construirDatosAporte(
                                fila,
                                asociado,
                                analisis.columnasAportesDb
                        );


                String accionRealizada;


                if (!aporteExiste) {

                    insertarAporte(
                            datosAporte,
                            analisis.columnasAportesDb
                    );

                    aportesCreados++;

                    accionRealizada =
                            asociadoCreado
                                    ? NUEVO_ASOCIADO_APORTE
                                    : NUEVO_APORTE;

                } else {

                    actualizarAporte(
                            fila.documento,
                            datosAporte,
                            analisis.columnasAportesDb
                    );

                    aportesActualizados++;

                    accionRealizada =
                            ACTUALIZAR_APORTE;
                }


                Map<String, Object> item =
                        new LinkedHashMap<>();

                item.put(
                        "fila",
                        fila.numeroFila
                );

                item.put(
                        "documento",
                        fila.documento
                );

                item.put(
                        "nombre",
                        fila.nombre
                );

                item.put(
                        "operacion",
                        accionRealizada
                );

                item.put(
                        "resultado",
                        "OK"
                );

                item.put(
                        "mensaje",
                        mensajeOperacionRealizada(
                                accionRealizada
                        )
                );

                detalleProceso.add(
                        item
                );


            } catch (Exception e) {

                /*
                 * Lanzar RuntimeException provoca rollback de TODO
                 * el archivo. No quedan cargas parciales.
                 */
                throw new IllegalStateException(
                        "Error procesando la fila "
                                + fila.numeroFila
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
                "Archivo procesado correctamente. La base de datos fue actualizada."
        );

        response.put(
                "archivo",
                archivo.getOriginalFilename()
        );

        response.put(
                "hoja",
                HOJA_BASE
        );

        response.put(
                "usuario",
                usuario
        );

        /*
         * Cada fila cuenta una sola vez:
         * - nuevo asociado + aporte = 1 fila
         * - nuevo aporte = 1 fila
         * - update = 1 fila
         */
        response.put(
                "totalProcesadas",
                detalleProceso.size()
        );

        response.put(
                "asociadosCreados",
                asociadosCreados
        );

        response.put(
                "aportesCreados",
                aportesCreados
        );

        response.put(
                "aportesActualizados",
                aportesActualizados
        );

        response.put(
                "errores",
                0
        );

        response.put(
                "columnasMapeadas",
                analisis.columnasMapeadas
        );

        response.put(
                "columnasIgnoradas",
                analisis.columnasIgnoradas
        );

        response.put(
                "detalle",
                detalleProceso
        );


        return response;
    }


    /* =========================================================
       ANALIZAR ARCHIVO
       ========================================================= */
    private AnalisisArchivo analizarArchivo(
            MultipartFile archivo) throws Exception {

        validarArchivoBasico(
                archivo
        );


        Map<String, ColumnaDb> columnasAportesDb =
                obtenerColumnasDb(
                        "tblAportes"
                );


        Map<String, AsociadoDb> asociadosExistentes =
                cargarAsociados();


        Set<String> aportesExistentes =
                cargarDocumentosAportes();


        try (
                Workbook workbook =
                        WorkbookFactory.create(
                                archivo.getInputStream()
                        )
        ) {

            Sheet sheet =
                    workbook.getSheet(
                            HOJA_BASE
                    );


            if (sheet == null) {

                throw new IllegalArgumentException(
                        "El archivo no contiene la hoja '"
                                + HOJA_BASE
                                + "'."
                );
            }


            Row filaEncabezados =
                    sheet.getRow(0);


            if (filaEncabezados == null) {

                throw new IllegalArgumentException(
                        "La hoja BASE no contiene encabezados."
                );
            }


            DataFormatter formatter =
                    new DataFormatter(
                            Locale.US
                    );


            FormulaEvaluator evaluator =
                    workbook
                            .getCreationHelper()
                            .createFormulaEvaluator();


            Map<String, Integer> encabezados =
                    obtenerEncabezados(
                            filaEncabezados,
                            formatter,
                            evaluator
                    );


            validarEncabezados(
                    encabezados
            );


            MapeoColumnas mapeoColumnas =
                    mapearColumnasAportes(
                            filaEncabezados,
                            formatter,
                            evaluator,
                            columnasAportesDb
                    );


            validarColumnasAportesClave(
                    mapeoColumnas
            );


            Integer colDocumento =
                    encabezados.get(
                            normalizarEncabezado(
                                    "DOCUMENTO"
                            )
                    );

            Integer colNombre =
                    encabezados.get(
                            normalizarEncabezado(
                                    "NOMBRE"
                            )
                    );

            Integer colFechaIngreso =
                    encabezados.get(
                            normalizarEncabezado(
                                    "FECHA DE INGRESO"
                            )
                    );

            Integer colFechaRetiro =
                    encabezados.get(
                            normalizarEncabezado(
                                    "FECHA DE RETIRO"
                            )
                    );


            Set<String> documentosExcel =
                    new HashSet<>();


            AnalisisArchivo analisis =
                    new AnalisisArchivo();

            analisis.columnasAportesDb =
                    columnasAportesDb;

            analisis.columnasMapeadas =
                    mapeoColumnas.nombresMapeados;

            analisis.columnasIgnoradas =
                    mapeoColumnas.nombresIgnorados;


            for (
                    int i = 1;
                    i <= sheet.getLastRowNum();
                    i++
            ) {

                Row row =
                        sheet.getRow(i);


                if (row == null) {
                    continue;
                }


                String documento =
                        leerDocumento(
                                row,
                                colDocumento,
                                formatter,
                                evaluator
                        );


                String nombre =
                        leerTexto(
                                row,
                                colNombre,
                                formatter,
                                evaluator
                        );


                if (
                        documento.isEmpty()
                                && nombre.isEmpty()
                ) {
                    continue;
                }


                /*
                 * El archivo estándar de FEMPROBIEN contiene al final
                 * algunas filas informativas que NO corresponden a
                 * asociados, por ejemplo:
                 *
                 *   CONSIGNAR
                 *   CRUCE CON CREDITO Y CONSIGNAR DIFERENCIA (SI APLICA)
                 *   Nuevo
                 *
                 * Estas filas vienen por defecto en el formato Excel,
                 * no tienen DOCUMENTO y por lo tanto:
                 *
                 * - NO se cuentan como registros.
                 * - NO se marcan como error.
                 * - NO se procesan en tblAsociado ni tblAportes.
                 */
                if (
                        esFilaInformativaExcel(
                                documento,
                                nombre
                        )
                ) {
                    continue;
                }


                analisis.totalFilas++;


                FilaImportacion fila =
                        new FilaImportacion();

                fila.numeroFila =
                        i + 1;

                fila.documento =
                        documento;

                fila.nombre =
                        nombre;


                try {

                    fila.fechaIngreso =
                            leerFechaSql(
                                    row,
                                    colFechaIngreso,
                                    formatter,
                                    evaluator,
                                    false
                            );

                    fila.fechaRetiro =
                            leerFechaSql(
                                    row,
                                    colFechaRetiro,
                                    formatter,
                                    evaluator,
                                    false
                            );


                    if (documento.isEmpty()) {

                        marcarError(
                                fila,
                                "La fila no contiene DOCUMENTO."
                        );

                    } else if (
                            !documento.matches(
                                    "\\d+"
                            )
                    ) {

                        marcarError(
                                fila,
                                "El DOCUMENTO debe contener únicamente números."
                        );

                    } else if (
                            documentosExcel.contains(
                                    documento
                            )
                    ) {

                        marcarError(
                                fila,
                                "El documento está repetido dentro del archivo."
                        );

                    } else {

                        documentosExcel.add(
                                documento
                        );


                        boolean existeAsociado =
                                asociadosExistentes.containsKey(
                                        documento
                                );


                        boolean existeAporte =
                                aportesExistentes.contains(
                                        documento
                                );


                        if (
                                !existeAsociado
                                        && (
                                        nombre == null
                                                || nombre.trim().isEmpty()
                                )
                        ) {

                            marcarError(
                                    fila,
                                    "El asociado es nuevo y la columna NOMBRE está vacía."
                            );

                        } else {

                            fila.valoresAportes =
                                    leerValoresAportes(
                                            row,
                                            mapeoColumnas.porIndice,
                                            formatter,
                                            evaluator
                                    );


                            fila.valido =
                                    true;


                            if (!existeAsociado) {

                                fila.operacion =
                                        NUEVO_ASOCIADO_APORTE;

                                fila.mensaje =
                                        "Se crearía el asociado en tblAsociado y su registro en tblAportes.";

                                analisis.nuevosAsociados++;

                            } else if (!existeAporte) {

                                fila.operacion =
                                        NUEVO_APORTE;

                                fila.mensaje =
                                        "El asociado ya existe y se crearía su registro en tblAportes.";

                                analisis.nuevosAportes++;

                            } else {

                                fila.operacion =
                                        ACTUALIZAR_APORTE;

                                fila.mensaje =
                                        "El asociado y sus aportes ya existen; se actualizaría tblAportes.";

                                analisis.aportesActualizar++;
                            }
                        }
                    }


                } catch (Exception e) {

                    marcarError(
                            fila,
                            e.getMessage() == null
                                    ? "No fue posible interpretar la fila."
                                    : e.getMessage()
                    );
                }


                if (!fila.valido) {
                    analisis.errores++;
                }


                analisis.filas.add(
                        fila
                );
            }


            return analisis;
        }
    }


    /* =========================================================
       RESPUESTA DE VALIDACIÓN
       ========================================================= */
    private Map<String, Object> construirRespuestaValidacion(
            MultipartFile archivo,
            AnalisisArchivo analisis) {

        Map<String, Object> response =
                new LinkedHashMap<>();

        response.put(
                "status",
                200
        );

        response.put(
                "message",
                "Validación completada. No se realizaron cambios en la base de datos."
        );

        response.put(
                "hoja",
                HOJA_BASE
        );

        response.put(
                "archivo",
                archivo.getOriginalFilename()
        );

        response.put(
                "totalFilas",
                analisis.totalFilas
        );

        response.put(
                "nuevosAsociados",
                analisis.nuevosAsociados
        );

        response.put(
                "nuevosAportes",
                analisis.nuevosAportes
        );

        response.put(
                "aportesActualizar",
                analisis.aportesActualizar
        );

        response.put(
                "errores",
                analisis.errores
        );

        response.put(
                "columnasMapeadas",
                analisis.columnasMapeadas
        );

        response.put(
                "columnasIgnoradas",
                analisis.columnasIgnoradas
        );


        List<Map<String, Object>> detalle =
                new ArrayList<>();


        for (
                FilaImportacion fila
                : analisis.filas
        ) {

            Map<String, Object> item =
                    new LinkedHashMap<>();

            item.put(
                    "fila",
                    fila.numeroFila
            );

            item.put(
                    "documento",
                    fila.documento
            );

            item.put(
                    "nombre",
                    fila.nombre
            );

            item.put(
                    "fechaIngreso",
                    fila.fechaIngreso == null
                            ? ""
                            : fila.fechaIngreso.toString()
            );

            item.put(
                    "fechaRetiro",
                    fila.fechaRetiro == null
                            ? ""
                            : fila.fechaRetiro.toString()
            );

            item.put(
                    "operacion",
                    fila.operacion
            );

            item.put(
                    "valido",
                    fila.valido
            );

            item.put(
                    "mensaje",
                    fila.mensaje
            );

            detalle.add(
                    item
            );
        }


        response.put(
                "detalle",
                detalle
        );


        return response;
    }


    /* =========================================================
       CREAR NUEVO ASOCIADO
       ========================================================= */
    private AsociadoDb insertarAsociadoNuevo(
            FilaImportacion fila) {

        Integer siguienteId =
                siguienteIdAsociadoBloqueado();


        boolean activo =
                fila.fechaRetiro == null;


        String sql =
                "INSERT INTO FEMPROBIEN.dbo.tblAsociado (" +
                        "id_aso, " +
                        "cod_asp, " +
                        "nom_aso, " +
                        "fec_ing, " +
                        "fecha_retiro, " +
                        "activo" +
                        ") VALUES (?, ?, ?, ?, ?, ?)";


        sqlServerJdbcTemplate.update(
                sql,
                siguienteId,
                fila.documento,
                fila.nombre,
                fila.fechaIngreso,
                fila.fechaRetiro,
                activo
        );


        AsociadoDb asociado =
                new AsociadoDb();

        asociado.idAso =
                siguienteId;

        asociado.codAsp =
                fila.documento;

        asociado.activo =
                activo;

        asociado.fechaRetiro =
                fila.fechaRetiro;


        return asociado;
    }


    private Integer siguienteIdAsociadoBloqueado() {

        Integer siguienteId =
                sqlServerJdbcTemplate.queryForObject(
                        "SELECT ISNULL(MAX(id_aso), 0) + 1 " +
                                "FROM FEMPROBIEN.dbo.tblAsociado " +
                                "WITH (UPDLOCK, HOLDLOCK)",
                        Integer.class
                );


        return siguienteId == null
                ? 1
                : siguienteId;
    }


    /* =========================================================
       CONSTRUIR DATOS DE tblAportes
       ========================================================= */
    private Map<String, Object> construirDatosAporte(
            FilaImportacion fila,
            AsociadoDb asociado,
            Map<String, ColumnaDb> columnasDb) {

        Map<String, Object> datos =
                new LinkedHashMap<>();


        if (fila.valoresAportes != null) {

            datos.putAll(
                    fila.valoresAportes
            );
        }


        agregarSiExiste(
                datos,
                columnasDb,
                "id_aso",
                asociado.idAso
        );

        agregarSiExiste(
                datos,
                columnasDb,
                "cod_asp",
                fila.documento
        );

        agregarSiExiste(
                datos,
                columnasDb,
                "nom_aso",
                fila.nombre
        );

        agregarSiExiste(
                datos,
                columnasDb,
                "fec_ing",
                fila.fechaIngreso
        );

        agregarSiExiste(
                datos,
                columnasDb,
                "fecha_retiro",
                fila.fechaRetiro
        );

        agregarSiExiste(
                datos,
                columnasDb,
                "activo",
                fila.fechaRetiro == null
        );


        return datos;
    }


    private void agregarSiExiste(
            Map<String, Object> datos,
            Map<String, ColumnaDb> columnasDb,
            String nombreColumna,
            Object valor) {

        ColumnaDb columna =
                columnasDb.get(
                        normalizarClave(
                                nombreColumna
                        )
                );


        if (columna != null) {

            datos.put(
                    columna.nombre,
                    valor
            );
        }
    }


    /* =========================================================
       INSERT tblAportes
       ========================================================= */
    private void insertarAporte(
            Map<String, Object> datos,
            Map<String, ColumnaDb> columnasDb) {

        if (datos.isEmpty()) {

            throw new IllegalArgumentException(
                    "No hay datos para insertar en tblAportes."
            );
        }


        StringBuilder columnas =
                new StringBuilder();

        StringBuilder placeholders =
                new StringBuilder();

        List<Object> parametros =
                new ArrayList<>();


        for (
                Map.Entry<String, Object> entry
                : datos.entrySet()
        ) {

            ColumnaDb columna =
                    buscarColumnaDbPorNombreReal(
                            columnasDb,
                            entry.getKey()
                    );


            if (columna == null) {
                continue;
            }


            if (columnas.length() > 0) {
                columnas.append(", ");
                placeholders.append(", ");
            }


            columnas.append("[")
                    .append(columna.nombre)
                    .append("]");

            placeholders.append("?");

            parametros.add(
                    entry.getValue()
            );
        }


        if (parametros.isEmpty()) {

            throw new IllegalArgumentException(
                    "No se encontraron columnas válidas para tblAportes."
            );
        }


        String sql =
                "INSERT INTO FEMPROBIEN.dbo.tblAportes (" +
                        columnas +
                        ") VALUES (" +
                        placeholders +
                        ")";


        sqlServerJdbcTemplate.update(
                sql,
                parametros.toArray()
        );
    }


    /* =========================================================
       UPDATE tblAportes
       ========================================================= */
    private void actualizarAporte(
            String documento,
            Map<String, Object> datos,
            Map<String, ColumnaDb> columnasDb) {

        StringBuilder set =
                new StringBuilder();

        List<Object> parametros =
                new ArrayList<>();


        for (
                Map.Entry<String, Object> entry
                : datos.entrySet()
        ) {

            ColumnaDb columna =
                    buscarColumnaDbPorNombreReal(
                            columnasDb,
                            entry.getKey()
                    );


            if (columna == null) {
                continue;
            }


            /*
             * La llave nunca se cambia durante UPDATE.
             * Tampoco se cambia id_aso del aporte existente.
             */
            if (
                    "cod_asp".equalsIgnoreCase(
                            columna.nombre
                    )
                            || "id_aso".equalsIgnoreCase(
                            columna.nombre
                    )
            ) {
                continue;
            }


            if (set.length() > 0) {
                set.append(", ");
            }


            set.append("[")
                    .append(columna.nombre)
                    .append("] = ?");

            parametros.add(
                    entry.getValue()
            );
        }


        if (parametros.isEmpty()) {

            throw new IllegalArgumentException(
                    "No hay columnas para actualizar en tblAportes."
            );
        }


        parametros.add(
                documento
        );


        String sql =
                "UPDATE FEMPROBIEN.dbo.tblAportes " +
                        "SET " +
                        set +
                        " WHERE LTRIM(RTRIM(cod_asp)) = ?";


        int actualizados =
                sqlServerJdbcTemplate.update(
                        sql,
                        parametros.toArray()
                );


        if (actualizados <= 0) {

            throw new IllegalStateException(
                    "No se actualizó tblAportes para el documento "
                            + documento
            );
        }
    }


    /* =========================================================
       MAPEO DE ENCABEZADOS EXCEL -> COLUMNAS REALES tblAportes
       ========================================================= */
    private MapeoColumnas mapearColumnasAportes(
            Row filaEncabezados,
            DataFormatter formatter,
            FormulaEvaluator evaluator,
            Map<String, ColumnaDb> columnasDb) {

        MapeoColumnas resultado =
                new MapeoColumnas();


        Map<String, String> aliases =
                construirAliases();


        for (
                int i = filaEncabezados.getFirstCellNum();
                i < filaEncabezados.getLastCellNum();
                i++
        ) {

            Cell cell =
                    filaEncabezados.getCell(
                            i,
                            Row.MissingCellPolicy.RETURN_BLANK_AS_NULL
                    );


            if (cell == null) {
                continue;
            }


            String encabezado =
                    leerTextoCelda(
                            cell,
                            formatter,
                            evaluator
                    );


            if (
                    encabezado == null
                            || encabezado.trim().isEmpty()
            ) {
                continue;
            }


            String encabezadoNormalizado =
                    normalizarClave(
                            encabezado
                    );


            /*
             * Estas columnas se manejan expresamente para
             * tblAsociado / llave y no se tratan como saldo.
             */
            if (
                    "DOCUMENTO".equals(
                            normalizarEncabezado(encabezado)
                    )
                            || "NOMBRE".equals(
                            normalizarEncabezado(encabezado)
                    )
                            || "FECHA DE INGRESO".equals(
                            normalizarEncabezado(encabezado)
                    )
                            || "FECHA DE RETIRO".equals(
                            normalizarEncabezado(encabezado)
                    )
            ) {
                continue;
            }


            ColumnaDb columna =
                    columnasDb.get(
                            encabezadoNormalizado
                    );


            if (columna == null) {

                String aliasDestino =
                        aliases.get(
                                encabezadoNormalizado
                        );


                if (aliasDestino != null) {

                    columna =
                            columnasDb.get(
                                    aliasDestino
                            );
                }
            }


            if (columna == null) {

                /*
                 * Segundo intento: ignorar la palabra ASOCIADO.
                 * Algunos encabezados del Excel la incluyen y
                 * algunas columnas históricas de SQL no.
                 */
                String comparable =
                        encabezadoNormalizado.replace(
                                "ASOCIADO",
                                ""
                        );


                for (
                        Map.Entry<String, ColumnaDb> db
                        : columnasDb.entrySet()
                ) {

                    String claveDbComparable =
                            db.getKey().replace(
                                    "ASOCIADO",
                                    ""
                            );


                    if (
                            comparable.equals(
                                    claveDbComparable
                            )
                    ) {

                        columna =
                                db.getValue();

                        break;
                    }
                }
            }


            if (columna != null) {

                resultado.porIndice.put(
                        i,
                        columna
                );

                resultado.nombresMapeados.add(
                        encabezado
                                + " -> "
                                + columna.nombre
                );

            } else {

                resultado.nombresIgnorados.add(
                        encabezado
                );
            }
        }


        return resultado;
    }


    private Map<String, String> construirAliases() {

        Map<String, String> aliases =
                new HashMap<>();


        alias(
                aliases,
                "SALDO APORTE SOCIAL CONSOLIDADO (3 AÑOS + NUEVO CICLO)",
                "SaldoAporteSocialConsolidado_3AniosNuevoCiclo"
        );

        alias(
                aliases,
                "SALDO AHORRO PERMANENTE CONSOLIDADO (3 AÑOS + NUEVO CICLO)",
                "SaldoAhorroPermanenteConsolidado_3AniosNuevoCiclo"
        );

        alias(
                aliases,
                "SALDO RENDIMIENTO AHORRO PERMANENTE CONSOLIDADO (3 AÑOS + NUEVO CICLO)",
                "SaldoRendimientoAhorroPermanenteConsolidado_3AniosNuevoCiclo"
        );

        alias(
                aliases,
                "SALDOS APORTE EMPRESA",
                "SaldosAporteEmpresa"
        );

        alias(
                aliases,
                "SALDOS RENDIMIENTOS APORTE EMPRESA",
                "SaldosRendimientosAporteEmpresa"
        );

        alias(
                aliases,
                "SALDO AHORRO NAVIDEÑO",
                "SaldoAhorroNavideno"
        );

        alias(
                aliases,
                "TOTAL CRUCES/RETIROS 2025-2026",
                "TotalCrucesRetiros_2025_2026"
        );


        return aliases;
    }


    private void alias(
            Map<String, String> aliases,
            String encabezado,
            String columnaDb) {

        aliases.put(
                normalizarClave(
                        encabezado
                ),
                normalizarClave(
                        columnaDb
                )
        );
    }


    private void validarColumnasAportesClave(
            MapeoColumnas mapeo) {

        Set<String> columnasMapeadasDb =
                new HashSet<>();


        for (
                ColumnaDb columna
                : mapeo.porIndice.values()
        ) {

            columnasMapeadasDb.add(
                    normalizarClave(
                            columna.nombre
                    )
            );
        }


        List<String> faltantes =
                new ArrayList<>();


        for (
                String requerida
                : COLUMNAS_APORTES_CLAVE
        ) {

            if (
                    !columnasMapeadasDb.contains(
                            normalizarClave(
                                    requerida
                            )
                    )
            ) {

                faltantes.add(
                        requerida
                );
            }
        }


        if (!faltantes.isEmpty()) {

            throw new IllegalArgumentException(
                    "No fue posible relacionar las columnas financieras principales con tblAportes. " +
                            "Faltan: " +
                            String.join(", ", faltantes)
            );
        }
    }


    /* =========================================================
       LEER VALORES DE APORTES
       ========================================================= */
    private Map<String, Object> leerValoresAportes(
            Row row,
            Map<Integer, ColumnaDb> columnas,
            DataFormatter formatter,
            FormulaEvaluator evaluator) {

        Map<String, Object> valores =
                new LinkedHashMap<>();


        for (
                Map.Entry<Integer, ColumnaDb> entry
                : columnas.entrySet()
        ) {

            Cell cell =
                    row.getCell(
                            entry.getKey(),
                            Row.MissingCellPolicy.RETURN_BLANK_AS_NULL
                    );


            Object valor =
                    convertirCeldaParaDb(
                            cell,
                            entry.getValue(),
                            formatter,
                            evaluator
                    );


            valores.put(
                    entry.getValue().nombre,
                    valor
            );
        }


        return valores;
    }


    private Object convertirCeldaParaDb(
            Cell cell,
            ColumnaDb columna,
            DataFormatter formatter,
            FormulaEvaluator evaluator) {

        String tipo =
                columna.tipoDato == null
                        ? ""
                        : columna.tipoDato.toLowerCase();


        if (esTipoNumerico(tipo)) {

            return leerNumero(
                    cell,
                    formatter,
                    evaluator
            );
        }


        if (esTipoFecha(tipo)) {

            return leerFechaCelda(
                    cell,
                    formatter,
                    evaluator,
                    true
            );
        }


        if (
                "bit".equals(tipo)
        ) {

            return leerBooleano(
                    cell,
                    formatter,
                    evaluator
            );
        }


        return leerTextoCelda(
                cell,
                formatter,
                evaluator
        );
    }


    private BigDecimal leerNumero(
            Cell cell,
            DataFormatter formatter,
            FormulaEvaluator evaluator) {

        if (cell == null) {
            return BigDecimal.ZERO;
        }


        try {

            CellValue value =
                    evaluator.evaluate(
                            cell
                    );


            if (value == null) {
                return BigDecimal.ZERO;
            }


            if (
                    value.getCellType()
                            == CellType.NUMERIC
            ) {

                return BigDecimal.valueOf(
                        value.getNumberValue()
                );
            }


            if (
                    value.getCellType()
                            == CellType.BLANK
            ) {

                return BigDecimal.ZERO;
            }


            if (
                    value.getCellType()
                            == CellType.ERROR
            ) {

                /*
                 * Para columnas numéricas de tblAportes:
                 * si Excel devuelve #N/A, se interpreta como 0.
                 *
                 * Otros errores de Excel (#DIV/0!, #VALUE!, etc.)
                 * continúan generando error para evitar guardar
                 * información incorrecta silenciosamente.
                 */
                if (
                        value.getErrorValue()
                                == FormulaError.NA.getCode()
                ) {

                    return BigDecimal.ZERO;
                }


                String error =
                        FormulaError.forInt(
                                value.getErrorValue()
                        ).getString();

                throw new IllegalArgumentException(
                        "La celda "
                                + posicionCelda(cell)
                                + " contiene error de Excel: "
                                + error
                );
            }


            if (
                    value.getCellType()
                            == CellType.STRING
            ) {

                return parseBigDecimal(
                        value.getStringValue(),
                        cell
                );
            }


            return parseBigDecimal(
                    formatter.formatCellValue(
                            cell,
                            evaluator
                    ),
                    cell
            );


        } catch (IllegalArgumentException e) {
            throw e;

        } catch (Exception e) {

            return parseBigDecimal(
                    formatter.formatCellValue(
                            cell
                    ),
                    cell
            );
        }
    }


    private BigDecimal parseBigDecimal(
            String texto,
            Cell cell) {

        if (
                texto == null
                        || texto.trim().isEmpty()
                        || "-".equals(texto.trim())
                        || "#N/A".equalsIgnoreCase(texto.trim())
                        || "N/A".equalsIgnoreCase(texto.trim())
        ) {

            /*
             * Los valores vacíos, guion y #N/A de columnas
             * numéricas se almacenan como 0.
             */
            return BigDecimal.ZERO;
        }


        String limpio =
                texto
                        .trim()
                        .replace("$", "")
                        .replace("COP", "")
                        .replace(" ", "")
                        .replace(",", "");


        if (
                limpio.startsWith("(")
                        && limpio.endsWith(")")
        ) {

            limpio =
                    "-"
                            + limpio.substring(
                            1,
                            limpio.length() - 1
                    );
        }


        try {

            return new BigDecimal(
                    limpio
            );

        } catch (Exception e) {

            throw new IllegalArgumentException(
                    "La celda "
                            + posicionCelda(cell)
                            + " no contiene un valor numérico válido: '"
                            + texto
                            + "'."
            );
        }
    }


    private boolean esTipoNumerico(
            String tipo) {

        return "decimal".equals(tipo)
                || "numeric".equals(tipo)
                || "money".equals(tipo)
                || "smallmoney".equals(tipo)
                || "float".equals(tipo)
                || "real".equals(tipo)
                || "int".equals(tipo)
                || "bigint".equals(tipo)
                || "smallint".equals(tipo)
                || "tinyint".equals(tipo);
    }


    private boolean esTipoFecha(
            String tipo) {

        return "date".equals(tipo)
                || "datetime".equals(tipo)
                || "datetime2".equals(tipo)
                || "smalldatetime".equals(tipo);
    }


    private Boolean leerBooleano(
            Cell cell,
            DataFormatter formatter,
            FormulaEvaluator evaluator) {

        if (cell == null) {
            return false;
        }


        CellValue value =
                evaluator.evaluate(
                        cell
                );


        if (value == null) {
            return false;
        }


        if (
                value.getCellType()
                        == CellType.BOOLEAN
        ) {

            return value.getBooleanValue();
        }


        if (
                value.getCellType()
                        == CellType.NUMERIC
        ) {

            return value.getNumberValue() != 0;
        }


        String texto =
                formatter
                        .formatCellValue(
                                cell,
                                evaluator
                        )
                        .trim()
                        .toUpperCase();


        return "1".equals(texto)
                || "SI".equals(texto)
                || "SÍ".equals(texto)
                || "TRUE".equals(texto)
                || "Y".equals(texto);
    }


    /* =========================================================
       COLUMNAS REALES SQL SERVER
       ========================================================= */
    private Map<String, ColumnaDb> obtenerColumnasDb(
            String tabla) {

        if (
                !"tblAportes".equals(tabla)
        ) {

            throw new IllegalArgumentException(
                    "Tabla no permitida para esta importación: "
                            + tabla
            );
        }


        String sql =
                "SELECT " +
                        "c.name AS COLUMN_NAME, " +
                        "ty.name AS DATA_TYPE " +
                        "FROM FEMPROBIEN.sys.columns c " +
                        "INNER JOIN FEMPROBIEN.sys.tables t " +
                        "ON t.object_id = c.object_id " +
                        "INNER JOIN FEMPROBIEN.sys.schemas s " +
                        "ON s.schema_id = t.schema_id " +
                        "INNER JOIN FEMPROBIEN.sys.types ty " +
                        "ON ty.user_type_id = c.user_type_id " +
                        "WHERE t.name = ? " +
                        "AND s.name = 'dbo' " +
                        "AND c.is_identity = 0 " +
                        "AND c.is_computed = 0 " +
                        "ORDER BY c.column_id";


        List<Map<String, Object>> filas =
                sqlServerJdbcTemplate.queryForList(
                        sql,
                        tabla
                );


        Map<String, ColumnaDb> columnas =
                new LinkedHashMap<>();


        for (
                Map<String, Object> fila
                : filas
        ) {

            ColumnaDb columna =
                    new ColumnaDb();

            columna.nombre =
                    fila.get("COLUMN_NAME") == null
                            ? ""
                            : fila.get("COLUMN_NAME").toString();

            columna.tipoDato =
                    fila.get("DATA_TYPE") == null
                            ? ""
                            : fila.get("DATA_TYPE").toString();


            columnas.put(
                    normalizarClave(
                            columna.nombre
                    ),
                    columna
            );
        }


        return columnas;
    }


    private ColumnaDb buscarColumnaDbPorNombreReal(
            Map<String, ColumnaDb> columnas,
            String nombreReal) {

        if (nombreReal == null) {
            return null;
        }


        return columnas.get(
                normalizarClave(
                        nombreReal
                )
        );
    }


    /* =========================================================
       FILAS INFORMATIVAS DEL FORMATO EXCEL

       Estas filas vienen por defecto al final de la hoja BASE
       y no corresponden a asociados.
       ========================================================= */
    private boolean esFilaInformativaExcel(
            String documento,
            String nombre) {

        /*
         * Si existe DOCUMENTO, nunca se considera fila informativa.
         */
        if (
                documento != null
                        && !documento.trim().isEmpty()
        ) {
            return false;
        }


        /*
         * IMPORTANTE:
         *
         * normalizarClave() elimina espacios, tildes, paréntesis
         * y cualquier carácter diferente de A-Z / 0-9.
         *
         * Ejemplo:
         *
         * CRUCE CON CREDITO Y CONSIGNAR DIFERENCIA (SI APLICA)
         *
         * queda:
         *
         * CRUCECONCREDITOYCONSIGNARDIFERENCIASIAPLICA
         *
         * Por eso las comparaciones también deben hacerse contra
         * valores normalizados.
         */
        String texto =
                normalizarClave(
                        nombre
                );


        if (
                texto == null
                        || texto.trim().isEmpty()
        ) {
            return false;
        }


        /*
         * Filas informativas estándar del formato.
         */
        if (
                "CONSIGNAR".equals(
                        texto
                )
                        || "NUEVO".equals(
                        texto
                )
        ) {
            return true;
        }


        /*
         * Se usa startsWith para aceptar pequeñas variaciones del
         * texto, saltos de línea o la parte "(SI APLICA)".
         */
        return texto.startsWith(
                "CRUCECONCREDITOYCONSIGNARDIFERENCIA"
        );
    }


    /* =========================================================
       ASOCIADOS EXISTENTES
       ========================================================= */
    private Map<String, AsociadoDb> cargarAsociados() {

        String sql =
                "SELECT " +
                        "id_aso, " +
                        "LTRIM(RTRIM(cod_asp)) AS cod_asp, " +
                        "activo, " +
                        "fecha_retiro " +
                        "FROM FEMPROBIEN.dbo.tblAsociado " +
                        "WHERE cod_asp IS NOT NULL " +
                        "AND LTRIM(RTRIM(cod_asp)) <> ''";


        List<Map<String, Object>> filas =
                sqlServerJdbcTemplate.queryForList(
                        sql
                );


        Map<String, AsociadoDb> resultado =
                new HashMap<>();


        for (
                Map<String, Object> fila
                : filas
        ) {

            String documento =
                    normalizarDocumento(
                            fila.get("cod_asp") == null
                                    ? ""
                                    : fila.get("cod_asp").toString()
                    );


            if (documento.isEmpty()) {
                continue;
            }


            AsociadoDb asociado =
                    new AsociadoDb();

            asociado.idAso =
                    fila.get("id_aso") == null
                            ? null
                            : ((Number) fila.get("id_aso")).intValue();

            asociado.codAsp =
                    documento;

            asociado.activo =
                    convertirBooleanSql(
                            fila.get("activo")
                    );

            asociado.fechaRetiro =
                    convertirFechaSql(
                            fila.get("fecha_retiro")
                    );


            resultado.put(
                    documento,
                    asociado
            );
        }


        return resultado;
    }


    private AsociadoDb obtenerAsociado(
            String documento) {

        String sql =
                "SELECT TOP 1 " +
                        "id_aso, " +
                        "LTRIM(RTRIM(cod_asp)) AS cod_asp, " +
                        "activo, " +
                        "fecha_retiro " +
                        "FROM FEMPROBIEN.dbo.tblAsociado " +
                        "WHERE LTRIM(RTRIM(cod_asp)) = ?";


        List<Map<String, Object>> filas =
                sqlServerJdbcTemplate.queryForList(
                        sql,
                        documento
                );


        if (filas.isEmpty()) {
            return null;
        }


        Map<String, Object> fila =
                filas.get(0);


        AsociadoDb asociado =
                new AsociadoDb();

        asociado.idAso =
                fila.get("id_aso") == null
                        ? null
                        : ((Number) fila.get("id_aso")).intValue();

        asociado.codAsp =
                documento;

        asociado.activo =
                convertirBooleanSql(
                        fila.get("activo")
                );

        asociado.fechaRetiro =
                convertirFechaSql(
                        fila.get("fecha_retiro")
                );


        return asociado;
    }


    /* =========================================================
       APORTES EXISTENTES
       ========================================================= */
    private Set<String> cargarDocumentosAportes() {

        String sql =
                "SELECT DISTINCT LTRIM(RTRIM(cod_asp)) " +
                        "FROM FEMPROBIEN.dbo.tblAportes " +
                        "WHERE cod_asp IS NOT NULL " +
                        "AND LTRIM(RTRIM(cod_asp)) <> ''";


        List<String> documentos =
                sqlServerJdbcTemplate.queryForList(
                        sql,
                        String.class
                );


        Set<String> resultado =
                new HashSet<>();


        for (
                String documento
                : documentos
        ) {

            String normalizado =
                    normalizarDocumento(
                            documento
                    );


            if (!normalizado.isEmpty()) {

                resultado.add(
                        normalizado
                );
            }
        }


        return resultado;
    }


    private boolean existeAporte(
            String documento) {

        Integer cantidad =
                sqlServerJdbcTemplate.queryForObject(
                        "SELECT COUNT(*) " +
                                "FROM FEMPROBIEN.dbo.tblAportes " +
                                "WHERE LTRIM(RTRIM(cod_asp)) = ?",
                        new Object[]{
                                documento
                        },
                        Integer.class
                );


        return cantidad != null
                && cantidad > 0;
    }


    /* =========================================================
       ENCABEZADOS
       ========================================================= */
    private Map<String, Integer> obtenerEncabezados(
            Row row,
            DataFormatter formatter,
            FormulaEvaluator evaluator) {

        Map<String, Integer> columnas =
                new HashMap<>();


        for (
                int i = row.getFirstCellNum();
                i < row.getLastCellNum();
                i++
        ) {

            Cell cell =
                    row.getCell(
                            i,
                            Row.MissingCellPolicy.RETURN_BLANK_AS_NULL
                    );


            if (cell == null) {
                continue;
            }


            String encabezado =
                    leerTextoCelda(
                            cell,
                            formatter,
                            evaluator
                    );


            if (
                    encabezado == null
                            || encabezado.trim().isEmpty()
            ) {
                continue;
            }


            columnas.put(
                    normalizarEncabezado(
                            encabezado
                    ),
                    i
            );
        }


        return columnas;
    }


    private void validarEncabezados(
            Map<String, Integer> columnas) {

        List<String> faltantes =
                new ArrayList<>();


        for (
                String requerido
                : ENCABEZADOS_REQUERIDOS
        ) {

            if (
                    !columnas.containsKey(
                            normalizarEncabezado(
                                    requerido
                            )
                    )
            ) {

                faltantes.add(
                        requerido
                );
            }
        }


        if (!faltantes.isEmpty()) {

            throw new IllegalArgumentException(
                    "El archivo no tiene todas las columnas requeridas. Faltan: "
                            + String.join(", ", faltantes)
            );
        }
    }


    /* =========================================================
       ARCHIVO
       ========================================================= */
    private void validarArchivoBasico(
            MultipartFile archivo) {

        if (
                archivo == null
                        || archivo.isEmpty()
        ) {

            throw new IllegalArgumentException(
                    "Debes seleccionar un archivo de Excel."
            );
        }


        String nombre =
                archivo.getOriginalFilename();


        if (
                nombre == null
                        || nombre.trim().isEmpty()
        ) {

            throw new IllegalArgumentException(
                    "El archivo no tiene un nombre válido."
            );
        }


        String lower =
                nombre
                        .trim()
                        .toLowerCase();


        if (
                !lower.endsWith(".xlsx")
                        && !lower.endsWith(".xls")
        ) {

            throw new IllegalArgumentException(
                    "Solo se permiten archivos .xlsx o .xls."
            );
        }
    }


    /* =========================================================
       LEER DOCUMENTO
       ========================================================= */
    private String leerDocumento(
            Row row,
            Integer columna,
            DataFormatter formatter,
            FormulaEvaluator evaluator) {

        if (
                row == null
                        || columna == null
        ) {
            return "";
        }


        Cell cell =
                row.getCell(
                        columna,
                        Row.MissingCellPolicy.RETURN_BLANK_AS_NULL
                );


        if (cell == null) {
            return "";
        }


        try {

            CellValue value =
                    evaluator.evaluate(
                            cell
                    );


            if (
                    value != null
                            && value.getCellType()
                            == CellType.NUMERIC
            ) {

                BigDecimal numero =
                        BigDecimal.valueOf(
                                value.getNumberValue()
                        );


                return numero
                        .setScale(
                                0,
                                RoundingMode.DOWN
                        )
                        .toPlainString();
            }

        } catch (Exception e) {
            // Continuar con DataFormatter.
        }


        return normalizarDocumento(
                leerTextoCelda(
                        cell,
                        formatter,
                        evaluator
                )
        );
    }


    /* =========================================================
       TEXTO
       ========================================================= */
    private String leerTexto(
            Row row,
            Integer columna,
            DataFormatter formatter,
            FormulaEvaluator evaluator) {

        if (
                row == null
                        || columna == null
        ) {
            return "";
        }


        Cell cell =
                row.getCell(
                        columna,
                        Row.MissingCellPolicy.RETURN_BLANK_AS_NULL
                );


        return leerTextoCelda(
                cell,
                formatter,
                evaluator
        );
    }


    private String leerTextoCelda(
            Cell cell,
            DataFormatter formatter,
            FormulaEvaluator evaluator) {

        if (cell == null) {
            return "";
        }


        try {

            CellValue value =
                    evaluator.evaluate(
                            cell
                    );


            if (
                    value != null
                            && value.getCellType()
                            == CellType.ERROR
            ) {

                String error =
                        FormulaError.forInt(
                                value.getErrorValue()
                        ).getString();

                throw new IllegalArgumentException(
                        "La celda "
                                + posicionCelda(cell)
                                + " contiene error de Excel: "
                                + error
                );
            }


            return formatter
                    .formatCellValue(
                            cell,
                            evaluator
                    )
                    .trim();

        } catch (IllegalArgumentException e) {
            throw e;

        } catch (Exception e) {

            return formatter
                    .formatCellValue(
                            cell
                    )
                    .trim();
        }
    }


    /* =========================================================
       FECHAS
       ========================================================= */
    private Date leerFechaSql(
            Row row,
            Integer columna,
            DataFormatter formatter,
            FormulaEvaluator evaluator,
            boolean obligatorio) {

        if (
                row == null
                        || columna == null
        ) {

            if (obligatorio) {
                throw new IllegalArgumentException(
                        "No se encontró la columna de fecha."
                );
            }

            return null;
        }


        Cell cell =
                row.getCell(
                        columna,
                        Row.MissingCellPolicy.RETURN_BLANK_AS_NULL
                );


        Date fecha =
                leerFechaCelda(
                        cell,
                        formatter,
                        evaluator,
                        obligatorio
                );


        return fecha;
    }


    private Date leerFechaCelda(
            Cell cell,
            DataFormatter formatter,
            FormulaEvaluator evaluator,
            boolean obligatorio) {

        if (cell == null) {

            if (obligatorio) {
                throw new IllegalArgumentException(
                        "La fecha es obligatoria."
                );
            }

            return null;
        }


        try {

            CellValue value =
                    evaluator.evaluate(
                            cell
                    );


            if (value == null) {
                return null;
            }


            if (
                    value.getCellType()
                            == CellType.BLANK
            ) {
                return null;
            }


            if (
                    value.getCellType()
                            == CellType.ERROR
            ) {

                String error =
                        FormulaError.forInt(
                                value.getErrorValue()
                        ).getString();

                throw new IllegalArgumentException(
                        "La celda "
                                + posicionCelda(cell)
                                + " contiene error de Excel: "
                                + error
                );
            }


            if (
                    value.getCellType()
                            == CellType.NUMERIC
                            && DateUtil.isValidExcelDate(
                            value.getNumberValue()
                    )
            ) {

                java.util.Date javaDate =
                        DateUtil.getJavaDate(
                                value.getNumberValue()
                        );


                return new Date(
                        javaDate.getTime()
                );
            }


            String texto =
                    formatter
                            .formatCellValue(
                                    cell,
                                    evaluator
                            )
                            .trim();


            if (texto.isEmpty()) {
                return null;
            }


            return parseFechaTexto(
                    texto,
                    cell
            );


        } catch (IllegalArgumentException e) {
            throw e;

        } catch (Exception e) {

            String texto =
                    formatter
                            .formatCellValue(
                                    cell
                            )
                            .trim();


            if (texto.isEmpty()) {
                return null;
            }


            return parseFechaTexto(
                    texto,
                    cell
            );
        }
    }


    private Date parseFechaTexto(
            String texto,
            Cell cell) {

        String[] formatos =
                new String[]{
                        "yyyy-MM-dd",
                        "dd/MM/yyyy",
                        "d/M/yyyy",
                        "MM/dd/yyyy",
                        "M/d/yyyy",
                        "dd-MM-yyyy",
                        "d-M-yyyy"
                };


        for (
                String formato
                : formatos
        ) {

            SimpleDateFormat sdf =
                    new SimpleDateFormat(
                            formato
                    );

            sdf.setLenient(
                    false
            );


            try {

                java.util.Date fecha =
                        sdf.parse(
                                texto
                        );


                return new Date(
                        fecha.getTime()
                );

            } catch (ParseException e) {
                // probar siguiente formato
            }
        }


        throw new IllegalArgumentException(
                "La celda "
                        + posicionCelda(cell)
                        + " no contiene una fecha válida: '"
                        + texto
                        + "'."
        );
    }


    /* =========================================================
       NORMALIZACIONES
       ========================================================= */
    private String normalizarDocumento(
            String documento) {

        if (documento == null) {
            return "";
        }


        String resultado =
                documento
                        .replace("\u00A0", "")
                        .replace(" ", "")
                        .trim();


        if (
                resultado.matches(
                        "\\d+\\.0+"
                )
        ) {

            resultado =
                    resultado.substring(
                            0,
                            resultado.indexOf(".")
                    );
        }


        return resultado;
    }


    private String normalizarEncabezado(
            String texto) {

        if (texto == null) {
            return "";
        }


        String normalizado =
                Normalizer.normalize(
                        texto,
                        Normalizer.Form.NFD
                );


        normalizado =
                normalizado.replaceAll(
                        "\\p{M}",
                        ""
                );


        return normalizado
                .replace("\u00A0", " ")
                .trim()
                .replaceAll("\\s+", " ")
                .toUpperCase(
                        Locale.ROOT
                );
    }


    private String normalizarClave(
            String texto) {

        String valor =
                normalizarEncabezado(
                        texto
                );


        /*
         * Unificar formas usadas entre encabezados y SQL:
         * - Años / Anios
         * - 31/12/2025 / 31Diciembre2025
         */
        valor =
                valor.replace(
                        "ANIOS",
                        "ANOS"
                );

        valor =
                valor.replace(
                        "31/12/2025",
                        "31 DICIEMBRE 2025"
                );


        return valor.replaceAll(
                "[^A-Z0-9]",
                ""
        );
    }


    /* =========================================================
       UTILIDADES SQL
       ========================================================= */
    private boolean convertirBooleanSql(
            Object valor) {

        if (valor == null) {
            return false;
        }


        if (valor instanceof Boolean) {
            return (Boolean) valor;
        }


        if (valor instanceof Number) {
            return ((Number) valor).intValue() != 0;
        }


        String texto =
                valor
                        .toString()
                        .trim();


        return "1".equals(texto)
                || "true".equalsIgnoreCase(texto)
                || "Y".equalsIgnoreCase(texto)
                || "SI".equalsIgnoreCase(texto);
    }


    private Date convertirFechaSql(
            Object valor) {

        if (valor == null) {
            return null;
        }


        if (valor instanceof Date) {
            return (Date) valor;
        }


        if (valor instanceof java.util.Date) {

            return new Date(
                    ((java.util.Date) valor).getTime()
            );
        }


        return null;
    }


    private String posicionCelda(
            Cell cell) {

        if (cell == null) {
            return "desconocida";
        }


        return "fila "
                + (cell.getRowIndex() + 1)
                + ", columna "
                + (cell.getColumnIndex() + 1);
    }


    private void marcarError(
            FilaImportacion fila,
            String mensaje) {

        fila.valido =
                false;

        fila.operacion =
                ERROR;

        fila.mensaje =
                mensaje;
    }


    private String mensajeOperacionRealizada(
            String operacion) {

        if (
                NUEVO_ASOCIADO_APORTE.equals(
                        operacion
                )
        ) {

            return "Asociado creado y aportes registrados correctamente.";
        }


        if (
                NUEVO_APORTE.equals(
                        operacion
                )
        ) {

            return "Registro de aportes creado correctamente.";
        }


        return "Aportes actualizados correctamente.";
    }


    /* =========================================================
       CLASES INTERNAS
       ========================================================= */
    private static class ColumnaDb {
        private String nombre;
        private String tipoDato;
    }


    private static class AsociadoDb {
        private Integer idAso;
        private String codAsp;
        private boolean activo;
        private Date fechaRetiro;
    }


    private static class FilaImportacion {
        private int numeroFila;
        private String documento = "";
        private String nombre = "";
        private Date fechaIngreso;
        private Date fechaRetiro;
        private String operacion = ERROR;
        private boolean valido = false;
        private String mensaje = "";
        private Map<String, Object> valoresAportes =
                new LinkedHashMap<>();
    }


    private static class MapeoColumnas {
        private Map<Integer, ColumnaDb> porIndice =
                new LinkedHashMap<>();

        private List<String> nombresMapeados =
                new ArrayList<>();

        private List<String> nombresIgnorados =
                new ArrayList<>();
    }


    private static class AnalisisArchivo {
        private int totalFilas = 0;
        private int nuevosAsociados = 0;
        private int nuevosAportes = 0;
        private int aportesActualizar = 0;
        private int errores = 0;

        private List<FilaImportacion> filas =
                new ArrayList<>();

        private Map<String, ColumnaDb> columnasAportesDb =
                new LinkedHashMap<>();

        private List<String> columnasMapeadas =
                new ArrayList<>();

        private List<String> columnasIgnoradas =
                new ArrayList<>();
    }
}
