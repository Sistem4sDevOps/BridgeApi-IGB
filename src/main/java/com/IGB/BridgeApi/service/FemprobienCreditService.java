package com.IGB.BridgeApi.service;

import com.IGB.BridgeApi.dto.CreditDecisionDTO;
import com.IGB.BridgeApi.dto.CreditStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class FemprobienCreditService {

    /*
     * Regla FEMPROBIEN:
     * Desde $10.000.000 es obligatorio UN deudor solidario.
     */
    private static final BigDecimal MONTO_MINIMO_DEUDORES_SOLIDARIOS =
            new BigDecimal("10000000");

    private final JdbcTemplate jdbcTemplate;
    private final FemprobienCreditFileService creditFileService;
    private final FemprobienCreditEmailService creditEmailService;

    public FemprobienCreditService(
            @Qualifier("sqlServerJdbcTemplate") JdbcTemplate jdbcTemplate,
            FemprobienCreditFileService creditFileService,
            FemprobienCreditEmailService creditEmailService) {

        this.jdbcTemplate = jdbcTemplate;
        this.creditFileService = creditFileService;
        this.creditEmailService = creditEmailService;
    }

    public synchronized Map<String, Object> crearSolicitud(
            String codAsp,
            Map<String, Object> datos,
            String usuario) {

        if (datos == null) {
            throw new IllegalArgumentException("No se recibió información de la solicitud.");
        }

        Map<String, Object> asociado = obtenerAsociadoActivo(codAsp);

        validarSolicitudAbierta(asociado.get("id_aso"));
        validarDatosBasicos(datos);

        /*
         * Desde $10.000.000 el deudor solidario
         * debe estar registrado en la solicitud.
         */
        validarDeudoresPorMonto(datos);

        validarFormaDescuento(datos);
        validarAutorizaciones(datos);

        Integer numeroSolicitud = siguienteNumeroSolicitud();

        datos.remove("id");
        datos.remove("id_aso");
        datos.remove("numero_solicitud");
        datos.remove("fecha_solicitud");
        datos.remove("estado_credito");
        datos.remove("fecha_creacion");
        datos.remove("fecha_actualizacion");
        datos.remove("fecha_estudio");
        datos.remove("firma_gerente");
        datos.remove("firma_comite_credito");
        datos.remove("comentarios");
        datos.remove("usuario_solicitud");

        datos.put("id_aso", asociado.get("id_aso"));
        datos.put("numero_solicitud", numeroSolicitud);
        datos.put("fecha_solicitud", java.sql.Date.valueOf(LocalDate.now()));
        datos.put("estado_credito", CreditStatus.PENDIENTE.name());

        ponerSiVacio(datos, "nombre_completo_solicitante", asociado.get("nom_aso"));
        ponerSiVacio(datos, "numero_documento_solicitante", asociado.get("cod_asp"));
        ponerSiVacio(datos, "profesion_solicitante", asociado.get("profesion"));
        ponerSiVacio(datos, "cargo_solicitante", asociado.get("cargo"));
        ponerSiVacio(datos, "celular_solicitante", asociado.get("cel"));
        ponerSiVacio(datos, "email_personal_solicitante", asociado.get("email_per"));
        ponerSiVacio(datos, "fecha_nacimiento_solicitante", asociado.get("fec_nac"));
        ponerSiVacio(datos, "estado_civil_solicitante", asociado.get("est_civil"));
        ponerSiVacio(datos, "direccion_residencia_solicitante", asociado.get("dir_res"));
        ponerSiVacio(datos, "salario_solicitante", asociado.get("sal_bas"));
        ponerSiVacio(datos, "empresa_grupo", asociado.get("nom_emp"));

        if (columnaExiste("tblAsociadoCredito", "usuario_solicitud")) {
            datos.put("usuario_solicitud", usuario);
        }

        insertarSolicitud(datos);

        Integer idSolicitud = obtenerIdSolicitud(numeroSolicitud);

        insertarEstado(
                idSolicitud,
                CreditStatus.PENDIENTE,
                "Solicitud de crédito creada.",
                usuario
        );

        Map<String, Object> response = new HashMap<>();
        response.put("status", 201);
        response.put("message", "Solicitud de crédito creada correctamente.");
        response.put("numero_solicitud", numeroSolicitud);
        response.put("cod_asp", codAsp);
        response.put("estado", CreditStatus.PENDIENTE.name());

        return response;
    }

    public List<Map<String, Object>> listarSolicitudes(String estado) {

        if (estado == null || estado.trim().isEmpty()) {
            return jdbcTemplate.queryForList(
                    "SELECT ac.*, a.cod_asp, a.nom_aso " +
                            "FROM FEMPROBIEN.dbo.tblAsociadoCredito ac " +
                            "INNER JOIN FEMPROBIEN.dbo.tblAsociado a ON a.id_aso = ac.id_aso " +
                            "ORDER BY ac.fecha_solicitud DESC, ac.numero_solicitud DESC"
            );
        }

        CreditStatus status = CreditStatus.from(estado);

        return jdbcTemplate.queryForList(
                "SELECT ac.*, a.cod_asp, a.nom_aso " +
                        "FROM FEMPROBIEN.dbo.tblAsociadoCredito ac " +
                        "INNER JOIN FEMPROBIEN.dbo.tblAsociado a ON a.id_aso = ac.id_aso " +
                        "WHERE ac.estado_credito = ? " +
                        "ORDER BY ac.fecha_solicitud DESC, ac.numero_solicitud DESC",
                status.name()
        );
    }

    public List<Map<String, Object>> listarSolicitudesAsociado(String codAsp) {
        return jdbcTemplate.queryForList(
                "SELECT ac.* " +
                        "FROM FEMPROBIEN.dbo.tblAsociadoCredito ac " +
                        "INNER JOIN FEMPROBIEN.dbo.tblAsociado a ON a.id_aso = ac.id_aso " +
                        "WHERE a.cod_asp = ? " +
                        "ORDER BY ac.fecha_solicitud DESC, ac.numero_solicitud DESC",
                codAsp
        );
    }

    public Map<String, Object> consultarSolicitud(Integer numeroSolicitud) {

        Map<String, Object> solicitud = obtenerSolicitudInterna(numeroSolicitud);

        List<Map<String, Object>> estados = jdbcTemplate.queryForList(
                "SELECT id_estado, estado, fecha_estado, comentario, usuario, fecha_creacion " +
                        "FROM FEMPROBIEN.dbo.tblEstadoCredito " +
                        "WHERE id_solicitud = ? " +
                        "ORDER BY fecha_estado DESC, id_estado DESC",
                solicitud.get("id")
        );

        solicitud.put("historial_estados", estados);
        return solicitud;
    }

    public Map<String, Object> cambiarEstado(
            Integer numeroSolicitud,
            CreditDecisionDTO decision) {

        if (decision == null) {
            throw new IllegalArgumentException("No se recibió la decisión del crédito.");
        }

        CreditStatus nuevoEstado = CreditStatus.from(decision.getEstado());
        Map<String, Object> solicitud = obtenerSolicitudInterna(numeroSolicitud);
        CreditStatus estadoActual = CreditStatus.from(
                solicitud.get("estado_credito").toString()
        );

        validarTransicion(estadoActual, nuevoEstado);

        if ((nuevoEstado == CreditStatus.NEGADA || nuevoEstado == CreditStatus.APLAZADA)
                && estaVacio(decision.getComentario())) {
            throw new IllegalArgumentException(
                    "Para negar o aplazar un crédito debe registrar un comentario."
            );
        }

        String sqlUpdate =
                "UPDATE FEMPROBIEN.dbo.tblAsociadoCredito " +
                        "SET estado_credito = ?, fecha_actualizacion = SYSDATETIME() ";

        List<Object> parametros = new ArrayList<>();
        parametros.add(nuevoEstado.name());

        if (nuevoEstado == CreditStatus.APROBADA
                || nuevoEstado == CreditStatus.NEGADA
                || nuevoEstado == CreditStatus.APLAZADA) {
            sqlUpdate += ", fecha_estudio = CONVERT(DATE, GETDATE()) ";
        }

        if (!estaVacio(decision.getComentario())) {
            sqlUpdate += ", comentarios = ? ";
            parametros.add(decision.getComentario().trim());
        }

        sqlUpdate += "WHERE id = ?";
        parametros.add(solicitud.get("id"));

        jdbcTemplate.update(sqlUpdate, parametros.toArray());

        insertarEstado(
                toInteger(solicitud.get("id")),
                nuevoEstado,
                decision.getComentario(),
                decision.getUsuario()
        );

        /*
         * ============================================================
         * NOTIFICACIÓN POR MICROSOFT 365 / MICROSOFT GRAPH
         * ============================================================
         *
         * El cambio de estado y su historial ya quedaron guardados antes
         * de intentar enviar el correo. Si Microsoft Graph falla, el cambio
         * de estado NO se revierte. FemprobienCreditEmailService registra
         * además el resultado del envío en tblNotificacionCredito.
         */
        Map<String, Object> solicitudActualizada =
                obtenerSolicitudInterna(numeroSolicitud);

        Map<String, Object> resultadoCorreo =
                creditEmailService.notificarCambioEstado(
                        solicitudActualizada,
                        nuevoEstado,
                        decision.getComentario(),
                        decision.getUsuario()
                );

        boolean correoEnviado =
                resultadoCorreo != null &&
                        Boolean.TRUE.equals(
                                resultadoCorreo.get("enviado")
                        );

        String mensajeCorreo =
                resultadoCorreo == null
                        ? "No se obtuvo respuesta del servicio de correo."
                        : (
                        resultadoCorreo.get("message") == null
                                ? null
                                : resultadoCorreo.get("message").toString()
                );

        Map<String, Object> response = new HashMap<>();
        response.put("status", 200);
        response.put(
                "message",
                correoEnviado
                        ? "Estado del crédito actualizado y correo enviado correctamente."
                        : "Estado del crédito actualizado correctamente, pero no fue posible enviar el correo."
        );
        response.put("numero_solicitud", numeroSolicitud);
        response.put("estado_anterior", estadoActual.name());
        response.put("estado", nuevoEstado.name());
        response.put("correo_enviado", correoEnviado);
        response.put(
                "correo_destinatario",
                resultadoCorreo == null
                        ? null
                        : resultadoCorreo.get("destinatario")
        );
        response.put("correo_mensaje", mensajeCorreo);

        return response;
    }

    public List<Map<String, Object>> consultarHistorial(Integer numeroSolicitud) {
        Integer idSolicitud = obtenerIdSolicitud(numeroSolicitud);

        return jdbcTemplate.queryForList(
                "SELECT id_estado, estado, fecha_estado, comentario, usuario, fecha_creacion " +
                        "FROM FEMPROBIEN.dbo.tblEstadoCredito " +
                        "WHERE id_solicitud = ? " +
                        "ORDER BY fecha_estado DESC, id_estado DESC",
                idSolicitud
        );
    }

    public Map<String, Object> guardarArchivosCredito(
            Integer numeroSolicitud,
            MultipartFile firmaSolicitante,
            MultipartFile huellaSolicitante,
            MultipartFile firmaDeudor1,
            MultipartFile huellaDeudor1,
            MultipartFile firmaDeudor2,
            MultipartFile huellaDeudor2,
            String usuario) throws Exception {

        Map<String, Object> solicitud =
                obtenerSolicitudInterna(numeroSolicitud);

        validarEstadoCargaArchivos(solicitud);
        validarColumnasBiometria();

        /*
         * Protección adicional del backend.
         * Para solicitudes >= $10.000.000 el deudor solidario
         * debe adjuntar firma Y huella.
         */
        validarArchivosDeudoresPorMonto(
                solicitud,
                firmaDeudor1,
                huellaDeudor1
        );

        if (!tieneArchivo(firmaSolicitante)
                && !tieneArchivo(huellaSolicitante)
                && !tieneArchivo(firmaDeudor1)
                && !tieneArchivo(huellaDeudor1)
                && !tieneArchivo(firmaDeudor2)
                && !tieneArchivo(huellaDeudor2)) {

            throw new IllegalArgumentException(
                    "Debe adjuntar al menos un archivo de firma o huella."
            );
        }

        validarDeudorSiArchivo(
                solicitud,
                "numero_documento_deudor1",
                firmaDeudor1,
                huellaDeudor1,
                "deudor solidario"
        );

        validarDeudorSiArchivo(
                solicitud,
                "numero_documento_deudor2",
                firmaDeudor2,
                huellaDeudor2,
                "deudor solidario 2"
        );

        List<String> archivosNuevos =
                new ArrayList<>();

        List<String> asignaciones =
                new ArrayList<>();

        List<Object> parametros =
                new ArrayList<>();

        List<String> cargados =
                new ArrayList<>();

        try {

            String rutaFirmaSolicitante =
                    guardarSiExiste(
                            numeroSolicitud,
                            firmaSolicitante,
                            "firma_solicitante",
                            archivosNuevos
                    );

            if (rutaFirmaSolicitante != null) {
                asignaciones.add("firma_solicitante = ?");
                parametros.add(rutaFirmaSolicitante);
                asignaciones.add("fecha_firma_solicitante = SYSDATETIME()");
                cargados.add("firmaSolicitante");
            }

            String rutaHuellaSolicitante =
                    guardarSiExiste(
                            numeroSolicitud,
                            huellaSolicitante,
                            "huella_solicitante",
                            archivosNuevos
                    );

            if (rutaHuellaSolicitante != null) {
                asignaciones.add("huella_solicitante = ?");
                parametros.add(rutaHuellaSolicitante);
                asignaciones.add("fecha_huella_solicitante = SYSDATETIME()");
                cargados.add("huellaSolicitante");
            }

            String rutaFirmaDeudor1 =
                    guardarSiExiste(
                            numeroSolicitud,
                            firmaDeudor1,
                            "firma_deudor1",
                            archivosNuevos
                    );

            if (rutaFirmaDeudor1 != null) {
                asignaciones.add("firma_deudor1 = ?");
                parametros.add(rutaFirmaDeudor1);
                asignaciones.add("fecha_firma_deudor1 = SYSDATETIME()");
                cargados.add("firmaDeudor1");
            }

            String rutaHuellaDeudor1 =
                    guardarSiExiste(
                            numeroSolicitud,
                            huellaDeudor1,
                            "huella_deudor1",
                            archivosNuevos
                    );

            if (rutaHuellaDeudor1 != null) {
                asignaciones.add("huella_deudor1 = ?");
                parametros.add(rutaHuellaDeudor1);
                asignaciones.add("fecha_huella_deudor1 = SYSDATETIME()");
                cargados.add("huellaDeudor1");
            }

            String rutaFirmaDeudor2 =
                    guardarSiExiste(
                            numeroSolicitud,
                            firmaDeudor2,
                            "firma_deudor2",
                            archivosNuevos
                    );

            if (rutaFirmaDeudor2 != null) {
                asignaciones.add("firma_deudor2 = ?");
                parametros.add(rutaFirmaDeudor2);
                asignaciones.add("fecha_firma_deudor2 = SYSDATETIME()");
                cargados.add("firmaDeudor2");
            }

            String rutaHuellaDeudor2 =
                    guardarSiExiste(
                            numeroSolicitud,
                            huellaDeudor2,
                            "huella_deudor2",
                            archivosNuevos
                    );

            if (rutaHuellaDeudor2 != null) {
                asignaciones.add("huella_deudor2 = ?");
                parametros.add(rutaHuellaDeudor2);
                asignaciones.add("fecha_huella_deudor2 = SYSDATETIME()");
                cargados.add("huellaDeudor2");
            }

            asignaciones.add("usuario_carga_biometria = ?");
            parametros.add(
                    usuario == null || usuario.trim().isEmpty()
                            ? null
                            : usuario.trim()
            );

            if (columnaExiste("tblAsociadoCredito", "fecha_actualizacion")) {
                asignaciones.add("fecha_actualizacion = SYSDATETIME()");
            }

            String sql =
                    "UPDATE FEMPROBIEN.dbo.tblAsociadoCredito SET " +
                            String.join(", ", asignaciones) +
                            " WHERE numero_solicitud = ?";

            parametros.add(numeroSolicitud);

            int actualizados = jdbcTemplate.update(
                    sql,
                    parametros.toArray()
            );

            if (actualizados != 1) {
                throw new IllegalStateException(
                        "No fue posible actualizar los archivos de la solicitud."
                );
            }

            Map<String, Object> response =
                    new HashMap<>();

            response.put("status", 200);
            response.put(
                    "message",
                    "Firma y huella cargadas correctamente."
            );
            response.put("numero_solicitud", numeroSolicitud);
            response.put("archivos_cargados", cargados);

            return response;

        } catch (Exception e) {

            for (String ruta : archivosNuevos) {
                creditFileService.eliminarArchivoRelativo(ruta);
            }

            throw e;
        }
    }

    public Map<String, Object> consultarEstadoArchivos(
            Integer numeroSolicitud) {

        Map<String, Object> solicitud =
                obtenerSolicitudInterna(numeroSolicitud);

        Map<String, Object> response =
                new HashMap<>();

        response.put("numero_solicitud", numeroSolicitud);
        response.put(
                "firma_solicitante",
                !estaVacio(solicitud.get("firma_solicitante"))
        );
        response.put(
                "huella_solicitante",
                !estaVacio(solicitud.get("huella_solicitante"))
        );
        response.put(
                "firma_deudor1",
                !estaVacio(solicitud.get("firma_deudor1"))
        );
        response.put(
                "huella_deudor1",
                !estaVacio(solicitud.get("huella_deudor1"))
        );
        response.put(
                "firma_deudor2",
                !estaVacio(solicitud.get("firma_deudor2"))
        );
        response.put(
                "huella_deudor2",
                !estaVacio(solicitud.get("huella_deudor2"))
        );

        return response;
    }

    private String guardarSiExiste(
            Integer numeroSolicitud,
            MultipartFile archivo,
            String tipoArchivo,
            List<String> archivosNuevos) throws Exception {

        if (!tieneArchivo(archivo)) {
            return null;
        }

        String ruta = creditFileService.guardarImagen(
                numeroSolicitud,
                archivo,
                tipoArchivo
        );

        archivosNuevos.add(ruta);

        return ruta;
    }

    private boolean tieneArchivo(MultipartFile archivo) {
        return archivo != null && !archivo.isEmpty();
    }

    private void validarDeudorSiArchivo(
            Map<String, Object> solicitud,
            String columnaDocumento,
            MultipartFile firma,
            MultipartFile huella,
            String descripcion) {

        if (!tieneArchivo(firma) && !tieneArchivo(huella)) {
            return;
        }

        if (estaVacio(solicitud.get(columnaDocumento))) {
            throw new IllegalArgumentException(
                    "No puede adjuntar firma o huella para " +
                            descripcion +
                            " porque la solicitud no tiene ese deudor registrado."
            );
        }
    }

    private void validarEstadoCargaArchivos(
            Map<String, Object> solicitud) {

        Object estadoObject = solicitud.get("estado_credito");

        if (estadoObject == null) {
            return;
        }

        CreditStatus estado = CreditStatus.from(
                estadoObject.toString()
        );

        if (estado == CreditStatus.APROBADA ||
                estado == CreditStatus.NEGADA) {

            throw new IllegalArgumentException(
                    "No se pueden reemplazar firma o huella cuando la solicitud está en estado " +
                            estado.name() +
                            "."
            );
        }
    }

    private void validarColumnasBiometria() {

        String[] columnas = new String[]{
                "firma_solicitante",
                "fecha_firma_solicitante",
                "huella_solicitante",
                "fecha_huella_solicitante",
                "firma_deudor1",
                "fecha_firma_deudor1",
                "huella_deudor1",
                "fecha_huella_deudor1",
                "firma_deudor2",
                "fecha_firma_deudor2",
                "huella_deudor2",
                "fecha_huella_deudor2",
                "usuario_carga_biometria"
        };

        for (String columna : columnas) {
            if (!columnaExiste("tblAsociadoCredito", columna)) {
                throw new IllegalStateException(
                        "Falta la columna '" +
                                columna +
                                "' en tblAsociadoCredito. Ejecute primero el script SQL de firma y huella."
                );
            }
        }
    }

    private Map<String, Object> obtenerAsociadoActivo(String codAsp) {

        if (codAsp == null || codAsp.trim().isEmpty()) {
            throw new IllegalArgumentException("El código del asociado es obligatorio.");
        }

        List<Map<String, Object>> resultado = jdbcTemplate.queryForList(
                "SELECT TOP 1 * FROM FEMPROBIEN.dbo.tblAsociado WHERE cod_asp = ?",
                codAsp.trim()
        );

        if (resultado.isEmpty()) {
            throw new IllegalArgumentException(
                    "No se encontró el asociado con código: " + codAsp
            );
        }

        Map<String, Object> asociado = resultado.get(0);

        if (asociado.containsKey("activo")
                && asociado.get("activo") != null
                && !toBoolean(asociado.get("activo"))) {
            throw new IllegalArgumentException("El asociado se encuentra inactivo.");
        }

        if (asociado.containsKey("fecha_retiro")
                && asociado.get("fecha_retiro") != null) {
            throw new IllegalArgumentException(
                    "El asociado tiene fecha de retiro y no puede solicitar créditos."
            );
        }

        return asociado;
    }

    private void validarSolicitudAbierta(Object idAso) {
        Integer cantidad = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) " +
                        "FROM FEMPROBIEN.dbo.tblAsociadoCredito " +
                        "WHERE id_aso = ? " +
                        "AND estado_credito IN ('PENDIENTE', 'EN_ESTUDIO', 'APLAZADA')",
                new Object[]{idAso},
                Integer.class
        );

        if (cantidad != null && cantidad > 0) {
            throw new IllegalArgumentException(
                    "El asociado ya tiene una solicitud de crédito abierta."
            );
        }
    }

    private void validarDatosBasicos(Map<String, Object> datos) {

        BigDecimal monto = toBigDecimal(datos.get("monto_solicitado"));
        if (monto == null || monto.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "El monto solicitado debe ser mayor que cero."
            );
        }

        Integer plazo = toInteger(datos.get("plazo_meses"));
        if (plazo == null || plazo <= 0) {
            throw new IllegalArgumentException(
                    "El plazo del crédito debe ser mayor que cero."
            );
        }

        if (estaVacio(datos.get("destino_credito"))) {
            throw new IllegalArgumentException(
                    "El destino del crédito es obligatorio."
            );
        }
    }

    /* =========================================================
       REGLA DE DEUDORES SOLIDARIOS POR MONTO
       ========================================================= */

    private void validarDeudoresPorMonto(
            Map<String, Object> datos) {

        BigDecimal monto =
                toBigDecimal(
                        datos.get(
                                "monto_solicitado"
                        )
                );


        /*
         * La validación básica del monto se realiza
         * en validarDatosBasicos().
         */
        if (
                monto == null
        ) {
            return;
        }


        /*
         * Menos de $10.000.000:
         * los deudores siguen siendo opcionales.
         */
        if (
                monto.compareTo(
                        MONTO_MINIMO_DEUDORES_SOLIDARIOS
                ) < 0
        ) {
            return;
        }


        /*
         * $10.000.000 o más:
         * un deudor solidario es obligatorio.
         */
        validarDeudorSolidarioObligatorio(
                datos,
                1
        );
    }


    private void validarDeudorSolidarioObligatorio(
            Map<String, Object> datos,
            int numeroDeudor) {

        String sufijo =
                String.valueOf(
                        numeroDeudor
                );


        String nombre =
                "nombre_completo_deudor" +
                        sufijo;

        String tipoDocumento =
                "tipo_documento_deudor" +
                        sufijo;

        String documento =
                "numero_documento_deudor" +
                        sufijo;

        String empresa =
                "empresa_deudor" +
                        sufijo;

        String profesion =
                "profesion_deudor" +
                        sufijo;

        String cargo =
                "cargo_deudor" +
                        sufijo;

        String telefono =
                "telefono_deudor" +
                        sufijo;

        String salario =
                "salario_deudor" +
                        sufijo;

        String fechaNacimiento =
                "fecha_nacimiento_deudor" +
                        sufijo;

        String direccionResidencia =
                "direccion_residencia_deudor" +
                        sufijo;


        String etiquetaDeudor =
                numeroDeudor == 1
                        ? "deudor solidario"
                        : "deudor solidario " + numeroDeudor;


        if (
                estaVacio(
                        datos.get(
                                nombre
                        )
                )
        ) {

            throw new IllegalArgumentException(
                    "Para créditos iguales o superiores a $10.000.000 " +
                            "es obligatorio registrar el nombre del " +
                            etiquetaDeudor +
                            "."
            );
        }


        if (
                estaVacio(
                        datos.get(
                                tipoDocumento
                        )
                )
        ) {

            throw new IllegalArgumentException(
                    "Debe registrar el tipo de documento del " +
                            etiquetaDeudor +
                            "."
            );
        }


        if (
                estaVacio(
                        datos.get(
                                documento
                        )
                )
        ) {

            throw new IllegalArgumentException(
                    "Debe registrar el número de documento del " +
                            etiquetaDeudor +
                            "."
            );
        }


        if (
                estaVacio(
                        datos.get(
                                empresa
                        )
                )
        ) {

            throw new IllegalArgumentException(
                    "Debe registrar la empresa del " +
                            etiquetaDeudor +
                            "."
            );
        }


        if (
                estaVacio(
                        datos.get(
                                profesion
                        )
                )
        ) {

            throw new IllegalArgumentException(
                    "Debe registrar la profesión del " +
                            etiquetaDeudor +
                            "."
            );
        }


        if (
                estaVacio(
                        datos.get(
                                cargo
                        )
                )
        ) {

            throw new IllegalArgumentException(
                    "Debe registrar el cargo del " +
                            etiquetaDeudor +
                            "."
            );
        }


        if (
                estaVacio(
                        datos.get(
                                telefono
                        )
                )
        ) {

            throw new IllegalArgumentException(
                    "Debe registrar el teléfono del " +
                            etiquetaDeudor +
                            "."
            );
        }


        BigDecimal salarioValor =
                toBigDecimal(
                        datos.get(
                                salario
                        )
                );


        if (
                salarioValor == null ||
                        salarioValor.compareTo(
                                BigDecimal.ZERO
                        ) <= 0
        ) {

            throw new IllegalArgumentException(
                    "Debe registrar un salario válido para el " +
                            etiquetaDeudor +
                            "."
            );
        }


        if (
                estaVacio(
                        datos.get(
                                fechaNacimiento
                        )
                )
        ) {

            throw new IllegalArgumentException(
                    "Debe registrar la fecha de nacimiento del " +
                            etiquetaDeudor +
                            "."
            );
        }


        if (
                estaVacio(
                        datos.get(
                                direccionResidencia
                        )
                )
        ) {

            throw new IllegalArgumentException(
                    "Debe registrar la dirección de residencia del " +
                            etiquetaDeudor +
                            "."
            );
        }
    }


    /*
     * Como la creación de la solicitud y la carga de archivos
     * son endpoints separados, aquí protegemos también el
     * segundo paso.
     */
    private void validarArchivosDeudoresPorMonto(
            Map<String, Object> solicitud,
            MultipartFile firmaDeudor1,
            MultipartFile huellaDeudor1) {

        BigDecimal monto =
                toBigDecimal(
                        solicitud.get(
                                "monto_solicitado"
                        )
                );


        if (
                monto == null ||
                        monto.compareTo(
                                MONTO_MINIMO_DEUDORES_SOLIDARIOS
                        ) < 0
        ) {
            return;
        }


        if (
                !tieneArchivo(
                        firmaDeudor1
                ) ||
                        !tieneArchivo(
                                huellaDeudor1
                        )
        ) {

            throw new IllegalArgumentException(
                    "Para créditos iguales o superiores a $10.000.000 " +
                            "debe adjuntar firma y huella del deudor solidario."
            );
        }
    }


    private void validarFormaDescuento(Map<String, Object> datos) {
        boolean q1 = toBoolean(datos.get("descuento_primera_quincena"));
        boolean q2 = toBoolean(datos.get("descuento_segunda_quincena"));
        boolean ambas = toBoolean(datos.get("descuento_ambas_quincenas"));

        int seleccionados = (q1 ? 1 : 0) + (q2 ? 1 : 0) + (ambas ? 1 : 0);

        if (seleccionados != 1) {
            throw new IllegalArgumentException(
                    "Debe seleccionar una sola forma de descuento: quincena 1, quincena 2 o ambas quincenas."
            );
        }
    }

    private void validarAutorizaciones(Map<String, Object> datos) {
        if (!toBoolean(datos.get("certificacion_destino_fondos"))) {
            throw new IllegalArgumentException(
                    "Debe aceptar la certificación de destino de fondos."
            );
        }

        if (!toBoolean(datos.get("autorizacion_tratamiento_datos"))) {
            throw new IllegalArgumentException(
                    "Debe aceptar la autorización de tratamiento de datos."
            );
        }

        if (!toBoolean(datos.get("autorizacion_central_riesgo"))) {
            throw new IllegalArgumentException(
                    "Debe aceptar la autorización de consulta y reporte en centrales de riesgo."
            );
        }

        if (!toBoolean(datos.get("autorizacion_descuento_nomina"))) {
            throw new IllegalArgumentException(
                    "Debe aceptar la autorización de descuento por nómina."
            );
        }
    }

    private void validarTransicion(CreditStatus actual, CreditStatus nuevo) {

        if (actual == nuevo) {
            throw new IllegalArgumentException(
                    "La solicitud ya se encuentra en estado " + actual.name() + "."
            );
        }

        boolean permitido = false;

        if (actual == CreditStatus.PENDIENTE) {
            permitido = nuevo == CreditStatus.EN_ESTUDIO
                    || nuevo == CreditStatus.APROBADA
                    || nuevo == CreditStatus.NEGADA
                    || nuevo == CreditStatus.APLAZADA;
        } else if (actual == CreditStatus.EN_ESTUDIO) {
            permitido = nuevo == CreditStatus.APROBADA
                    || nuevo == CreditStatus.NEGADA
                    || nuevo == CreditStatus.APLAZADA;
        } else if (actual == CreditStatus.APLAZADA) {
            permitido = nuevo == CreditStatus.EN_ESTUDIO
                    || nuevo == CreditStatus.APROBADA
                    || nuevo == CreditStatus.NEGADA;
        }

        if (!permitido) {
            throw new IllegalArgumentException(
                    "No se permite cambiar el estado de "
                            + actual.name() + " a " + nuevo.name() + "."
            );
        }
    }

    private Integer siguienteNumeroSolicitud() {
        Integer numero = jdbcTemplate.queryForObject(
                "SELECT ISNULL(MAX(numero_solicitud), 0) + 1 " +
                        "FROM FEMPROBIEN.dbo.tblAsociadoCredito",
                Integer.class
        );

        return numero == null ? 1 : numero;
    }

    private Map<String, Object> obtenerSolicitudInterna(Integer numeroSolicitud) {
        List<Map<String, Object>> resultado = jdbcTemplate.queryForList(
                "SELECT TOP 1 * " +
                        "FROM FEMPROBIEN.dbo.tblAsociadoCredito " +
                        "WHERE numero_solicitud = ?",
                numeroSolicitud
        );

        if (resultado.isEmpty()) {
            throw new IllegalArgumentException(
                    "No se encontró la solicitud número " + numeroSolicitud + "."
            );
        }

        return resultado.get(0);
    }

    private Integer obtenerIdSolicitud(Integer numeroSolicitud) {
        return toInteger(obtenerSolicitudInterna(numeroSolicitud).get("id"));
    }

    private void insertarEstado(
            Integer idSolicitud,
            CreditStatus estado,
            String comentario,
            String usuario) {

        jdbcTemplate.update(
                "INSERT INTO FEMPROBIEN.dbo.tblEstadoCredito " +
                        "(id_solicitud, estado, comentario, usuario) " +
                        "VALUES (?, ?, ?, ?)",
                idSolicitud,
                estado.name(),
                comentario,
                usuario
        );
    }

    private void insertarSolicitud(Map<String, Object> datos) {

        String tabla = "tblAsociadoCredito";

        List<String> columnasPermitidas = jdbcTemplate.queryForList(
                "SELECT c.name " +
                        "FROM FEMPROBIEN.sys.columns c " +
                        "INNER JOIN FEMPROBIEN.sys.tables t ON t.object_id = c.object_id " +
                        "INNER JOIN FEMPROBIEN.sys.schemas s ON s.schema_id = t.schema_id " +
                        "WHERE t.name = ? " +
                        "AND s.name = 'dbo' " +
                        "AND c.is_identity = 0 " +
                        "AND c.is_computed = 0",
                new Object[]{tabla},
                String.class
        );

        StringBuilder columnas = new StringBuilder();
        StringBuilder valores = new StringBuilder();
        List<Object> parametros = new ArrayList<>();

        for (Map.Entry<String, Object> entry : datos.entrySet()) {
            String columnaReal = buscarColumna(columnasPermitidas, entry.getKey());

            if (columnaReal == null) {
                throw new IllegalArgumentException(
                        "El campo '" + entry.getKey() + "' no existe en " + tabla
                );
            }

            if (columnas.length() > 0) {
                columnas.append(", ");
                valores.append(", ");
            }

            columnas.append("[").append(columnaReal).append("]");
            valores.append("?");
            parametros.add(entry.getValue());
        }

        if (parametros.isEmpty()) {
            throw new IllegalArgumentException("No se recibieron campos para insertar.");
        }

        jdbcTemplate.update(
                "INSERT INTO FEMPROBIEN.dbo." + tabla +
                        " (" + columnas + ") VALUES (" + valores + ")",
                parametros.toArray()
        );
    }

    private boolean columnaExiste(String tabla, String columna) {
        Integer cantidad = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) " +
                        "FROM FEMPROBIEN.INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_SCHEMA = 'dbo' " +
                        "AND TABLE_NAME = ? " +
                        "AND COLUMN_NAME = ?",
                new Object[]{tabla, columna},
                Integer.class
        );

        return cantidad != null && cantidad > 0;
    }

    private String buscarColumna(List<String> columnas, String buscada) {
        for (String columna : columnas) {
            if (columna.equalsIgnoreCase(buscada)) {
                return columna;
            }
        }
        return null;
    }

    private void ponerSiVacio(Map<String, Object> datos, String key, Object value) {
        if (!datos.containsKey(key) || estaVacio(datos.get(key))) {
            datos.put(key, value);
        }
    }

    private boolean estaVacio(Object value) {
        return value == null || value.toString().trim().isEmpty();
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null || value.toString().trim().isEmpty()) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        return new BigDecimal(value.toString());
    }

    private Integer toInteger(Object value) {
        if (value == null || value.toString().trim().isEmpty()) {
            return null;
        }
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.valueOf(value.toString());
    }

    private boolean toBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }

        String text = value.toString().trim();
        return "true".equalsIgnoreCase(text)
                || "1".equals(text)
                || "si".equalsIgnoreCase(text)
                || "sí".equalsIgnoreCase(text)
                || "y".equalsIgnoreCase(text);
    }
}
