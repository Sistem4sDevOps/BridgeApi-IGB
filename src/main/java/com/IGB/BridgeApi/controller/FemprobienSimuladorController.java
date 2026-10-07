package com.IGB.BridgeApi.controller;
import com.IGB.BridgeApi.service.FemprobienSimuladorService;
import com.IGB.BridgeApi.service.FemprobienRolesService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/femprobien/simulador-credito")
public class FemprobienSimuladorController {
    private final FemprobienSimuladorService simulador;
    private final FemprobienRolesService roles;
    public FemprobienSimuladorController(FemprobienSimuladorService simulador, FemprobienRolesService roles) { this.simulador=simulador; this.roles=roles; }
    @GetMapping("/configuracion")
    public ResponseEntity<?> configuracion() {
        try { return ResponseEntity.ok(simulador.configuracion()); }
        catch(Exception e) { return error(503,"No fue posible cargar la tasa vigente. Verifique la migración SQL del simulador."); }
    }
    @PutMapping("/configuracion")
    public ResponseEntity<?> guardar(@RequestBody Map<String,Object> body, @RequestHeader(value="X-Femprobien-User",required=false) String usuario) {
        if(usuario==null || !roles.consultarRoles(usuario).contains("ADMIN_FEMPROBIEN")) return error(403,"Solo el administrador FEMPROBIEN puede modificar la tasa.");
        try { return ResponseEntity.ok(simulador.cambiarTasa(decimal(body.get("tasaMensual")),usuario.trim().toLowerCase())); }
        catch(IllegalArgumentException e) { return error(400,e.getMessage()); }
        catch(Exception e) { return error(503,"No fue posible guardar la tasa y su auditoría."); }
    }
    @PostMapping
    public ResponseEntity<?> simular(@RequestBody Map<String,Object> body) {
        try {
            int meses = decimal(body.get("plazoMeses")).intValueExact();
            return ResponseEntity.ok(simulador.simular(decimal(body.get("monto")),meses,String.valueOf(body.get("frecuencia"))));
        } catch(IllegalArgumentException | ArithmeticException e) { return error(400,"Ingrese monto y plazo válidos y una frecuencia mensual o quincenal."); }
        catch(Exception e) { return error(503,"No fue posible simular. Verifique la configuración de la tasa en SQL Server."); }
    }
    private BigDecimal decimal(Object valor) { if(valor==null)throw new IllegalArgumentException("Campo obligatorio."); return new BigDecimal(valor.toString()); }
    private ResponseEntity<?> error(int status,String message) { Map<String,Object> r=new HashMap<>();r.put("message",message);return ResponseEntity.status(status).body(r); }
}
