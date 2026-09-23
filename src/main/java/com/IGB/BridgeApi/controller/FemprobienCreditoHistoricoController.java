package com.IGB.BridgeApi.controller;

import com.IGB.BridgeApi.service.FemprobienCreditoHistoricoService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/femprobien/creditos")
public class FemprobienCreditoHistoricoController {

    private final FemprobienCreditoHistoricoService service;


    public FemprobienCreditoHistoricoController(
            FemprobienCreditoHistoricoService service) {

        this.service =
                service;
    }


    /* =========================================================
       HISTÓRICO POR CÉDULA

       GET
       /femprobien/creditos/historico/asociado/{cedula}

       La consulta sale directamente de tblAporteCredito.
       tblCredito NO es obligatoria.
       ========================================================= */
    @GetMapping(
            "/historico/asociado/{cedula}"
    )
    public ResponseEntity<?> consultarHistoricoPorCedula(
            @PathVariable String cedula) {

        try {

            return ResponseEntity.ok(
                    service.consultarHistoricoPorCedula(
                            cedula
                    )
            );

        } catch (IllegalArgumentException e) {

            return badRequest(
                    e.getMessage()
            );

        } catch (Exception e) {

            e.printStackTrace();


            return internalError(
                    "Error consultando el histórico de créditos.",
                    e
            );
        }
    }


    /* =========================================================
       DETALLE POR ID DE tblAporteCredito

       GET
       /femprobien/creditos/historico/detalle/{id}
       ========================================================= */
    @GetMapping(
            "/historico/detalle/{id}"
    )
    public ResponseEntity<?> consultarCreditoPorId(
            @PathVariable Integer id) {

        try {

            return ResponseEntity.ok(
                    service.consultarCreditoPorId(
                            id
                    )
            );

        } catch (IllegalArgumentException e) {

            Map<String, Object> response =
                    new HashMap<String, Object>();


            response.put(
                    "status",
                    404
            );


            response.put(
                    "message",
                    e.getMessage()
            );


            return ResponseEntity
                    .status(
                            HttpStatus.NOT_FOUND
                    )
                    .body(
                            response
                    );

        } catch (Exception e) {

            e.printStackTrace();


            return internalError(
                    "Error consultando el detalle del crédito.",
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
