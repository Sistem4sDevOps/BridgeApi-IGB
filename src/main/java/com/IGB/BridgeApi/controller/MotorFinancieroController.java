
package com.IGB.BridgeApi.controller;

import com.IGB.BridgeApi.service.MotorFinancieroService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/femprobien/motor")
@ConditionalOnProperty(
        name = "femprobien.motor.test-habilitado",
        havingValue = "true"
)
public class MotorFinancieroController {

    private final MotorFinancieroService motor;

    public MotorFinancieroController(
            MotorFinancieroService motor) {
        this.motor = motor;
    }

    // ==========================================
    // EVALUAR INDICADORES FINANCIEROS
    // ==========================================

    @PostMapping("/evaluar")
    public ResponseEntity<Map<String, Object>> evaluar(
            @RequestBody MotorFinancieroService.Entrada entrada) {

        try {

            Map<String, Object> resultado =
                    motor.generarIndicadoresFinancieros(
                            entrada
                    );

            Map<String, Object> respuesta =
                    new LinkedHashMap<>();

            respuesta.put("status", 200);
            respuesta.put(
                    "mensaje",
                    "Análisis financiero realizado correctamente."
            );
            respuesta.put("resultado", resultado);

            return ResponseEntity.ok(respuesta);

        } catch (IllegalArgumentException e) {

            Map<String, Object> error =
                    new LinkedHashMap<>();

            error.put("status", 400);
            error.put("mensaje", e.getMessage());

            return ResponseEntity
                    .badRequest()
                    .body(error);

        } catch (Exception e) {

            e.printStackTrace();

            Map<String, Object> error =
                    new LinkedHashMap<>();

            error.put("status", 500);
            error.put(
                    "mensaje",
                    "Error interno realizando el análisis financiero."
            );

            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(error);
        }
    }
}
