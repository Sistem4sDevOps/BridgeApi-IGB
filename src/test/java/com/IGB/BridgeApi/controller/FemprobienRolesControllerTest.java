package com.IGB.BridgeApi.controller;
import com.IGB.BridgeApi.service.FemprobienRolesService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class FemprobienRolesControllerTest {
    @Test void sinPermisoNoPuedeDesactivarNiEliminar() {
        FemprobienRolesService service = mock(FemprobienRolesService.class);
        FemprobienRolesController controller = new FemprobienRolesController(service);
        assertEquals(403, controller.desactivarUsuario("persona", "lector").getStatusCodeValue());
        assertEquals(403, controller.eliminarConfiguracionUsuario("persona", "lector").getStatusCodeValue());
        verify(service, never()).retirarUsuarioConfigurado(anyString(), anyString(), anyBoolean());
    }
    @Test void administradorPuedeRetirarConfiguracion() {
        FemprobienRolesService service = mock(FemprobienRolesService.class);
        when(service.tienePermiso("admin", "ADMINISTRAR_ROLES")).thenReturn(true);
        FemprobienRolesController controller = new FemprobienRolesController(service);
        assertEquals(200, controller.desactivarUsuario("persona", "admin").getStatusCodeValue());
        verify(service).retirarUsuarioConfigurado("persona", "admin", false);
        assertEquals(200, controller.eliminarConfiguracionUsuario("persona", "admin").getStatusCodeValue());
        verify(service).retirarUsuarioConfigurado("persona", "admin", true);
    }
}
