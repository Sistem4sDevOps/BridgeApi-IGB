package com.IGB.BridgeApi.controller;
import com.IGB.BridgeApi.service.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class FemprobienSimuladorControllerTest {
    @Test void soloAdministradorPuedeCambiarTasa() {
        FemprobienSimuladorService s = mock(FemprobienSimuladorService.class);
        FemprobienRolesService r = mock(FemprobienRolesService.class);
        FemprobienSimuladorController c = new FemprobienSimuladorController(s,r);
        Map<String,Object> body=new HashMap<>();body.put("tasaMensual",1.35);
        assertEquals(403,c.guardar(body,null).getStatusCodeValue());
        assertEquals(403,c.guardar(body,"lector").getStatusCodeValue());
        verify(s,never()).cambiarTasa(any(),any());
        when(r.consultarRoles("admin")).thenReturn(Arrays.asList("ADMIN_FEMPROBIEN"));
        assertEquals(200,c.guardar(body,"admin").getStatusCodeValue());
        verify(s).cambiarTasa(new BigDecimal("1.35"),"admin");
    }
    @Test void rechazaPlazoFraccionarioAntesDeSimular() {
        FemprobienSimuladorService s=mock(FemprobienSimuladorService.class);
        FemprobienSimuladorController c=new FemprobienSimuladorController(s,mock(FemprobienRolesService.class));
        Map<String,Object> body=new HashMap<>();body.put("plazoMeses",1.5);body.put("monto",1000);body.put("frecuencia","MENSUAL");
        assertEquals(400,c.simular(body).getStatusCodeValue());
        verify(s,never()).simular(any(),anyInt(),anyString());
    }
}
