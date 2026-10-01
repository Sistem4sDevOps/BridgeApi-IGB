package com.IGB.BridgeApi.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FemprobienRolesServiceTest {
    private JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private Connection connection;
    private FemprobienRolesService service() throws Exception {
        DataSource ds = mock(DataSource.class);
        connection = mock(Connection.class);
        when(ds.getConnection()).thenReturn(connection);
        when(jdbc.getDataSource()).thenReturn(ds);
        when(jdbc.update(anyString(), anyString())).thenReturn(1);
        return new FemprobienRolesService(jdbc);
    }
    @Test void noPermiteDesactivarNiEliminarAlPropioAdministrador() throws Exception {
        FemprobienRolesService s = service();
        assertThrows(IllegalArgumentException.class, () -> s.retirarUsuarioConfigurado("admin", "admin", false));
        assertThrows(IllegalArgumentException.class, () -> s.retirarUsuarioConfigurado("admin", "admin", true));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
    @Test void desactivaAsignacionesYConservaAuditoria() throws Exception {
        FemprobienRolesService s = service();
        s.retirarUsuarioConfigurado("persona", "admin", false);
        verify(jdbc).update(contains("SET activo = 0"), eq("persona"));
        verify(jdbc).update(contains("tblFemprobienRolAuditoria"), eq("DESACTIVAR_USUARIO"), eq("persona"), isNull(), isNull(), eq("CONFIGURADO"), eq("INACTIVO"), eq("admin"));
    }
    @Test void eliminaSoloAsignacionesYConservaAuditoria() throws Exception {
        FemprobienRolesService s = service();
        s.retirarUsuarioConfigurado("persona", "admin", true);
        verify(jdbc).update(startsWith("DELETE FROM FEMPROBIEN.dbo.tblFemprobienUsuarioRol"), eq("persona"));
        verify(jdbc).update(contains("tblFemprobienRolAuditoria"), eq("ELIMINAR_CONFIGURACION_USUARIO"), eq("persona"), isNull(), isNull(), eq("CONFIGURADO"), eq("ELIMINADO"), eq("admin"));
    }
    @Test void listaIncluyeUsuariosSinRolesActivos() throws Exception {
        Map<String,Object> row = new HashMap<>();
        row.put("username", "persona"); row.put("asignado", 0);
        when(jdbc.queryForList(anyString())).thenReturn(Arrays.asList(row));
        Map<String,Object> user = service().consultarUsuariosConfigurados().get(0);
        assertEquals(false, user.get("activo"));
        assertTrue(((List<?>)user.get("roles")).isEmpty());
    }
    @Test void revierteOperacionSiFallaAuditoria() throws Exception {
        FemprobienRolesService s = service();
        doThrow(new IllegalStateException("Auditoria no disponible")).when(jdbc).update(
            contains("tblFemprobienRolAuditoria"), anyString(), anyString(), isNull(), isNull(), anyString(), anyString(), anyString());
        assertThrows(IllegalStateException.class, () -> s.retirarUsuarioConfigurado("persona", "admin", true));
        verify(connection).rollback();
        verify(connection, never()).commit();
    }
    @Test void reactivacionInsertaRolYRegistraAuditoria() throws Exception {
        FemprobienRolesService s = service();
        Map<String,Object> rol = new HashMap<>();
        rol.put("id_rol", 2); rol.put("codigo", "APROBADOR_FEMPROBIEN");
        when(jdbc.queryForList(anyString())).thenReturn(Arrays.asList(rol));
        s.actualizarRolesUsuario("persona", Arrays.asList("APROBADOR_FEMPROBIEN"), "admin");
        verify(jdbc).update(startsWith("INSERT INTO FEMPROBIEN.dbo.tblFemprobienUsuarioRol"), eq("persona"), eq(2), eq("admin"));
        verify(jdbc).update(contains("tblFemprobienRolAuditoria"), eq("ASIGNAR_ROL_USUARIO"), eq("persona"), eq("APROBADOR_FEMPROBIEN"), isNull(), eq("INACTIVO"), eq("ACTIVO"), eq("admin"));
        verify(connection).commit();
    }
}