package com.IGB.BridgeApi.controller;

import com.IGB.BridgeApi.dto.FormalizarCreditoDTO;
import com.IGB.BridgeApi.service.FemprobienFormalizacionCreditoService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/femprobien/creditos")
public class FemprobienFormalizacionCreditoController {

    private final FemprobienFormalizacionCreditoService service;


    public FemprobienFormalizacionCreditoController(
            FemprobienFormalizacionCreditoService service) {

        this.service = service;
    }


    /* =========================================================
       POST
       FORMALIZAR CRÉDITO

       POST
       /femprobien/creditos/solicitudes/{numeroSolicitud}/formalizar
       ========================================================= */
    @PostMapping(
            "/solicitudes/{numeroSolicitud}/formalizar"
    )
    public ResponseEntity<?> formalizarCredito(
            @PathVariable Integer numeroSolicitud,
            @RequestBody FormalizarCreditoDTO dto) {

        try {

            Map<String, Object> response =
                    service.formalizarCredito(
                            numeroSolicitud,
                            dto
                    );


            return ResponseEntity
                    .status(
                            HttpStatus.CREATED
                    )
                    .body(
                            response
                    );

        } catch (IllegalArgumentException e) {

            return badRequest(
                    e.getMessage()
            );

        } catch (Exception e) {

            e.printStackTrace();


            return internalError(
                    "Error formalizando el crédito.",
                    e
            );
        }
    }


    /* =========================================================
       GET
       CONSULTAR SI LA SOLICITUD YA FUE FORMALIZADA

       GET
       /femprobien/creditos/solicitudes/{numeroSolicitud}/formalizacion
       ========================================================= */
    @GetMapping(
            "/solicitudes/{numeroSolicitud}/formalizacion"
    )
    public ResponseEntity<?> consultarFormalizacion(
            @PathVariable Integer numeroSolicitud) {

        try {

            return ResponseEntity.ok(
                    service.consultarFormalizacion(
                            numeroSolicitud
                    )
            );

        } catch (IllegalArgumentException e) {

            return badRequest(
                    e.getMessage()
            );

        } catch (Exception e) {

            e.printStackTrace();


            return internalError(
                    "Error consultando la formalización del crédito.",
                    e
            );
        }
    }


    private ResponseEntity<?> badRequest(
            String message) {

        Map<String, Object> response =
                new HashMap<String, Object>();

        response.put(
                "status",
                400
        );

        response.put(
                "message",
                message
        );


        return ResponseEntity
                .status(
                        HttpStatus.BAD_REQUEST
                )
                .body(
                        response
                );
    }


    private ResponseEntity<?> internalError(
            String message,
            Exception e) {

        Map<String, Object> response =
                new HashMap<String, Object>();

        response.put(
                "status",
                500
        );

        response.put(
                "message",
                message
        );


        if (
                e != null &&
                        e.getMessage() != null
        ) {

            response.put(
                    "detail",
                    e.getMessage()
            );
        }


        return ResponseEntity
                .status(
                        HttpStatus.INTERNAL_SERVER_ERROR
                )
                .body(
                        response
                );
    }
}
