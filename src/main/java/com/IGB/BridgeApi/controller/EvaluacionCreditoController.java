package com.IGB.BridgeApi.controller;

import com.IGB.BridgeApi.service.EvaluacionCreditoService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/femprobien/evaluacion")
@ConditionalOnProperty(
        name = "femprobien.evaluacion.test-habilitado",
        havingValue = "true"
)
public class EvaluacionCreditoController {

    private final EvaluacionCreditoService service;

    public EvaluacionCreditoController(
            EvaluacionCreditoService service) {

        this.service = service;
    }

    @GetMapping("/solicitudes/{numeroSolicitud}")
    public ResponseEntity<Map<String, Object>> evaluar(
            @PathVariable Integer numeroSolicitud) {

        if (numeroSolicitud == null ||
                numeroSolicitud <= 0) {

            return error(
                    HttpStatus.BAD_REQUEST,
                    "Número de solicitud inválido."
            );
        }

        try {

            return ResponseEntity.ok(
                    service.evaluarSolicitud(numeroSolicitud)
            );

        } catch (IllegalArgumentException e) {

            return error(
                    HttpStatus.NOT_FOUND,
                    e.getMessage()
            );

        } catch (Exception e) {

            System.err.println(
                    "[FEMPROBIEN] Error en estudio financiero " +
                            "de solicitud #" + numeroSolicitud
            );

            e.printStackTrace();

            return error(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "No fue posible generar el estudio financiero."
            );
        }
    }

    private ResponseEntity<Map<String, Object>> error(
            HttpStatus estado,
            String mensaje) {

        Map<String, Object> respuesta =
                new LinkedHashMap<>();

        respuesta.put("status", estado.value());
        respuesta.put("mensaje", mensaje);

        return ResponseEntity
                .status(estado)
                .body(respuesta);
    }
}