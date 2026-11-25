package com.IGB.BridgeApi.controller;

import com.IGB.BridgeApi.service.FemprobienEntradaSimulacionService;
import com.IGB.BridgeApi.service.FemprobienSimulacionIntegralService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Etapa 1: entradas normalizadas para simulación.
 * No llama al motor de amortización ni realiza cambios en BD.
 */
@RestController
@RequestMapping("/femprobien/simulaciones")
@ConditionalOnProperty(name = "femprobien.evaluacion.test-habilitado", havingValue = "true")
public class FemprobienEntradaSimulacionController {

    private final FemprobienEntradaSimulacionService service;
    private final FemprobienSimulacionIntegralService integral;

    public FemprobienEntradaSimulacionController(FemprobienEntradaSimulacionService service,
                                                 FemprobienSimulacionIntegralService integral) {
        this.service = service;
        this.integral = integral;
    }

    @PostMapping("/preparar")
    public ResponseEntity<?> preparar(@RequestBody Map<String, Object> entrada) {
        try {
            return ResponseEntity.ok(service.preparar(entrada));
        } catch (IllegalArgumentException ex) {
            return error(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo preparar la simulación.");
        }
    }

    @PostMapping("/preparar-lote")
    public ResponseEntity<?> prepararLote(@RequestBody List<Map<String, Object>> entradas) {
        try {
            return ResponseEntity.ok(service.prepararLote(entradas));
        } catch (IllegalArgumentException ex) {
            return error(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo preparar el lote.");
        }
    }

    @PostMapping("/simular")
    public ResponseEntity<?> simular(@RequestBody Map<String, Object> entrada) {
        try { return ResponseEntity.ok(integral.simular(entrada)); }
        catch (IllegalArgumentException ex) { return error(HttpStatus.BAD_REQUEST, ex.getMessage()); }
        catch (Exception ex) { return error(HttpStatus.INTERNAL_SERVER_ERROR, "No fue posible simular esta solicitud."); }
    }

    @PostMapping("/simular-lote")
    public ResponseEntity<?> simularLote(@RequestBody List<Map<String, Object>> entradas) {
        try { return ResponseEntity.ok(integral.simularLote(entradas)); }
        catch (IllegalArgumentException ex) { return error(HttpStatus.BAD_REQUEST, ex.getMessage()); }
        catch (Exception ex) { return error(HttpStatus.INTERNAL_SERVER_ERROR, "No fue posible procesar el lote."); }
    }

    @PostMapping("/comparar")
    public ResponseEntity<?> comparar(@RequestBody Map<String, Object> entrada) {
        try { return ResponseEntity.ok(integral.comparar(entrada)); }
        catch (IllegalArgumentException ex) { return error(HttpStatus.BAD_REQUEST, ex.getMessage()); }
        catch (Exception ex) { return error(HttpStatus.INTERNAL_SERVER_ERROR, "No fue posible comparar los escenarios."); }
    }

    private ResponseEntity<?> error(HttpStatus codigo, String mensaje) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("status", codigo.value());
        cuerpo.put("mensaje", mensaje);
        return ResponseEntity.status(codigo).body(cuerpo);
    }
}
