package com.IGB.BridgeApi.controller;

import com.IGB.BridgeApi.service.FemprobienMisSolicitudesService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/femprobien/mis-solicitudes")
@CrossOrigin(origins = "*")
public class FemprobienMisSolicitudesController {

    private final FemprobienMisSolicitudesService service;

    public FemprobienMisSolicitudesController(
            FemprobienMisSolicitudesService service) {

        this.service = service;
    }

    /* =========================================================
       CONSULTAR MIS SOLICITUDES

       POST /femprobien/mis-solicitudes/consultar

       Body:
       {
         "codAsp": "1052413815",
         "fechaNacimiento": "1995-08-20"
       }
       ========================================================= */
    @PostMapping("/consultar")
    public ResponseEntity<?> consultarSolicitudes(
            @RequestBody Map<String, Object> datos) {

        try {

            String codAsp =
                    obtenerTexto(
                            datos,
                            "codAsp"
                    );

            String fechaNacimiento =
                    obtenerTexto(
                            datos,
                            "fechaNacimiento"
                    );

            return ResponseEntity.ok(
                    service.consultarSolicitudes(
                            codAsp,
                            fechaNacimiento
                    )
            );

        } catch (IllegalArgumentException e) {

            return badRequest(
                    e.getMessage()
            );

        } catch (Exception e) {

            return internalError(
                    "Error consultando las solicitudes del asociado.",
                    e
            );
        }
    }

    /* =========================================================
       CONSULTAR SEGUIMIENTO DE UNA SOLICITUD DE CRÉDITO

       POST
       /femprobien/mis-solicitudes/creditos/{numeroSolicitud}/historial

       Body:
       {
         "codAsp": "1052413815",
         "fechaNacimiento": "1995-08-20"
       }
       ========================================================= */
    @PostMapping("/creditos/{numeroSolicitud}/historial")
    public ResponseEntity<?> consultarHistorialCredito(
            @PathVariable Integer numeroSolicitud,
            @RequestBody Map<String, Object> datos) {

        try {

            String codAsp =
                    obtenerTexto(
                            datos,
                            "codAsp"
                    );

            String fechaNacimiento =
                    obtenerTexto(
                            datos,
                            "fechaNacimiento"
                    );

            return ResponseEntity.ok(
                    service.consultarHistorialCredito(
                            numeroSolicitud,
                            codAsp,
                            fechaNacimiento
                    )
            );

        } catch (IllegalArgumentException e) {

            return badRequest(
                    e.getMessage()
            );

        } catch (Exception e) {

            return internalError(
                    "Error consultando el seguimiento de la solicitud.",
                    e
            );
        }
    }

    private String obtenerTexto(
            Map<String, Object> datos,
            String campo) {

        if (datos == null || datos.get(campo) == null) {
            return null;
        }

        return datos.get(campo)
                .toString()
                .trim();
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
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
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

        if (e != null && e.getMessage() != null) {
            response.put(
                    "detail",
                    e.getMessage()
            );
        }

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(response);
    }
}
