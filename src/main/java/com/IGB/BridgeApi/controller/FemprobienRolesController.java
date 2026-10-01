package com.IGB.BridgeApi.controller;

import com.IGB.BridgeApi.service.FemprobienRolesService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/femprobien/roles")
public class FemprobienRolesController {

    private static final String PERMISO_ADMIN_ROLES =
            "ADMINISTRAR_ROLES";

    private static final String HEADER_USUARIO =
            "X-Femprobien-User";

    private final FemprobienRolesService femprobienRolesService;

    public FemprobienRolesController(
            FemprobienRolesService femprobienRolesService) {

        this.femprobienRolesService = femprobienRolesService;
    }

    /* =========================================================
       FASE 1 - CONSULTAR ROLES Y PERMISOS
       GET /femprobien/roles/permisos?username=jvelasquez
       ========================================================= */

    @GetMapping("/permisos")
    public ResponseEntity<?> consultarPermisos(
            @RequestParam("username") String username) {

        try {

            String usuario = normalizarUsername(username);

            if (usuario.isEmpty()) {
                return badRequest("El usuario es obligatorio.");
            }

            return ResponseEntity.ok(
                    femprobienRolesService
                            .consultarRolesPermisosUsuario(usuario)
            );

        } catch (Exception e) {

            return internalError(
                    "No fue posible consultar los permisos FEMPROBIEN.",
                    e
            );
        }
    }

    /* =========================================================
       FASE 3 - USUARIOS CONFIGURADOS
       GET /femprobien/roles/admin/usuarios
       ========================================================= */

    @GetMapping("/admin/usuarios")
    public ResponseEntity<?> consultarUsuarios(

            @RequestHeader(
                    value = HEADER_USUARIO,
                    required = false
            ) String usuarioActual) {

        ResponseEntity<?> acceso =
                validarAdministrador(usuarioActual);

        if (acceso != null) {
            return acceso;
        }

        try {

            return ResponseEntity.ok(
                    femprobienRolesService
                            .consultarUsuariosConfigurados()
            );

        } catch (Exception e) {

            return internalError(
                    "No fue posible consultar los usuarios con roles FEMPROBIEN.",
                    e
            );
        }
    }

    /* =========================================================
       FASE 3 - DETALLE DE USUARIO
       GET /femprobien/roles/admin/usuarios/{username}
       ========================================================= */

    @GetMapping("/admin/usuarios/{username}")
    public ResponseEntity<?> consultarUsuario(

            @PathVariable("username") String username,

            @RequestHeader(
                    value = HEADER_USUARIO,
                    required = false
            ) String usuarioActual) {

        ResponseEntity<?> acceso =
                validarAdministrador(usuarioActual);

        if (acceso != null) {
            return acceso;
        }

        try {

            return ResponseEntity.ok(
                    femprobienRolesService
                            .consultarDetalleUsuario(username)
            );

        } catch (IllegalArgumentException e) {

            return badRequest(e.getMessage());

        } catch (Exception e) {

            return internalError(
                    "No fue posible consultar el detalle de roles del usuario.",
                    e
            );
        }
    }

    /* =========================================================
       FASE 3 - GUARDAR ROLES DE USUARIO
       PUT /femprobien/roles/admin/usuarios/{username}/roles
       ========================================================= */

    @PutMapping("/admin/usuarios/{username}/roles")
    public ResponseEntity<?> guardarRolesUsuario(

            @PathVariable("username") String username,

            @RequestBody Map<String, Object> body,

            @RequestHeader(
                    value = HEADER_USUARIO,
                    required = false
            ) String usuarioActual) {

        ResponseEntity<?> acceso =
                validarAdministrador(usuarioActual);

        if (acceso != null) {
            return acceso;
        }

        try {

            List<String> roles =
                    listaStrings(
                            body == null
                                    ? null
                                    : body.get("roles")
                    );

            return ResponseEntity.ok(
                    femprobienRolesService
                            .actualizarRolesUsuario(
                                    username,
                                    roles,
                                    normalizarUsername(usuarioActual)
                            )
            );

        } catch (IllegalArgumentException e) {

            return badRequest(e.getMessage());

        } catch (Exception e) {

            return internalError(
                    "No fue posible actualizar los roles del usuario.",
                    e
            );
        }
    }

    @PutMapping("/admin/usuarios/{username}/desactivar")
    public ResponseEntity<?> desactivarUsuario(
            @PathVariable("username") String username,
            @RequestHeader(value = HEADER_USUARIO, required = false) String usuarioActual) {
        return retirarUsuario(username, usuarioActual, false);
    }

    @DeleteMapping("/admin/usuarios/{username}")
    public ResponseEntity<?> eliminarConfiguracionUsuario(
            @PathVariable("username") String username,
            @RequestHeader(value = HEADER_USUARIO, required = false) String usuarioActual) {
        return retirarUsuario(username, usuarioActual, true);
    }

    private ResponseEntity<?> retirarUsuario(String username, String usuarioActual, boolean eliminar) {
        ResponseEntity<?> acceso = validarAdministrador(usuarioActual);
        if (acceso != null) {
            return acceso;
        }
        try {
            return ResponseEntity.ok(femprobienRolesService.retirarUsuarioConfigurado(
                    username, normalizarUsername(usuarioActual), eliminar));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return internalError("No fue posible retirar la configuración del usuario FEMPROBIEN.", e);
        }
    }

    /* =========================================================
       FASE 3 - LISTAR ROLES
       GET /femprobien/roles/admin/roles
       ========================================================= */

    @GetMapping("/admin/roles")
    public ResponseEntity<?> consultarRolesAdministracion(

            @RequestHeader(
                    value = HEADER_USUARIO,
                    required = false
            ) String usuarioActual) {

        ResponseEntity<?> acceso =
                validarAdministrador(usuarioActual);

        if (acceso != null) {
            return acceso;
        }

        try {

            return ResponseEntity.ok(
                    femprobienRolesService
                            .consultarRolesAdministracion()
            );

        } catch (Exception e) {

            return internalError(
                    "No fue posible consultar los roles FEMPROBIEN.",
                    e
            );
        }
    }

    /* =========================================================
       FASE 3 - CONSULTAR PERMISOS DEL ROL
       GET /femprobien/roles/admin/roles/{codigoRol}/permisos
       ========================================================= */

    @GetMapping("/admin/roles/{codigoRol}/permisos")
    public ResponseEntity<?> consultarPermisosRol(

            @PathVariable("codigoRol") String codigoRol,

            @RequestHeader(
                    value = HEADER_USUARIO,
                    required = false
            ) String usuarioActual) {

        ResponseEntity<?> acceso =
                validarAdministrador(usuarioActual);

        if (acceso != null) {
            return acceso;
        }

        try {

            return ResponseEntity.ok(
                    femprobienRolesService
                            .consultarPermisosRol(codigoRol)
            );

        } catch (IllegalArgumentException e) {

            return badRequest(e.getMessage());

        } catch (Exception e) {

            return internalError(
                    "No fue posible consultar los permisos del rol.",
                    e
            );
        }
    }

    /* =========================================================
       FASE 3 - GUARDAR PERMISOS DEL ROL
       PUT /femprobien/roles/admin/roles/{codigoRol}/permisos
       ========================================================= */

    @PutMapping("/admin/roles/{codigoRol}/permisos")
    public ResponseEntity<?> guardarPermisosRol(

            @PathVariable("codigoRol") String codigoRol,

            @RequestBody Map<String, Object> body,

            @RequestHeader(
                    value = HEADER_USUARIO,
                    required = false
            ) String usuarioActual) {

        ResponseEntity<?> acceso =
                validarAdministrador(usuarioActual);

        if (acceso != null) {
            return acceso;
        }

        try {

            List<String> permisos =
                    listaStrings(
                            body == null
                                    ? null
                                    : body.get("permisos")
                    );

            return ResponseEntity.ok(
                    femprobienRolesService
                            .actualizarPermisosRol(
                                    codigoRol,
                                    permisos,
                                    normalizarUsername(usuarioActual)
                            )
            );

        } catch (IllegalArgumentException e) {

            return badRequest(e.getMessage());

        } catch (Exception e) {

            return internalError(
                    "No fue posible actualizar los permisos del rol.",
                    e
            );
        }
    }

    /* =========================================================
       FASE 3 - AUDITORÍA
       GET /femprobien/roles/admin/auditoria?limite=100
       ========================================================= */

    @GetMapping("/admin/auditoria")
    public ResponseEntity<?> consultarAuditoria(

            @RequestParam(
                    value = "limite",
                    required = false,
                    defaultValue = "100"
            ) int limite,

            @RequestHeader(
                    value = HEADER_USUARIO,
                    required = false
            ) String usuarioActual) {

        ResponseEntity<?> acceso =
                validarAdministrador(usuarioActual);

        if (acceso != null) {
            return acceso;
        }

        try {

            return ResponseEntity.ok(
                    femprobienRolesService
                            .consultarAuditoria(limite)
            );

        } catch (Exception e) {

            return internalError(
                    "No fue posible consultar la auditoría de roles.",
                    e
            );
        }
    }

    /* =========================================================
       VALIDAR ADMINISTRACIÓN DE ROLES

       BridgeApi no autentica FEMPROBIEN con JWT.
       Angular envía el username de la sesión WMS mediante:

       X-Femprobien-User
       ========================================================= */

    private ResponseEntity<?> validarAdministrador(
            String username) {

        String usuario = normalizarUsername(username);

        if (usuario.isEmpty()) {

            return badRequest(
                    "No fue posible identificar el usuario actual."
            );
        }

        if (!femprobienRolesService
                .tienePermiso(
                        usuario,
                        PERMISO_ADMIN_ROLES
                )) {

            return forbidden(
                    "No tienes permisos para administrar roles FEMPROBIEN."
            );
        }

        return null;
    }

    /* =========================================================
       HELPERS
       ========================================================= */

    private List<String> listaStrings(Object value) {

        List<String> resultado = new ArrayList<>();

        if (!(value instanceof List)) {
            return resultado;
        }

        List<?> valores = (List<?>) value;

        for (Object item : valores) {

            if (item == null) {
                continue;
            }

            String texto = item.toString().trim();

            if (!texto.isEmpty()) {
                resultado.add(texto);
            }
        }

        return resultado;
    }

    private String normalizarUsername(String username) {

        if (username == null) {
            return "";
        }

        return username
                .trim()
                .toLowerCase();
    }

    private ResponseEntity<?> badRequest(String message) {

        return respuestaError(
                HttpStatus.BAD_REQUEST,
                message
        );
    }

    private ResponseEntity<?> forbidden(String message) {

        return respuestaError(
                HttpStatus.FORBIDDEN,
                message
        );
    }

    private ResponseEntity<?> respuestaError(
            HttpStatus status,
            String message) {

        Map<String, Object> response = new HashMap<>();

        response.put("status", status.value());
        response.put("message", message);

        return ResponseEntity
                .status(status)
                .body(response);
    }

    private ResponseEntity<?> internalError(
            String message,
            Exception e) {

        e.printStackTrace();

        Map<String, Object> response = new HashMap<>();

        response.put(
                "status",
                HttpStatus.INTERNAL_SERVER_ERROR.value()
        );

        response.put("message", message);
        response.put("error", e.getMessage());

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(response);
    }
}
