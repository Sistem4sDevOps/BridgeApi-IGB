package com.IGB.BridgeApi.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class FemprobienRolesService {

    private static final String ROL_ADMIN = "ADMIN_FEMPROBIEN";
    private static final String PERMISO_ADMIN_ROLES = "ADMINISTRAR_ROLES";

    private final JdbcTemplate sqlServerJdbcTemplate;

    public FemprobienRolesService(
            @Qualifier("sqlServerJdbcTemplate")
            JdbcTemplate sqlServerJdbcTemplate) {

        this.sqlServerJdbcTemplate = sqlServerJdbcTemplate;
    }


    /* =========================================================
       CONSULTAR ROLES DE UN USUARIO
       ========================================================= */

    public List<String> consultarRoles(String username) {

        String usuario = normalizarUsername(username);

        if (usuario.isEmpty()) {
            return new ArrayList<>();
        }

        String sql =
                "SELECT DISTINCT r.codigo " +
                        "FROM FEMPROBIEN.dbo.tblFemprobienUsuarioRol ur " +
                        "INNER JOIN FEMPROBIEN.dbo.tblFemprobienRol r " +
                        "ON r.id_rol = ur.id_rol " +
                        "WHERE LOWER(LTRIM(RTRIM(ur.username))) = ? " +
                        "AND ur.activo = 1 " +
                        "AND r.activo = 1 " +
                        "ORDER BY r.codigo";

        return sqlServerJdbcTemplate.query(
                sql,
                new Object[]{usuario},
                (rs, rowNum) -> rs.getString("codigo")
        );
    }


    /* =========================================================
       CONSULTAR PERMISOS EFECTIVOS DE UN USUARIO
       ========================================================= */

    public List<String> consultarPermisos(String username) {

        String usuario = normalizarUsername(username);

        if (usuario.isEmpty()) {
            return new ArrayList<>();
        }

        String sql =
                "SELECT DISTINCT p.codigo " +
                        "FROM FEMPROBIEN.dbo.tblFemprobienUsuarioRol ur " +
                        "INNER JOIN FEMPROBIEN.dbo.tblFemprobienRol r " +
                        "ON r.id_rol = ur.id_rol " +
                        "INNER JOIN FEMPROBIEN.dbo.tblFemprobienRolPermiso rp " +
                        "ON rp.id_rol = r.id_rol " +
                        "INNER JOIN FEMPROBIEN.dbo.tblFemprobienPermiso p " +
                        "ON p.id_permiso = rp.id_permiso " +
                        "WHERE LOWER(LTRIM(RTRIM(ur.username))) = ? " +
                        "AND ur.activo = 1 " +
                        "AND r.activo = 1 " +
                        "AND rp.activo = 1 " +
                        "AND p.activo = 1 " +
                        "ORDER BY p.codigo";

        return sqlServerJdbcTemplate.query(
                sql,
                new Object[]{usuario},
                (rs, rowNum) -> rs.getString("codigo")
        );
    }


    public boolean tienePermiso(
            String username,
            String permiso) {

        String codigoPermiso = normalizarCodigo(permiso);

        if (codigoPermiso.isEmpty()) {
            return false;
        }

        return consultarPermisos(username)
                .contains(codigoPermiso);
    }


    /* =========================================================
       RESPUESTA PARA EL FRONTEND
       ========================================================= */

    public Map<String, Object> consultarRolesPermisosUsuario(
            String username) {

        String usuario = normalizarUsername(username);

        List<String> roles = consultarRoles(usuario);
        List<String> permisos = consultarPermisos(usuario);

        Map<String, Object> response = new HashMap<>();

        response.put("status", 200);
        response.put("username", usuario);
        response.put("roles", roles);
        response.put("permisos", permisos);

        response.put(
                "isAdmin",
                roles.contains(ROL_ADMIN)
        );

        response.put(
                "puedeAprobarSolicitudes",
                permisos.contains("APROBAR_SOLICITUDES")
        );

        response.put(
                "puedeImportarDatos",
                permisos.contains("IMPORTAR_DATOS")
        );

        response.put(
                "puedeAdministrarRoles",
                permisos.contains(PERMISO_ADMIN_ROLES)
        );

        return response;
    }


    /* =========================================================
       FASE 3 - USUARIOS CONFIGURADOS
       ========================================================= */

    public List<Map<String, Object>> consultarUsuariosConfigurados() {

        String sql =
                "SELECT LOWER(LTRIM(RTRIM(ur.username))) AS username, " +
                "r.codigo AS rol_codigo, r.nombre AS rol_nombre, " +
                "CASE WHEN ur.activo = 1 AND r.activo = 1 THEN 1 ELSE 0 END AS asignado " +
                "FROM FEMPROBIEN.dbo.tblFemprobienUsuarioRol ur " +
                "LEFT JOIN FEMPROBIEN.dbo.tblFemprobienRol r ON r.id_rol = ur.id_rol " +
                "ORDER BY LOWER(LTRIM(RTRIM(ur.username))), r.nombre";

        List<Map<String, Object>> filas =
                sqlServerJdbcTemplate.queryForList(sql);

        Map<String, Map<String, Object>> agrupados =
                new LinkedHashMap<>();

        for (Map<String, Object> fila : filas) {

            String username = texto(fila.get("username"));

            if (username.isEmpty()) {
                continue;
            }

            Map<String, Object> usuario = agrupados.get(username);

            if (usuario == null) {

                usuario = new LinkedHashMap<>();
                usuario.put("username", username);
                usuario.put("activo", false);
                usuario.put(
                        "roles",
                        new ArrayList<Map<String, Object>>()
                );

                agrupados.put(username, usuario);
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> roles =
                    (List<Map<String, Object>>) usuario.get("roles");

            if (!Integer.valueOf(1).equals(numeroEntero(fila.get("asignado")))) {
                continue;
            }
            usuario.put("activo", true);
            Map<String, Object> rol = new LinkedHashMap<>();
            rol.put("codigo", fila.get("rol_codigo"));
            rol.put("nombre", fila.get("rol_nombre"));

            roles.add(rol);
        }

        return new ArrayList<>(agrupados.values());
    }


    /* =========================================================
       FASE 3 - DETALLE DE USUARIO
       ========================================================= */

    public Map<String, Object> consultarDetalleUsuario(
            String username) {

        String usuario = normalizarUsername(username);

        if (usuario.isEmpty()) {
            throw new IllegalArgumentException(
                    "El usuario es obligatorio."
            );
        }

        String sqlRoles =
                "SELECT " +
                        "r.id_rol, " +
                        "r.codigo, " +
                        "r.nombre, " +
                        "r.descripcion, " +
                        "CASE " +
                        " WHEN ur.id_usuario_rol IS NOT NULL " +
                        "      AND ur.activo = 1 " +
                        " THEN 1 ELSE 0 END AS asignado " +
                        "FROM FEMPROBIEN.dbo.tblFemprobienRol r " +
                        "LEFT JOIN FEMPROBIEN.dbo.tblFemprobienUsuarioRol ur " +
                        "ON ur.id_rol = r.id_rol " +
                        "AND LOWER(LTRIM(RTRIM(ur.username))) = ? " +
                        "WHERE r.activo = 1 " +
                        "ORDER BY r.nombre";

        List<Map<String, Object>> roles =
                sqlServerJdbcTemplate.queryForList(
                        sqlRoles,
                        usuario
                );

        Map<String, Object> response =
                new LinkedHashMap<>();

        response.put("username", usuario);
        response.put("roles", roles);
        response.put("permisos", consultarPermisos(usuario));
        response.put("activo", !consultarRoles(usuario).isEmpty());
        Integer asignaciones = sqlServerJdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM FEMPROBIEN.dbo.tblFemprobienUsuarioRol " +
                "WHERE LOWER(LTRIM(RTRIM(username))) = ?",
                new Object[]{usuario}, Integer.class);
        response.put("configurado", asignaciones != null && asignaciones > 0);

        return response;
    }


    /* =========================================================
       FASE 3 - LISTAR ROLES
       ========================================================= */

    public List<Map<String, Object>> consultarRolesAdministracion() {

        String sql =
                "SELECT " +
                        "r.id_rol, " +
                        "r.codigo, " +
                        "r.nombre, " +
                        "r.descripcion, " +
                        "r.activo, " +
                        "SUM(CASE WHEN rp.activo = 1 THEN 1 ELSE 0 END) AS cantidad_permisos " +
                        "FROM FEMPROBIEN.dbo.tblFemprobienRol r " +
                        "LEFT JOIN FEMPROBIEN.dbo.tblFemprobienRolPermiso rp " +
                        "ON rp.id_rol = r.id_rol " +
                        "WHERE r.activo = 1 " +
                        "GROUP BY " +
                        "r.id_rol, r.codigo, r.nombre, r.descripcion, r.activo " +
                        "ORDER BY r.nombre";

        return sqlServerJdbcTemplate.queryForList(sql);
    }


    /* =========================================================
       FASE 3 - PERMISOS DE UN ROL
       ========================================================= */

    public Map<String, Object> consultarPermisosRol(
            String codigoRol) {

        String rol = normalizarCodigo(codigoRol);

        if (rol.isEmpty()) {
            throw new IllegalArgumentException(
                    "El rol es obligatorio."
            );
        }

        String sqlRol =
                "SELECT TOP 1 " +
                        "id_rol, codigo, nombre, descripcion " +
                        "FROM FEMPROBIEN.dbo.tblFemprobienRol " +
                        "WHERE codigo = ? " +
                        "AND activo = 1";

        List<Map<String, Object>> roles =
                sqlServerJdbcTemplate.queryForList(
                        sqlRol,
                        rol
                );

        if (roles.isEmpty()) {
            throw new IllegalArgumentException(
                    "No existe el rol " + rol + "."
            );
        }

        Integer idRol =
                numeroEntero(
                        roles.get(0).get("id_rol")
                );

        String sqlPermisos =
                "SELECT " +
                        "p.id_permiso, " +
                        "p.codigo, " +
                        "p.nombre, " +
                        "p.descripcion, " +
                        "CASE " +
                        " WHEN rp.id_rol IS NOT NULL " +
                        "      AND rp.activo = 1 " +
                        " THEN 1 ELSE 0 END AS asignado " +
                        "FROM FEMPROBIEN.dbo.tblFemprobienPermiso p " +
                        "LEFT JOIN FEMPROBIEN.dbo.tblFemprobienRolPermiso rp " +
                        "ON rp.id_permiso = p.id_permiso " +
                        "AND rp.id_rol = ? " +
                        "WHERE p.activo = 1 " +
                        "ORDER BY p.nombre";

        List<Map<String, Object>> permisos =
                sqlServerJdbcTemplate.queryForList(
                        sqlPermisos,
                        idRol
                );

        Map<String, Object> response =
                new LinkedHashMap<>();

        response.put("rol", roles.get(0));
        response.put("permisos", permisos);

        return response;
    }


    /* =========================================================
       FASE 3 - ACTUALIZAR ROLES DE USUARIO
       ========================================================= */

    public Map<String, Object> actualizarRolesUsuario(
            String username, List<String> rolesSolicitados, String realizadoPor) {
        TransactionTemplate transaccion = new TransactionTemplate(
                new DataSourceTransactionManager(sqlServerJdbcTemplate.getDataSource()));
        return transaccion.execute(status ->
                actualizarRolesUsuarioEnTransaccion(username, rolesSolicitados, realizadoPor));
    }

    private Map<String, Object> actualizarRolesUsuarioEnTransaccion(
            String username, List<String> rolesSolicitados, String realizadoPor) {

        String usuario = normalizarUsername(username);
        String administrador = normalizarUsername(realizadoPor);

        if (usuario.isEmpty()) {
            throw new IllegalArgumentException(
                    "El usuario es obligatorio."
            );
        }

        if (administrador.isEmpty()) {
            throw new IllegalArgumentException(
                    "No fue posible identificar al administrador."
            );
        }

        Set<String> solicitados =
                normalizarCodigos(rolesSolicitados);

        Map<String, Integer> rolesDisponibles =
                consultarMapaRolesActivos();

        for (String codigo : solicitados) {

            if (!rolesDisponibles.containsKey(codigo)) {
                throw new IllegalArgumentException(
                        "El rol " + codigo +
                                " no existe o está inactivo."
                );
            }
        }

        Set<String> actuales =
                new LinkedHashSet<>(
                        consultarRoles(usuario)
                );

        if (usuario.equals(administrador)
                && actuales.contains(ROL_ADMIN)
                && !solicitados.contains(ROL_ADMIN)) {

            throw new IllegalArgumentException(
                    "No puedes quitarte tu propio rol Administrador FEMPROBIEN."
            );
        }

        for (Map.Entry<String, Integer> entry :
                rolesDisponibles.entrySet()) {

            String codigoRol = entry.getKey();
            Integer idRol = entry.getValue();

            boolean estabaAsignado =
                    actuales.contains(codigoRol);

            boolean debeQuedarAsignado =
                    solicitados.contains(codigoRol);

            if (estabaAsignado == debeQuedarAsignado) {
                continue;
            }

            if (debeQuedarAsignado) {

                activarRolUsuario(
                        usuario,
                        idRol,
                        administrador
                );

                registrarAuditoria(
                        "ASIGNAR_ROL_USUARIO",
                        usuario,
                        codigoRol,
                        null,
                        "INACTIVO",
                        "ACTIVO",
                        administrador
                );

            } else {

                desactivarRolUsuario(
                        usuario,
                        idRol
                );

                registrarAuditoria(
                        "QUITAR_ROL_USUARIO",
                        usuario,
                        codigoRol,
                        null,
                        "ACTIVO",
                        "INACTIVO",
                        administrador
                );
            }
        }

        return consultarDetalleUsuario(usuario);
    }


    /** Retira únicamente la configuración de roles FEMPROBIEN. */
    public Map<String, Object> retirarUsuarioConfigurado(
            String username, String realizadoPor, boolean eliminar) {
        String usuario = normalizarUsername(username);
        String administrador = normalizarUsername(realizadoPor);
        if (usuario.isEmpty() || administrador.isEmpty()) {
            throw new IllegalArgumentException("El usuario y el administrador son obligatorios.");
        }
        if (usuario.equals(administrador)) {
            throw new IllegalArgumentException("No puedes desactivar ni eliminar tu propia configuración FEMPROBIEN.");
        }
        TransactionTemplate transaccion = new TransactionTemplate(
                new DataSourceTransactionManager(sqlServerJdbcTemplate.getDataSource()));
        return transaccion.execute(status -> {
            int afectados = sqlServerJdbcTemplate.update(
                    eliminar
                            ? "DELETE FROM FEMPROBIEN.dbo.tblFemprobienUsuarioRol " +
                              "WHERE LOWER(LTRIM(RTRIM(username))) = ?"
                            : "UPDATE FEMPROBIEN.dbo.tblFemprobienUsuarioRol SET activo = 0 " +
                              "WHERE LOWER(LTRIM(RTRIM(username))) = ?",
                    usuario);
            if (afectados == 0) {
                throw new IllegalArgumentException("El usuario no tiene configuración de roles FEMPROBIEN.");
            }
            registrarAuditoria(eliminar ? "ELIMINAR_CONFIGURACION_USUARIO" : "DESACTIVAR_USUARIO",
                    usuario, null, null, "CONFIGURADO", eliminar ? "ELIMINADO" : "INACTIVO", administrador);
            return consultarDetalleUsuario(usuario);
        });
    }


    /* =========================================================
       FASE 3 - ACTUALIZAR PERMISOS DE ROL
       ========================================================= */

    @Transactional
    public Map<String, Object> actualizarPermisosRol(
            String codigoRol,
            List<String> permisosSolicitados,
            String realizadoPor) {

        String rol = normalizarCodigo(codigoRol);
        String administrador = normalizarUsername(realizadoPor);

        if (rol.isEmpty()) {
            throw new IllegalArgumentException(
                    "El rol es obligatorio."
            );
        }

        Set<String> solicitados =
                normalizarCodigos(permisosSolicitados);

        Map<String, Integer> rolesDisponibles =
                consultarMapaRolesActivos();

        Integer idRol = rolesDisponibles.get(rol);

        if (idRol == null) {
            throw new IllegalArgumentException(
                    "El rol " + rol +
                            " no existe o está inactivo."
            );
        }

        Map<String, Integer> permisosDisponibles =
                consultarMapaPermisosActivos();

        for (String codigo : solicitados) {

            if (!permisosDisponibles.containsKey(codigo)) {
                throw new IllegalArgumentException(
                        "El permiso " + codigo +
                                " no existe o está inactivo."
                );
            }
        }

        if (ROL_ADMIN.equals(rol)
                && !solicitados.contains(
                PERMISO_ADMIN_ROLES
        )) {

            throw new IllegalArgumentException(
                    "El rol Administrador FEMPROBIEN debe conservar el permiso ADMINISTRAR_ROLES."
            );
        }

        Set<String> actuales =
                consultarPermisosDirectosRol(idRol);

        for (Map.Entry<String, Integer> entry :
                permisosDisponibles.entrySet()) {

            String codigoPermiso = entry.getKey();
            Integer idPermiso = entry.getValue();

            boolean estabaAsignado =
                    actuales.contains(codigoPermiso);

            boolean debeQuedarAsignado =
                    solicitados.contains(codigoPermiso);

            if (estabaAsignado == debeQuedarAsignado) {
                continue;
            }

            if (debeQuedarAsignado) {

                activarPermisoRol(
                        idRol,
                        idPermiso,
                        administrador
                );

                registrarAuditoria(
                        "ASIGNAR_PERMISO_ROL",
                        null,
                        rol,
                        codigoPermiso,
                        "INACTIVO",
                        "ACTIVO",
                        administrador
                );

            } else {

                desactivarPermisoRol(
                        idRol,
                        idPermiso,
                        administrador
                );

                registrarAuditoria(
                        "QUITAR_PERMISO_ROL",
                        null,
                        rol,
                        codigoPermiso,
                        "ACTIVO",
                        "INACTIVO",
                        administrador
                );
            }
        }

        return consultarPermisosRol(rol);
    }


    /* =========================================================
       FASE 3 - AUDITORÍA
       ========================================================= */

    public List<Map<String, Object>> consultarAuditoria(
            int limite) {

        int maximo =
                limite <= 0
                        ? 100
                        : Math.min(limite, 500);

        String sql =
                "SELECT TOP " + maximo + " " +
                        "id_auditoria, " +
                        "accion, " +
                        "username_afectado, " +
                        "rol_codigo, " +
                        "permiso_codigo, " +
                        "valor_anterior, " +
                        "valor_nuevo, " +
                        "realizado_por, " +
                        "fecha_registro " +
                        "FROM FEMPROBIEN.dbo.tblFemprobienRolAuditoria " +
                        "ORDER BY fecha_registro DESC, id_auditoria DESC";

        return sqlServerJdbcTemplate.queryForList(sql);
    }


    /* =========================================================
       HELPERS USUARIO / ROL
       ========================================================= */

    private void activarRolUsuario(
            String username,
            Integer idRol,
            String asignadoPor) {

        Integer cantidad =
                sqlServerJdbcTemplate.queryForObject(
                        "SELECT COUNT(1) " +
                                "FROM FEMPROBIEN.dbo.tblFemprobienUsuarioRol " +
                                "WHERE LOWER(LTRIM(RTRIM(username))) = ? " +
                                "AND id_rol = ?",
                        new Object[]{
                                username,
                                idRol
                        },
                        Integer.class
                );

        if (cantidad != null && cantidad > 0) {

            sqlServerJdbcTemplate.update(
                    "UPDATE FEMPROBIEN.dbo.tblFemprobienUsuarioRol " +
                            "SET activo = 1, " +
                            "asignado_por = ?, " +
                            "fecha_asignacion = GETDATE() " +
                            "WHERE LOWER(LTRIM(RTRIM(username))) = ? " +
                            "AND id_rol = ?",
                    asignadoPor,
                    username,
                    idRol
            );

        } else {

            sqlServerJdbcTemplate.update(
                    "INSERT INTO FEMPROBIEN.dbo.tblFemprobienUsuarioRol " +
                            "(username, id_rol, activo, asignado_por, fecha_asignacion) " +
                            "VALUES (?, ?, 1, ?, GETDATE())",
                    username,
                    idRol,
                    asignadoPor
            );
        }
    }


    private void desactivarRolUsuario(
            String username,
            Integer idRol) {

        sqlServerJdbcTemplate.update(
                "UPDATE FEMPROBIEN.dbo.tblFemprobienUsuarioRol " +
                        "SET activo = 0 " +
                        "WHERE LOWER(LTRIM(RTRIM(username))) = ? " +
                        "AND id_rol = ?",
                username,
                idRol
        );
    }


    /* =========================================================
       HELPERS ROL / PERMISO
       ========================================================= */

    private Set<String> consultarPermisosDirectosRol(
            Integer idRol) {

        String sql =
                "SELECT p.codigo " +
                        "FROM FEMPROBIEN.dbo.tblFemprobienRolPermiso rp " +
                        "INNER JOIN FEMPROBIEN.dbo.tblFemprobienPermiso p " +
                        "ON p.id_permiso = rp.id_permiso " +
                        "WHERE rp.id_rol = ? " +
                        "AND rp.activo = 1 " +
                        "AND p.activo = 1";

        List<String> permisos =
                sqlServerJdbcTemplate.query(
                        sql,
                        new Object[]{idRol},
                        (rs, rowNum) ->
                                rs.getString("codigo")
                );

        return new LinkedHashSet<>(permisos);
    }


    private void activarPermisoRol(
            Integer idRol,
            Integer idPermiso,
            String asignadoPor) {

        Integer cantidad =
                sqlServerJdbcTemplate.queryForObject(
                        "SELECT COUNT(1) " +
                                "FROM FEMPROBIEN.dbo.tblFemprobienRolPermiso " +
                                "WHERE id_rol = ? " +
                                "AND id_permiso = ?",
                        new Object[]{
                                idRol,
                                idPermiso
                        },
                        Integer.class
                );

        if (cantidad != null && cantidad > 0) {

            sqlServerJdbcTemplate.update(
                    "UPDATE FEMPROBIEN.dbo.tblFemprobienRolPermiso " +
                            "SET activo = 1, " +
                            "asignado_por = ?, " +
                            "fecha_modificacion = GETDATE() " +
                            "WHERE id_rol = ? " +
                            "AND id_permiso = ?",
                    asignadoPor,
                    idRol,
                    idPermiso
            );

        } else {

            sqlServerJdbcTemplate.update(
                    "INSERT INTO FEMPROBIEN.dbo.tblFemprobienRolPermiso " +
                            "(id_rol, id_permiso, fecha_asignacion, activo, asignado_por, fecha_modificacion) " +
                            "VALUES (?, ?, GETDATE(), 1, ?, GETDATE())",
                    idRol,
                    idPermiso,
                    asignadoPor
            );
        }
    }


    private void desactivarPermisoRol(
            Integer idRol,
            Integer idPermiso,
            String asignadoPor) {

        sqlServerJdbcTemplate.update(
                "UPDATE FEMPROBIEN.dbo.tblFemprobienRolPermiso " +
                        "SET activo = 0, " +
                        "asignado_por = ?, " +
                        "fecha_modificacion = GETDATE() " +
                        "WHERE id_rol = ? " +
                        "AND id_permiso = ?",
                asignadoPor,
                idRol,
                idPermiso
        );
    }


    /* =========================================================
       HELPERS CATÁLOGOS
       ========================================================= */

    private Map<String, Integer> consultarMapaRolesActivos() {

        List<Map<String, Object>> filas =
                sqlServerJdbcTemplate.queryForList(
                        "SELECT id_rol, codigo " +
                                "FROM FEMPROBIEN.dbo.tblFemprobienRol " +
                                "WHERE activo = 1"
                );

        Map<String, Integer> mapa =
                new LinkedHashMap<>();

        for (Map<String, Object> fila : filas) {

            mapa.put(
                    normalizarCodigo(
                            texto(fila.get("codigo"))
                    ),
                    numeroEntero(
                            fila.get("id_rol")
                    )
            );
        }

        return mapa;
    }


    private Map<String, Integer> consultarMapaPermisosActivos() {

        List<Map<String, Object>> filas =
                sqlServerJdbcTemplate.queryForList(
                        "SELECT id_permiso, codigo " +
                                "FROM FEMPROBIEN.dbo.tblFemprobienPermiso " +
                                "WHERE activo = 1"
                );

        Map<String, Integer> mapa =
                new LinkedHashMap<>();

        for (Map<String, Object> fila : filas) {

            mapa.put(
                    normalizarCodigo(
                            texto(fila.get("codigo"))
                    ),
                    numeroEntero(
                            fila.get("id_permiso")
                    )
            );
        }

        return mapa;
    }


    /* =========================================================
       AUDITORÍA
       ========================================================= */

    private void registrarAuditoria(
            String accion,
            String usernameAfectado,
            String rolCodigo,
            String permisoCodigo,
            String valorAnterior,
            String valorNuevo,
            String realizadoPor) {

        sqlServerJdbcTemplate.update(
                "INSERT INTO FEMPROBIEN.dbo.tblFemprobienRolAuditoria " +
                        "(accion, username_afectado, rol_codigo, permiso_codigo, " +
                        "valor_anterior, valor_nuevo, realizado_por, fecha_registro) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, GETDATE())",
                accion,
                usernameAfectado,
                rolCodigo,
                permisoCodigo,
                valorAnterior,
                valorNuevo,
                realizadoPor
        );
    }


    /* =========================================================
       HELPERS GENERALES
       ========================================================= */

    private Set<String> normalizarCodigos(
            List<String> codigos) {

        Set<String> resultado =
                new LinkedHashSet<>();

        if (codigos == null) {
            return resultado;
        }

        for (String codigo : codigos) {

            String valor = normalizarCodigo(codigo);

            if (!valor.isEmpty()) {
                resultado.add(valor);
            }
        }

        return resultado;
    }


    private String normalizarUsername(
            String username) {

        if (username == null) {
            return "";
        }

        return username
                .trim()
                .toLowerCase();
    }


    private String normalizarCodigo(
            String codigo) {

        if (codigo == null) {
            return "";
        }

        return codigo
                .trim()
                .toUpperCase();
    }


    private String texto(Object value) {

        if (value == null) {
            return "";
        }

        return value.toString().trim();
    }


    private Integer numeroEntero(Object value) {

        if (value == null) {
            return null;
        }

        if (value instanceof Number) {
            return ((Number) value).intValue();
        }

        return Integer.valueOf(value.toString());
    }
}
