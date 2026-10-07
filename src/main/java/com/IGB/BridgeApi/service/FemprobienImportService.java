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

        this.transactionTemplate.setIsolationLevel(
                TransactionDefinition.ISOLATION_SERIALIZABLE
        );
    }


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




    public Map<String, Object> sincronizarDatosFaltantesAsociados(
            String usuario) {

        List<String> basesNovaweb =
                obtenerBasesNovaweb();


        String sql =
                "SELECT "
                        + "LTRIM(RTRIM(cod_asp)) AS cod_asp "
                        + "FROM FEMPROBIEN.dbo.tblAsociado "
                        + "WHERE cod_asp IS NOT NULL "
                        + "AND LTRIM(RTRIM(cod_asp)) <> '' "
                        + "AND ("
                        + "fec_nac IS NULL "
                        + "OR fec_ing IS NULL "
                        + "OR dir_res IS NULL "
                        + "OR LTRIM(RTRIM(dir_res)) = '' "
                        + "OR nom_bar IS NULL "
                        + "OR LTRIM(RTRIM(nom_bar)) = '' "
                        + "OR tel IS NULL "
                        + "OR LTRIM(RTRIM(tel)) = '' "
                        + "OR cel IS NULL "
                        + "OR LTRIM(RTRIM(cel)) = '' "
                        + "OR email_per IS NULL "
                        + "OR LTRIM(RTRIM(email_per)) = '' "
                        + "OR sal_bas IS NULL "
                        + "OR cargo IS NULL "
                        + "OR LTRIM(RTRIM(cargo)) = '' "
                        + "OR banco IS NULL "
                        + "OR LTRIM(RTRIM(banco)) = '' "
                        + "OR n_cuenta IS NULL "
                        + "OR LTRIM(RTRIM(n_cuenta)) = ''"
                        + ")";


        List<String> documentos =
                sqlServerJdbcTemplate.queryForList(
                        sql,
                        String.class
                );


        int encontrados =
                0;

        int actualizados =
                0;

        int noEncontrados =
                0;

        int sinDatosParaCompletar =
                0;

        int errores =
                0;


        List<Map<String, Object>> detalle =
                new ArrayList<>();


        for (
                String documento
                : documentos
        ) {

            Map<String, Object> item =
                    new LinkedHashMap<>();


            item.put(
                    "documento",
                    documento
            );


            try {

                AsociadoDb asociado =
                        obtenerAsociado(
                                documento
                        );


                DatosEmpleadoNovaweb datos =
                        buscarDatosEmpleadoNovaweb(
                                documento,
                                basesNovaweb
                        );


                if (datos == null) {

                    noEncontrados++;


                    item.put(
                            "resultado",
                            "NO_ENCONTRADO"
                    );

                    item.put(
                            "mensaje",
                            "No se encontró el documento en las bases NOVAWEB."
                    );


                    detalle.add(
                            item
                    );

                    continue;
                }


                encontrados++;


                if (
                        asociado == null
                                || !hayDatoNovawebParaCompletar(
                                asociado,
                                datos
                        )
                ) {

                    sinDatosParaCompletar++;


                    item.put(
                            "resultado",
                            "SIN_CAMBIOS"
                    );

                    item.put(
                            "baseNovaweb",
                            datos.baseDatos
                    );

                    item.put(
                            "mensaje",
                            "NOVAWEB no contiene información adicional para completar los campos faltantes."
                    );


                    detalle.add(
                            item
                    );

                    continue;
                }


                int filas =
                        completarDatosFaltantesAsociado(
                                documento,
                                datos
                        );


                if (filas > 0) {

                    actualizados++;
                }


                item.put(
                        "resultado",
                        "OK"
                );

                item.put(
                        "baseNovaweb",
                        datos.baseDatos
                );

                item.put(
                        "fechaNacimiento",
                        datos.fechaNacimiento
                );

                item.put(
                        "celular",
                        datos.celular
                );

                item.put(
                        "correo",
                        datos.correo
                );

                item.put(
                        "cargo",
                        datos.cargo
                );

                item.put(
                        "banco",
                        datos.banco
                );

                item.put(
                        "actualizado",
                        filas > 0
                );

                item.put(
                        "mensaje",
                        filas > 0
                                ? "Se completaron los datos faltantes."
                                : "No fue necesario realizar cambios."
                );


            } catch (Exception e) {

                errores++;


                item.put(
                        "resultado",
                        "ERROR"
                );

                item.put(
                        "mensaje",
                        e.getMessage()
                );
            }


            detalle.add(
                    item
            );
        }


        Map<String, Object> response =
                new LinkedHashMap<>();


        response.put(
                "status",
                200
        );

        response.put(
                "usuario",
                usuario
        );

        response.put(
                "totalRevisados",
                documentos.size()
        );

        response.put(
                "encontradosNovaweb",
                encontrados
        );

        response.put(
                "actualizados",
                actualizados
        );

        response.put(
                "noEncontrados",
                noEncontrados
        );

        response.put(
                "sinDatosParaCompletar",
                sinDatosParaCompletar
        );

        response.put(
                "errores",
                errores
        );

        response.put(
                "detalle",
                detalle
        );


        return response;
    }


    private Map<String, Object> procesarDentroTransaccion(
            MultipartFile archivo,
            AnalisisArchivo analisis,
            String usuario) {

        int asociadosCreados = 0;
        int aportesCreados = 0;
        int aportesActualizados = 0;

        /*
         * Se conserva este contador porque ya hacía parte
         * de la respuesta del importador.
         */
        int fechasNacimientoActualizadas = 0;

        /*
         * Nuevo contador general para saber cuántos asociados
         * existentes fueron enriquecidos con datos de NOVAWEB.
         */
        int asociadosConDatosCompletados = 0;


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

                boolean fechaNacimientoActualizada =
                        false;

                boolean datosAsociadoCompletados =
                        false;


                if (asociado == null) {

                    /*
                     * ASOCIADO NUEVO.
                     *
                     * insertarAsociadoNuevo conserva lo que ya se hacía,
                     * pero ahora también guarda los datos disponibles
                     * encontrados en NOVAWEB.
                     */
                    asociado =
                            insertarAsociadoNuevo(
                                    fila
                            );

                    asociadosCreados++;
                    asociadoCreado = true;

                } else if (
                        fila.datosNovaweb != null
                                && tieneDatosFaltantes(
                                asociado
                        )
                                && hayDatoNovawebParaCompletar(
                                asociado,
                                fila.datosNovaweb
                        )
                ) {

                    /*
                     * Guardamos esta condición antes del UPDATE
                     * para conservar el contador específico de fec_nac.
                     */
                    boolean teniaFechaNacimientoVacia =
                            asociado.fechaNacimiento == null
                                    && fila.datosNovaweb.fechaNacimiento != null;


                    int actualizados =
                            completarDatosFaltantesAsociado(
                                    fila.documento,
                                    fila.datosNovaweb
                            );


                    if (actualizados > 0) {

                        datosAsociadoCompletados =
                                true;

                        asociadosConDatosCompletados++;


                        if (teniaFechaNacimientoVacia) {

                            fechaNacimientoActualizada =
                                    true;

                            fechasNacimientoActualizadas++;

                            asociado.fechaNacimiento =
                                    fila.datosNovaweb.fechaNacimiento;
                        }
                    }
                }


                /*
                 * A PARTIR DE AQUÍ SE CONSERVA LA LÓGICA
                 * EXISTENTE DE tblAportes.
                 */
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
                        "fechaNacimiento",
                        fila.fechaNacimiento == null
                                ? ""
                                : fila.fechaNacimiento.toString()
                );

                /*
                 * Se conserva para no afectar respuestas
                 * que ya consuman este campo.
                 */
                item.put(
                        "fechaNacimientoActualizada",
                        fechaNacimientoActualizada
                );

                item.put(
                        "datosAsociadoCompletados",
                        datosAsociadoCompletados
                );


                if (fila.datosNovaweb != null) {

                    item.put(
                            "baseNovaweb",
                            fila.datosNovaweb.baseDatos
                    );

                    item.put(
                            "celularNovaweb",
                            fila.datosNovaweb.celular
                    );

                    item.put(
                            "correoNovaweb",
                            fila.datosNovaweb.correo
                    );

                    item.put(
                            "cargoNovaweb",
                            fila.datosNovaweb.cargo
                    );

                    item.put(
                            "bancoNovaweb",
                            fila.datosNovaweb.banco
                    );
                }


                item.put(
                        "operacion",
                        accionRealizada
                );

                item.put(
                        "resultado",
                        "OK"
                );


                String mensaje =
                        mensajeOperacionRealizada(
                                accionRealizada
                        );


                if (datosAsociadoCompletados) {

                    mensaje =
                            mensaje
                                    + " Se completaron datos faltantes "
                                    + "del asociado desde NOVAWEB.";
                }


                item.put(
                        "mensaje",
                        mensaje
                );

                detalleProceso.add(
                        item
                );


            } catch (Exception e) {

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

        /*
         * Campo existente.
         */
        response.put(
                "fechasNacimientoActualizadas",
                fechasNacimientoActualizadas
        );

        /*
         * Nuevo campo.
         */
        response.put(
                "asociadosConDatosCompletados",
                asociadosConDatosCompletados
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


        /*
         * Igual que en la implementación que ya tenías:
         * obtenemos las bases NOVAWEB una sola vez por archivo.
         */
        List<String> basesNovaweb =
                obtenerBasesNovaweb();


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


                        AsociadoDb asociadoExistente =
                                asociadosExistentes.get(
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

                            /*
                             * Se conserva exactamente la lectura dinámica
                             * de aportes desde el Excel.
                             */
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

                                /*
                                 * ASOCIADO NUEVO.
                                 *
                                 * Antes solamente buscábamos fec_nac.
                                 * Ahora recuperamos también los demás
                                 * datos disponibles en NOVAWEB.
                                 */
                                fila.datosNovaweb =
                                        buscarDatosEmpleadoNovaweb(
                                                documento,
                                                basesNovaweb
                                        );


                                if (fila.datosNovaweb == null) {

                                    marcarError(
                                            fila,
                                            "El asociado es nuevo y no fue posible encontrar "
                                                    + "el documento "
                                                    + documento
                                                    + " en las bases NOVAWEB."
                                    );

                                } else if (
                                        fila.datosNovaweb.fechaNacimiento == null
                                ) {

                                    /*
                                     * Conservamos la validación que ya tenías:
                                     * un asociado nuevo no se crea sin fec_nac.
                                     */
                                    marcarError(
                                            fila,
                                            "El asociado es nuevo y no fue posible encontrar "
                                                    + "la fecha de nacimiento del documento "
                                                    + documento
                                                    + " en las bases NOVAWEB."
                                    );

                                } else {

                                    fila.fechaNacimiento =
                                            fila.datosNovaweb.fechaNacimiento;


                                    /*
                                     * La fecha del Excel tiene prioridad.
                                     * NOVAWEB solo se usa si el Excel viene vacío.
                                     */
                                    if (
                                            fila.fechaIngreso == null
                                                    && fila.datosNovaweb.fechaIngreso != null
                                    ) {

                                        fila.fechaIngreso =
                                                fila.datosNovaweb.fechaIngreso;
                                    }


                                    fila.operacion =
                                            NUEVO_ASOCIADO_APORTE;

                                    fila.mensaje =
                                            "Se crearía el asociado en tblAsociado "
                                                    + "con la información encontrada en NOVAWEB "
                                                    + "y su registro en tblAportes.";

                                    analisis.nuevosAsociados++;
                                }

                            } else {

                                /*
                                 * ASOCIADO EXISTENTE.
                                 *
                                 * Solo consultamos NOVAWEB si encontramos
                                 * alguno de los campos que sabemos completar
                                 * como NULL o vacío.
                                 */
                                if (
                                        asociadoExistente != null
                                                && tieneDatosFaltantes(
                                                asociadoExistente
                                        )
                                ) {

                                    fila.datosNovaweb =
                                            buscarDatosEmpleadoNovaweb(
                                                    documento,
                                                    basesNovaweb
                                            );
                                }


                                /*
                                 * Se conserva el comportamiento anterior
                                 * respecto a fec_nac.
                                 */
                                if (
                                        asociadoExistente != null
                                                && asociadoExistente.fechaNacimiento != null
                                ) {

                                    fila.fechaNacimiento =
                                            asociadoExistente.fechaNacimiento;

                                } else if (
                                        fila.datosNovaweb != null
                                ) {

                                    fila.fechaNacimiento =
                                            fila.datosNovaweb.fechaNacimiento;
                                }


                                if (!existeAporte) {

                                    fila.operacion =
                                            NUEVO_APORTE;

                                    if (
                                            fila.datosNovaweb != null
                                                    && hayDatoNovawebParaCompletar(
                                                    asociadoExistente,
                                                    fila.datosNovaweb
                                            )
                                    ) {

                                        fila.mensaje =
                                                "El asociado ya existe y se crearía su registro "
                                                        + "en tblAportes. Además se completarían "
                                                        + "los datos faltantes encontrados en NOVAWEB.";

                                    } else {

                                        fila.mensaje =
                                                "El asociado ya existe y se crearía su registro en tblAportes.";
                                    }

                                    analisis.nuevosAportes++;

                                } else {

                                    fila.operacion =
                                            ACTUALIZAR_APORTE;

                                    if (
                                            fila.datosNovaweb != null
                                                    && hayDatoNovawebParaCompletar(
                                                    asociadoExistente,
                                                    fila.datosNovaweb
                                            )
                                    ) {

                                        fila.mensaje =
                                                "El asociado y sus aportes ya existen; se actualizaría tblAportes "
                                                        + "y se completarían los datos faltantes encontrados en NOVAWEB.";

                                    } else {

                                        fila.mensaje =
                                                "El asociado y sus aportes ya existen; se actualizaría tblAportes.";
                                    }

                                    analisis.aportesActualizar++;
                                }
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
                    "fechaNacimiento",
                    fila.fechaNacimiento == null
                            ? ""
                            : fila.fechaNacimiento.toString()
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


            /*
             * Campos adicionales de previsualización.
             * No modifican la estructura anterior de respuesta;
             * solamente agregan información cuando NOVAWEB
             * fue consultado.
             */
            if (fila.datosNovaweb != null) {

                item.put(
                        "baseNovaweb",
                        fila.datosNovaweb.baseDatos
                );

                item.put(
                        "celularNovaweb",
                        fila.datosNovaweb.celular
                );

                item.put(
                        "correoNovaweb",
                        fila.datosNovaweb.correo
                );

                item.put(
                        "cargoNovaweb",
                        fila.datosNovaweb.cargo
                );

                item.put(
                        "bancoNovaweb",
                        fila.datosNovaweb.banco
                );
            }


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


    private List<String> obtenerBasesNovaweb() {

        return sqlServerJdbcTemplate.queryForList(
                "SELECT name "
                        + "FROM sys.databases "
                        + "WHERE name LIKE '%[_]NOVAWEB' "
                        + "AND state_desc = 'ONLINE' "
                        + "ORDER BY name",
                String.class
        );
    }



    private DatosEmpleadoNovaweb buscarDatosEmpleadoNovaweb(
            String documento,
            List<String> basesNovaweb) {

        String documentoNormalizado =
                normalizarDocumento(
                        documento
                );


        if (
                documentoNormalizado == null
                        || documentoNormalizado.trim().isEmpty()
        ) {

            return null;
        }


        List<DatosEmpleadoNovaweb> encontrados =
                new ArrayList<>();


        Set<String> fechasNacimiento =
                new HashSet<>();


        for (
                String baseDatos
                : basesNovaweb
        ) {

            if (
                    baseDatos == null
                            || baseDatos.trim().isEmpty()
            ) {
                continue;
            }


            /*
             * El nombre de una base no puede enviarse como
             * parámetro SQL, por eso validamos caracteres seguros
             * antes de concatenarlo.
             */
            if (
                    !baseDatos.matches(
                            "^[A-Za-z0-9_]+$"
                    )
            ) {
                continue;
            }


            String sql =
                    "SELECT TOP 1 "
                            + "CAST(E.fec_nac AS DATE) AS fec_nac, "
                            + "CAST(E.fec_ing AS DATE) AS fec_ing, "
                            + "CAST(E.fec_egr AS DATE) AS fec_egr, "
                            + "LTRIM(RTRIM(E.dir_res)) AS dir_res, "
                            + "LTRIM(RTRIM(E.barrio)) AS barrio, "
                            + "LTRIM(RTRIM(E.tel_res)) AS tel_res, "
                            + "LTRIM(RTRIM(E.tel_cel)) AS tel_cel, "
                            + "LTRIM(RTRIM(E.e_mail)) AS e_mail, "
                            + "E.sal_bas AS sal_bas, "
                            + "LTRIM(RTRIM(CAST(E.cod_car AS VARCHAR(50)))) AS cod_car, "
                            + "LTRIM(RTRIM(CAST(E.cod_ban AS VARCHAR(50)))) AS cod_ban, "
                            + "LTRIM(RTRIM(CAST(E.cta_ban AS VARCHAR(100)))) AS cta_ban "
                            + "FROM ["
                            + baseDatos
                            + "].dbo.v_EmpleadosNOM E "
                            + "WHERE LTRIM(RTRIM(CAST(E.cod_emp AS VARCHAR(50)))) = ? "
                            + "ORDER BY "
                            + "CASE WHEN E.fec_egr IS NULL THEN 0 ELSE 1 END, "
                            + "E.fec_ing DESC";


            try {

                List<DatosEmpleadoNovaweb> datosBase =
                        sqlServerJdbcTemplate.query(
                                sql,
                                new Object[]{
                                        documentoNormalizado
                                },
                                (rs, rowNum) -> {

                                    DatosEmpleadoNovaweb datos =
                                            new DatosEmpleadoNovaweb();


                                    datos.baseDatos =
                                            baseDatos;

                                    datos.fechaNacimiento =
                                            rs.getDate(
                                                    "fec_nac"
                                            );

                                    datos.fechaIngreso =
                                            rs.getDate(
                                                    "fec_ing"
                                            );

                                    datos.fechaEgreso =
                                            rs.getDate(
                                                    "fec_egr"
                                            );

                                    datos.direccion =
                                            limpiarTexto(
                                                    rs.getString(
                                                            "dir_res"
                                                    )
                                            );

                                    datos.barrio =
                                            limpiarTexto(
                                                    rs.getString(
                                                            "barrio"
                                                    )
                                            );

                                    datos.telefono =
                                            limpiarTexto(
                                                    rs.getString(
                                                            "tel_res"
                                                    )
                                            );

                                    datos.celular =
                                            limpiarTexto(
                                                    rs.getString(
                                                            "tel_cel"
                                                    )
                                            );

                                    datos.correo =
                                            limpiarTexto(
                                                    rs.getString(
                                                            "e_mail"
                                                    )
                                            );

                                    datos.salario =
                                            rs.getBigDecimal(
                                                    "sal_bas"
                                            );

                                    datos.codigoCargo =
                                            limpiarTexto(
                                                    rs.getString(
                                                            "cod_car"
                                                    )
                                            );

                                    datos.codigoBanco =
                                            limpiarTexto(
                                                    rs.getString(
                                                            "cod_ban"
                                                    )
                                            );

                                    datos.cuentaBancaria =
                                            limpiarTexto(
                                                    rs.getString(
                                                            "cta_ban"
                                                    )
                                            );


                                    return datos;
                                }
                        );


                for (
                        DatosEmpleadoNovaweb datos
                        : datosBase
                ) {

                    /*
                     * La vista devuelve el código del cargo,
                     * pero la descripción está en rhh_cargos.
                     */
                    datos.cargo =
                            buscarNombreCargoNovaweb(
                                    baseDatos,
                                    datos.codigoCargo
                            );


                    /*
                     * La vista devuelve el código del banco,
                     * pero la descripción está en gen_bancos.
                     */
                    datos.banco =
                            buscarNombreBancoNovaweb(
                                    baseDatos,
                                    datos.codigoBanco
                            );


                    encontrados.add(
                            datos
                    );


                    if (
                            datos.fechaNacimiento != null
                    ) {

                        fechasNacimiento.add(
                                datos.fechaNacimiento.toString()
                        );
                    }
                }


            } catch (Exception e) {

                /*
                 * Conservamos el enfoque que ya tenías:
                 * si una NOVAWEB no se puede consultar,
                 * continuamos buscando en las demás.
                 */
                System.err.println(
                        "[FEMPROBIEN] No fue posible consultar "
                                + baseDatos
                                + " para el documento "
                                + documentoNormalizado
                                + ". Error: "
                                + e.getMessage()
                );
            }
        }


        if (
                encontrados.isEmpty()
        ) {

            return null;
        }


        /*
         * Se conserva la protección que ya existía para fec_nac.
         * Si el mismo documento aparece con fechas diferentes,
         * no elegimos una arbitrariamente.
         */
        if (
                fechasNacimiento.size() > 1
        ) {

            throw new IllegalArgumentException(
                    "Se encontraron diferentes fechas de nacimiento "
                            + "para el documento "
                            + documentoNormalizado
                            + " en las bases NOVAWEB: "
                            + fechasNacimiento
            );
        }


        DatosEmpleadoNovaweb resultado =
                null;


        /*
         * Si el documento aparece en varias empresas,
         * priorizamos:
         *
         * 1. Registro laboral activo.
         * 2. Ingreso más reciente.
         */
        for (
                DatosEmpleadoNovaweb candidato
                : encontrados
        ) {

            if (
                    resultado == null
                            || esMejorRegistroNovaweb(
                            candidato,
                            resultado
                    )
            ) {

                resultado =
                        candidato;
            }
        }


        /*
         * Si el registro elegido tiene algún dato personal
         * vacío, podemos aprovecharlo desde otra NOVAWEB
         * donde aparezca el mismo documento.
         *
         * Los datos laborales (cargo, salario, banco)
         * permanecen asociados al registro laboral elegido.
         */
        for (
                DatosEmpleadoNovaweb otro
                : encontrados
        ) {

            completarDatosPersonalesNovaweb(
                    resultado,
                    otro
            );
        }


        /*
         * Si todas las bases coinciden en una sola fec_nac,
         * garantizamos que quede en el resultado seleccionado.
         */
        if (
                resultado.fechaNacimiento == null
                        && fechasNacimiento.size() == 1
        ) {

            resultado.fechaNacimiento =
                    Date.valueOf(
                            fechasNacimiento
                                    .iterator()
                                    .next()
                    );
        }


        return resultado;
    }


    private String buscarNombreCargoNovaweb(
            String baseDatos,
            String codigoCargo) {

        if (
                esVacio(baseDatos)
                        || esVacio(codigoCargo)
        ) {

            return "";
        }


        try {

            List<String> resultados =
                    sqlServerJdbcTemplate.queryForList(
                            "SELECT TOP 1 "
                                    + "LTRIM(RTRIM(nom_car)) "
                                    + "FROM ["
                                    + baseDatos
                                    + "].dbo.rhh_cargos "
                                    + "WHERE LTRIM(RTRIM(CAST(cod_car AS VARCHAR(50)))) = ?",
                            new Object[]{
                                    codigoCargo
                            },
                            String.class
                    );


            return resultados.isEmpty()
                    ? ""
                    : limpiarTexto(
                    resultados.get(0)
            );


        } catch (Exception e) {

            System.err.println(
                    "[FEMPROBIEN] No fue posible consultar el cargo "
                            + codigoCargo
                            + " en "
                            + baseDatos
                            + ". Error: "
                            + e.getMessage()
            );

            return "";
        }
    }


    private String buscarNombreBancoNovaweb(
            String baseDatos,
            String codigoBanco) {

        if (
                esVacio(baseDatos)
                        || esVacio(codigoBanco)
        ) {

            return "";
        }


        try {

            List<String> resultados =
                    sqlServerJdbcTemplate.queryForList(
                            "SELECT TOP 1 "
                                    + "LTRIM(RTRIM(nom_ban)) "
                                    + "FROM ["
                                    + baseDatos
                                    + "].dbo.gen_bancos "
                                    + "WHERE LTRIM(RTRIM(CAST(cod_ban AS VARCHAR(50)))) = ?",
                            new Object[]{
                                    codigoBanco
                            },
                            String.class
                    );


            return resultados.isEmpty()
                    ? ""
                    : limpiarTexto(
                    resultados.get(0)
            );


        } catch (Exception e) {

            System.err.println(
                    "[FEMPROBIEN] No fue posible consultar el banco "
                            + codigoBanco
                            + " en "
                            + baseDatos
                            + ". Error: "
                            + e.getMessage()
            );

            return "";
        }
    }


    private boolean esMejorRegistroNovaweb(
            DatosEmpleadoNovaweb candidato,
            DatosEmpleadoNovaweb actual) {

        boolean candidatoActivo =
                candidato.fechaEgreso == null;

        boolean actualActivo =
                actual.fechaEgreso == null;


        if (
                candidatoActivo
                        && !actualActivo
        ) {

            return true;
        }


        if (
                !candidatoActivo
                        && actualActivo
        ) {

            return false;
        }


        if (
                candidato.fechaIngreso == null
        ) {

            return false;
        }


        if (
                actual.fechaIngreso == null
        ) {

            return true;
        }


        return candidato
                .fechaIngreso
                .after(
                        actual.fechaIngreso
                );
    }


    private void completarDatosPersonalesNovaweb(
            DatosEmpleadoNovaweb destino,
            DatosEmpleadoNovaweb origen) {

        if (
                destino == null
                        || origen == null
        ) {

            return;
        }


        if (
                destino.fechaNacimiento == null
                        && origen.fechaNacimiento != null
        ) {

            destino.fechaNacimiento =
                    origen.fechaNacimiento;
        }


        if (
                esVacio(destino.direccion)
                        && !esVacio(origen.direccion)
        ) {

            destino.direccion =
                    origen.direccion;
        }


        if (
                esVacio(destino.barrio)
                        && !esVacio(origen.barrio)
        ) {

            destino.barrio =
                    origen.barrio;
        }


        if (
                esVacio(destino.telefono)
                        && !esVacio(origen.telefono)
        ) {

            destino.telefono =
                    origen.telefono;
        }


        if (
                esVacio(destino.celular)
                        && !esVacio(origen.celular)
        ) {

            destino.celular =
                    origen.celular;
        }


        if (
                esVacio(destino.correo)
                        && !esVacio(origen.correo)
        ) {

            destino.correo =
                    origen.correo;
        }
    }



    private int completarDatosFaltantesAsociado(
            String documento,
            DatosEmpleadoNovaweb datos) {

        if (
                documento == null
                        || documento.trim().isEmpty()
                        || datos == null
        ) {

            return 0;
        }


        String sql =
                "UPDATE FEMPROBIEN.dbo.tblAsociado SET "

                        + "fec_nac = CASE "
                        + "WHEN fec_nac IS NULL "
                        + "THEN ? ELSE fec_nac END, "

                        + "fec_ing = CASE "
                        + "WHEN fec_ing IS NULL "
                        + "THEN ? ELSE fec_ing END, "

                        + "dir_res = CASE "
                        + "WHEN dir_res IS NULL "
                        + "OR LTRIM(RTRIM(dir_res)) = '' "
                        + "THEN ? ELSE dir_res END, "

                        + "nom_bar = CASE "
                        + "WHEN nom_bar IS NULL "
                        + "OR LTRIM(RTRIM(nom_bar)) = '' "
                        + "THEN ? ELSE nom_bar END, "

                        + "tel = CASE "
                        + "WHEN tel IS NULL "
                        + "OR LTRIM(RTRIM(tel)) = '' "
                        + "THEN ? ELSE tel END, "

                        + "cel = CASE "
                        + "WHEN cel IS NULL "
                        + "OR LTRIM(RTRIM(cel)) = '' "
                        + "THEN ? ELSE cel END, "

                        + "email_per = CASE "
                        + "WHEN email_per IS NULL "
                        + "OR LTRIM(RTRIM(email_per)) = '' "
                        + "THEN ? ELSE email_per END, "

                        + "sal_bas = CASE "
                        + "WHEN sal_bas IS NULL "
                        + "THEN ? ELSE sal_bas END, "

                        + "cargo = CASE "
                        + "WHEN cargo IS NULL "
                        + "OR LTRIM(RTRIM(cargo)) = '' "
                        + "THEN ? ELSE cargo END, "

                        + "banco = CASE "
                        + "WHEN banco IS NULL "
                        + "OR LTRIM(RTRIM(banco)) = '' "
                        + "THEN ? ELSE banco END, "

                        + "n_cuenta = CASE "
                        + "WHEN n_cuenta IS NULL "
                        + "OR LTRIM(RTRIM(n_cuenta)) = '' "
                        + "THEN ? ELSE n_cuenta END "

                        + "WHERE LTRIM(RTRIM(cod_asp)) = ?";


        return sqlServerJdbcTemplate.update(
                sql,

                datos.fechaNacimiento,

                datos.fechaIngreso,

                valorONull(
                        datos.direccion
                ),

                valorONull(
                        datos.barrio
                ),

                valorONull(
                        datos.telefono
                ),

                valorONull(
                        datos.celular
                ),

                valorONull(
                        datos.correo
                ),

                datos.salario,

                valorONull(
                        datos.cargo
                ),

                valorONull(
                        datos.banco
                ),

                valorONull(
                        datos.cuentaBancaria
                ),

                documento.trim()
        );
    }


    private boolean tieneDatosFaltantes(
            AsociadoDb asociado) {

        if (asociado == null) {
            return true;
        }


        return asociado.fechaNacimiento == null

                || asociado.fechaIngreso == null

                || esVacio(
                asociado.direccion
        )

                || esVacio(
                asociado.barrio
        )

                || esVacio(
                asociado.telefono
        )

                || esVacio(
                asociado.celular
        )

                || esVacio(
                asociado.correo
        )

                || asociado.salario == null

                || esVacio(
                asociado.cargo
        )

                || esVacio(
                asociado.banco
        )

                || esVacio(
                asociado.cuentaBancaria
        );
    }


    private boolean hayDatoNovawebParaCompletar(
            AsociadoDb asociado,
            DatosEmpleadoNovaweb datos) {

        if (
                asociado == null
                        || datos == null
        ) {
            return false;
        }


        return (
                asociado.fechaNacimiento == null
                        && datos.fechaNacimiento != null
        )
                || (
                asociado.fechaIngreso == null
                        && datos.fechaIngreso != null
        )
                || (
                esVacio(asociado.direccion)
                        && !esVacio(datos.direccion)
        )
                || (
                esVacio(asociado.barrio)
                        && !esVacio(datos.barrio)
        )
                || (
                esVacio(asociado.telefono)
                        && !esVacio(datos.telefono)
        )
                || (
                esVacio(asociado.celular)
                        && !esVacio(datos.celular)
        )
                || (
                esVacio(asociado.correo)
                        && !esVacio(datos.correo)
        )
                || (
                asociado.salario == null
                        && datos.salario != null
        )
                || (
                esVacio(asociado.cargo)
                        && !esVacio(datos.cargo)
        )
                || (
                esVacio(asociado.banco)
                        && !esVacio(datos.banco)
        )
                || (
                esVacio(asociado.cuentaBancaria)
                        && !esVacio(datos.cuentaBancaria)
        );
    }



    private AsociadoDb insertarAsociadoNuevo(
            FilaImportacion fila) {

        Integer siguienteId =
                siguienteIdAsociadoBloqueado();


        boolean activo =
                fila.fechaRetiro == null;


        DatosEmpleadoNovaweb datos =
                fila.datosNovaweb;


        if (
                datos == null
                        || datos.fechaNacimiento == null
        ) {

            throw new IllegalArgumentException(
                    "No se puede crear el asociado con documento "
                            + fila.documento
                            + " porque no se encontró su fecha de nacimiento."
            );
        }


        /*
         * Se conserva la fecha de ingreso del Excel cuando existe.
         * NOVAWEB solo sirve como respaldo si viene vacía.
         */
        Date fechaIngreso =
                fila.fechaIngreso != null
                        ? fila.fechaIngreso
                        : datos.fechaIngreso;


        String sql =
                "INSERT INTO FEMPROBIEN.dbo.tblAsociado ("
                        + "id_aso, "
                        + "cod_asp, "
                        + "nom_aso, "
                        + "fec_nac, "
                        + "fec_ing, "
                        + "fecha_retiro, "
                        + "dir_res, "
                        + "nom_bar, "
                        + "tel, "
                        + "cel, "
                        + "email_per, "
                        + "sal_bas, "
                        + "cargo, "
                        + "banco, "
                        + "n_cuenta, "
                        + "activo"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";


        sqlServerJdbcTemplate.update(
                sql,
                siguienteId,
                fila.documento,
                fila.nombre,
                datos.fechaNacimiento,
                fechaIngreso,
                fila.fechaRetiro,
                valorONull(
                        datos.direccion
                ),
                valorONull(
                        datos.barrio
                ),
                valorONull(
                        datos.telefono
                ),
                valorONull(
                        datos.celular
                ),
                valorONull(
                        datos.correo
                ),
                datos.salario,
                valorONull(
                        datos.cargo
                ),
                valorONull(
                        datos.banco
                ),
                valorONull(
                        datos.cuentaBancaria
                ),
                activo
        );


        AsociadoDb asociado =
                new AsociadoDb();

        asociado.idAso =
                siguienteId;

        asociado.codAsp =
                fila.documento;

        asociado.fechaNacimiento =
                datos.fechaNacimiento;

        asociado.fechaIngreso =
                fechaIngreso;

        asociado.direccion =
                datos.direccion;

        asociado.barrio =
                datos.barrio;

        asociado.telefono =
                datos.telefono;

        asociado.celular =
                datos.celular;

        asociado.correo =
                datos.correo;

        asociado.salario =
                datos.salario;

        asociado.cargo =
                datos.cargo;

        asociado.banco =
                datos.banco;

        asociado.cuentaBancaria =
                datos.cuentaBancaria;

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


    private boolean esFilaInformativaExcel(
            String documento,
            String nombre) {

        if (
                documento != null
                        && !documento.trim().isEmpty()
        ) {
            return false;
        }


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


        return texto.startsWith(
                "CRUCECONCREDITOYCONSIGNARDIFERENCIA"
        );
    }



    private Map<String, AsociadoDb> cargarAsociados() {

        String sql =
                "SELECT "
                        + "id_aso, "
                        + "LTRIM(RTRIM(cod_asp)) AS cod_asp, "
                        + "fec_nac, "
                        + "fec_ing, "
                        + "dir_res, "
                        + "nom_bar, "
                        + "tel, "
                        + "cel, "
                        + "email_per, "
                        + "sal_bas, "
                        + "cargo, "
                        + "banco, "
                        + "n_cuenta, "
                        + "activo, "
                        + "fecha_retiro "
                        + "FROM FEMPROBIEN.dbo.tblAsociado "
                        + "WHERE cod_asp IS NOT NULL "
                        + "AND LTRIM(RTRIM(cod_asp)) <> ''";


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
                    convertirAsociadoDb(
                            fila,
                            documento
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
                "SELECT TOP 1 "
                        + "id_aso, "
                        + "LTRIM(RTRIM(cod_asp)) AS cod_asp, "
                        + "fec_nac, "
                        + "fec_ing, "
                        + "dir_res, "
                        + "nom_bar, "
                        + "tel, "
                        + "cel, "
                        + "email_per, "
                        + "sal_bas, "
                        + "cargo, "
                        + "banco, "
                        + "n_cuenta, "
                        + "activo, "
                        + "fecha_retiro "
                        + "FROM FEMPROBIEN.dbo.tblAsociado "
                        + "WHERE LTRIM(RTRIM(cod_asp)) = ?";


        List<Map<String, Object>> filas =
                sqlServerJdbcTemplate.queryForList(
                        sql,
                        documento
                );


        if (filas.isEmpty()) {
            return null;
        }


        return convertirAsociadoDb(
                filas.get(0),
                documento
        );
    }


    private AsociadoDb convertirAsociadoDb(
            Map<String, Object> fila,
            String documento) {

        AsociadoDb asociado =
                new AsociadoDb();


        asociado.idAso =
                fila.get("id_aso") == null
                        ? null
                        : ((Number) fila.get("id_aso")).intValue();

        asociado.codAsp =
                documento;

        asociado.fechaNacimiento =
                convertirFechaSql(
                        fila.get("fec_nac")
                );

        asociado.fechaIngreso =
                convertirFechaSql(
                        fila.get("fec_ing")
                );

        asociado.direccion =
                textoMapa(
                        fila.get("dir_res")
                );

        asociado.barrio =
                textoMapa(
                        fila.get("nom_bar")
                );

        asociado.telefono =
                textoMapa(
                        fila.get("tel")
                );

        asociado.celular =
                textoMapa(
                        fila.get("cel")
                );

        asociado.correo =
                textoMapa(
                        fila.get("email_per")
                );

        asociado.salario =
                convertirBigDecimal(
                        fila.get("sal_bas")
                );

        asociado.cargo =
                textoMapa(
                        fila.get("cargo")
                );

        asociado.banco =
                textoMapa(
                        fila.get("banco")
                );

        asociado.cuentaBancaria =
                textoMapa(
                        fila.get("n_cuenta")
                );

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
        }


        return normalizarDocumento(
                leerTextoCelda(
                        cell,
                        formatter,
                        evaluator
                )
        );
    }

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



    private String textoMapa(
            Object valor) {

        return valor == null
                ? ""
                : valor.toString().trim();
    }


    private BigDecimal convertirBigDecimal(
            Object valor) {

        if (valor == null) {
            return null;
        }


        if (valor instanceof BigDecimal) {
            return (BigDecimal) valor;
        }


        if (valor instanceof Number) {

            return BigDecimal.valueOf(
                    ((Number) valor).doubleValue()
            );
        }


        try {

            return new BigDecimal(
                    valor.toString().trim()
            );

        } catch (Exception e) {

            return null;
        }
    }


    private String limpiarTexto(
            String valor) {

        if (valor == null) {
            return "";
        }


        return valor.trim();
    }


    private boolean esVacio(
            String valor) {

        return valor == null
                || valor.trim().isEmpty();
    }


    private String valorONull(
            String valor) {

        if (
                esVacio(
                        valor
                )
        ) {

            return null;
        }


        return valor.trim();
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


    private static class ColumnaDb {
        private String nombre;
        private String tipoDato;
    }



    private static class AsociadoDb {
        private Integer idAso;
        private String codAsp;

        private Date fechaNacimiento;
        private Date fechaIngreso;

        private String direccion;
        private String barrio;

        private String telefono;
        private String celular;
        private String correo;

        private BigDecimal salario;

        private String cargo;
        private String banco;
        private String cuentaBancaria;

        private boolean activo;
        private Date fechaRetiro;
    }


    private static class DatosEmpleadoNovaweb {
        private String baseDatos;

        private Date fechaNacimiento;
        private Date fechaIngreso;

        /*
         * Se utiliza únicamente para decidir cuál registro
         * laboral NOVAWEB es el vigente/más reciente.
         */
        private Date fechaEgreso;

        private String direccion;
        private String barrio;

        private String telefono;
        private String celular;
        private String correo;

        private BigDecimal salario;

        private String codigoCargo;
        private String cargo;

        private String codigoBanco;
        private String banco;

        private String cuentaBancaria;
    }


    private static class FilaImportacion {
        private int numeroFila;
        private String documento = "";
        private String nombre = "";

        private Date fechaNacimiento;
        private Date fechaIngreso;
        private Date fechaRetiro;

        /*
         * Datos recuperados desde NOVAWEB para enriquecer
         * asociados nuevos o completar asociados existentes.
         */
        private DatosEmpleadoNovaweb datosNovaweb;

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
