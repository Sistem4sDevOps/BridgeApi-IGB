package com.IGB.BridgeApi.controller;

import com.IGB.BridgeApi.service.AgenteIAService;
import com.IGB.BridgeApi.service.EvaluacionCreditoService;
import com.IGB.BridgeApi.service.FemprobienRolesService;
import com.IGB.BridgeApi.service.MotorFinancieroService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/femprobien/agente")
@CrossOrigin(origins = "*")
public class AgenteFinancieroController {

    private final AgenteIAService agenteIAService;
    private final EvaluacionCreditoService evaluacionCreditoService;
    private final FemprobienRolesService rolesService;
    private final MotorFinancieroService motorFinancieroService;

    @Value("${femprobien.agente.endpoint-habilitado:false}")
    private boolean endpointHabilitado;

    public AgenteFinancieroController(
            AgenteIAService agenteIAService,
            EvaluacionCreditoService evaluacionCreditoService,
            FemprobienRolesService rolesService,
            MotorFinancieroService motorFinancieroService) {
        this.agenteIAService = agenteIAService;
        this.evaluacionCreditoService = evaluacionCreditoService;
        this.rolesService = rolesService;
        this.motorFinancieroService = motorFinancieroService;
    }

    // Consultas conceptuales o sobre un lote de solicitudes desde el chat.
    // Los estudios se obtienen otra vez en servidor. Los escenarios reenviados
    // por Angular son solo REFERENCIAS, no evidencia verificable en este endpoint.
    @PostMapping("/consultar-general")
    public ResponseEntity<Map<String, Object>> consultarGeneral(
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "X-Femprobien-User", required = false) String usuarioHeader) {
        if (!endpointHabilitado) {
            return error(HttpStatus.FORBIDDEN, "El agente IA no está habilitado.");
        }
        // Mismo control de acceso que /consultar. El header NO autentica:
        // el endpoint sigue restringido a pruebas internas sin un principal seguro.
        String usuario = normalizarUsuario(usuarioHeader);
        if (usuario == null) {
            return error(HttpStatus.UNAUTHORIZED, "No fue posible identificar el usuario de WMS.");
        }
        try {
            if (!rolesService.tienePermiso(usuario, "APROBAR_SOLICITUDES")) {
                return error(HttpStatus.FORBIDDEN, "Permisos insuficientes para FEMPROBIEN.");
            }
        } catch (Exception ex) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "No fue posible validar los permisos.");
        }
        if (request == null || !(request.get("mensaje") instanceof String)) {
            return error(HttpStatus.BAD_REQUEST, "El mensaje es obligatorio.");
        }
        String mensaje = ((String) request.get("mensaje")).trim();
        if (mensaje.length() < 1 || mensaje.length() > 2000) {
            return error(HttpStatus.BAD_REQUEST, "El mensaje debe contener entre 1 y 2000 caracteres.");
        }

        List<Map<String, String>> historial;
        try {
            historial = validarHistorialConversacional(request.get("historial"));
        } catch (IllegalArgumentException ex) {
            return error(HttpStatus.BAD_REQUEST, ex.getMessage());
        }

        List<Integer> numeros = new ArrayList<>();
        try {
            Object lista = request.get("numeroSolicitudes");
            if (lista != null) {
                if (!(lista instanceof List)) {
                    throw new IllegalArgumentException("La selección de solicitudes es inválida.");
                }
                for (Object valor : (List<?>) lista) {
                    int numero = enteroPositivo(valor, 999999999);
                    if (numeros.contains(numero)) {
                        throw new IllegalArgumentException("La selección contiene solicitudes repetidas.");
                    }
                    numeros.add(numero);
                    if (numeros.size() > 20) {
                        throw new IllegalArgumentException("Se permiten máximo 20 solicitudes por consulta.");
                    }
                }
            }
            if (numeros.isEmpty() && request.get("resultadosSimulacion") != null) {
                throw new IllegalArgumentException("Selecciona las solicitudes antes de consultar simulaciones.");
            }
            List<Map<String, Object>> resultados =
                    contextoResultadosReportados(request.get("resultadosSimulacion"), numeros);

            List<Map<String, Object>> estudios = new ArrayList<>();
            int consultadas = 0;
            for (Integer numero : numeros) {
                Map<String, Object> registro = new LinkedHashMap<>();
                registro.put("numeroSolicitud", numero);
                try {
                    // Nunca se acepta desde Angular la nómina/cartera como verificada.
                    Map<String, Object> estudio = evaluacionCreditoService.evaluarSolicitud(numero);
                    if (estudio != null && !estudio.isEmpty()) {
                        registro.put("estudio", estudio);
                        consultadas++;
                    } else {
                        registro.put("sinEstudio", true);
                    }
                } catch (Exception ex) {
                    // No revelar detalles de bases de datos ni de otros sistemas.
                    registro.put("sinEstudio", true);
                }
                estudios.add(registro);
            }
            String respuesta = numeros.isEmpty()
                    ? agenteIAService.consultar(mensaje, historial)
                    : agenteIAService.consultarConLote(mensaje, estudios, resultados, historial);

            Map<String, Object> salida = new LinkedHashMap<>();
            salida.put("status", 200);
            salida.put("tipoRespuesta", numeros.isEmpty() ? "ORIENTACION_GENERAL" : "ANALISIS_IA_LOTE");
            salida.put("respuesta", respuesta);
            salida.put("solicitudesSeleccionadas", numeros.size());
            salida.put("estudiosRecuperadosServidor", consultadas);
            salida.put("resultadosReenviadosNoReverificados", resultados.size());
            salida.put("decisionAutomatica", false);
            salida.put("modificaSolicitud", false);
            return ResponseEntity.ok(salida);
        } catch (IllegalArgumentException ex) {
            return error(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            return error(HttpStatus.SERVICE_UNAVAILABLE,
                    "No fue posible responder la consulta general de solicitudes.");
        }
    }

    // Lista blanca: rechaza identificadores desconocidos y NO admite texto libre
    // ni tablas de amortización enviadas por el navegador.
    private List<Map<String, Object>> contextoResultadosReportados(
            Object valor, List<Integer> numeros) {
        List<Map<String, Object>> resultados = new ArrayList<>();
        if (valor == null) return resultados;
        if (!(valor instanceof List)) {
            throw new IllegalArgumentException("Formato de resultados de simulación inválido.");
        }
        if (((List<?>) valor).size() > 20) {
            throw new IllegalArgumentException("Se permiten máximo 20 resultados de simulación.");
        }
        List<Integer> vistos = new ArrayList<>();
        for (Object elemento : (List<?>) valor) {
            if (!(elemento instanceof Map)) {
                throw new IllegalArgumentException("Resultado de simulación inválido.");
            }
            Map<String, Object> fila = mapa(elemento);
            int numero = enteroPositivo(fila.get("numeroSolicitud"), 999999999);
            if (!numeros.contains(numero) || vistos.contains(numero)) {
                throw new IllegalArgumentException("La simulación no coincide con las solicitudes seleccionadas.");
            }
            vistos.add(numero);
            Map<String, Object> limpio = new LinkedHashMap<>();
            limpio.put("numeroSolicitud", numero);
            // El estado del cálculo NO es el estado oficial de la solicitud.
            Object estado = fila.get("estado");
            if (estado instanceof String && ((String) estado).matches("[A-Za-z_]{1,32}")) {
                limpio.put("estadoCalculoReportado", estado);
            }
            Map<String, Object> simulacion = mapa(fila.get("simulacion"));
            Map<String, Object> s = new LinkedHashMap<>();
            copiarNumeroReportado(s, simulacion, "cuotaPorPeriodo", "cuotaPorPeriodo");
            copiarNumeroReportado(s, simulacion, "cuotaMensualEquivalente", "cuotaMensualEquivalente");
            copiarNumeroReportado(s, simulacion, "plazoMeses", "plazoMeses");
            copiarNumeroReportado(s, simulacion, "interesesTotales", "interesesTotales");
            copiarNumeroReportado(s, simulacion, "totalPagar", "totalPagar");
            Object frecuencia = simulacion.get("frecuenciaPago");
            if (frecuencia instanceof String &&
                    ("MENSUAL".equals(frecuencia) || "QUINCENAL".equals(frecuencia))) {
                s.put("frecuenciaPago", frecuencia);
            }
            if (!s.isEmpty()) limpio.put("simulacion", s);
            Map<String, Object> capacidad = mapa(fila.get("capacidadPago"));
            Map<String, Object> cap = new LinkedHashMap<>();
            copiarNumeroReportado(cap, capacidad, "topeActualPorcentaje", "topeActualPorcentaje");
            copiarNumeroReportado(cap, capacidad, "topeProyectadoPorcentaje", "topeProyectadoPorcentaje");
            if (!cap.isEmpty()) limpio.put("capacidadPago", cap);
            Map<String, Object> reglamento = mapa(fila.get("validacionReglamentaria"));
            if (reglamento.get("plazoCumple") instanceof Boolean) {
                limpio.put("plazoCumpleReportado", reglamento.get("plazoCumple"));
            }
            resultados.add(limpio);
        }
        return resultados;
    }

    private void copiarNumeroReportado(Map<String, Object> destino,
                                       Map<String, Object> fuente, String origen, String destinoClave) {
        Object valor = fuente.get(origen);
        if (!(valor instanceof Number)) return;
        BigDecimal n;
        try {
            n = new BigDecimal(valor.toString());
        } catch (NumberFormatException ex) {
            return;
        }
        if (n.signum() >= 0 && n.compareTo(new BigDecimal("100000000000000")) <= 0) {
            destino.put(destinoClave, n);
        }
    }

    // Historial solo conversacional. Jamás se usa como nómina, cartera o estudio.
    // El backend reconstruye los datos oficiales para cada consulta.
    private List<Map<String, String>> validarHistorialConversacional(Object valor) {
        List<Map<String, String>> limpio = new ArrayList<>();
        if (valor == null) return limpio; // Compatible con clientes Angular anteriores.
        if (!(valor instanceof List) || ((List<?>) valor).size() > 10) {
            throw new IllegalArgumentException("El historial debe tener máximo diez mensajes.");
        }
        int totalCaracteres = 0;
        for (Object item : (List<?>) valor) {
            if (!(item instanceof Map)) {
                throw new IllegalArgumentException("Formato de historial inválido.");
            }
            Map<String, Object> mensaje = mapa(item);
            Object rol = mensaje.get("rol");
            Object contenido = mensaje.get("contenido");
            if (!(rol instanceof String) ||
                    !("usuario".equals(rol) || "asistente".equals(rol)) ||
                    !(contenido instanceof String)) {
                throw new IllegalArgumentException("El historial contiene mensajes inválidos.");
            }
            String texto = ((String) contenido).trim();
            if (texto.isEmpty() || texto.length() > 1200) {
                throw new IllegalArgumentException("La longitud de un mensaje histórico es inválida.");
            }
            totalCaracteres += texto.length();
            if (totalCaracteres > 12000) {
                throw new IllegalArgumentException("El historial supera el límite permitido.");
            }
            Map<String, String> turno = new LinkedHashMap<>();
            turno.put("rol", (String) rol);
            turno.put("contenido", texto);
            limpio.add(turno);
        }
        return limpio;
    }

    @PostMapping("/consultar")
    public ResponseEntity<Map<String, Object>> consultar(
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "X-Femprobien-User", required = false) String usuarioHeader) {

        if (!endpointHabilitado) {
            return error(HttpStatus.FORBIDDEN, "El agente IA no está habilitado.");
        }

        // IMPORTANTE: este valor es declarado por Angular y NO acredita identidad.
        // El rol se valida en SQL Server, pero la petición puede suplantar el username.
        // Uso restringido a pruebas internas; NO sustituye autenticación en producción.
        String usuario = normalizarUsuario(usuarioHeader);
        if (usuario == null) {
            return error(HttpStatus.UNAUTHORIZED,
                    "No fue posible identificar el usuario de WMS.");
        }

        try {
            if (!rolesService.tienePermiso(usuario, "APROBAR_SOLICITUDES")) {
                return error(HttpStatus.FORBIDDEN,
                        "No tiene permiso para aprobar o rechazar créditos en FEMPROBIEN.");
            }
        } catch (Exception e) {
            return error(HttpStatus.SERVICE_UNAVAILABLE,
                    "No fue posible validar los permisos de FEMPROBIEN.");
        }

        if (request == null || !(request.get("mensaje") instanceof String)) {
            return error(HttpStatus.BAD_REQUEST, "El mensaje es obligatorio.");
        }
        String mensaje = ((String) request.get("mensaje")).trim();
        if (mensaje.isEmpty() || mensaje.length() > 2000) {
            return error(HttpStatus.BAD_REQUEST,
                    "El mensaje debe contener entre 1 y 2000 caracteres.");
        }

        List<Map<String, String>> historial;
        try {
            historial = validarHistorialConversacional(request.get("historial"));
        } catch (IllegalArgumentException ex) {
            return error(HttpStatus.BAD_REQUEST, ex.getMessage());
        }

        Integer numeroSolicitud;
        try {
            Object valor = request.get("numeroSolicitud");
            if (valor == null) {
                throw new IllegalArgumentException("Número vacío");
            }
            numeroSolicitud = Integer.valueOf(valor.toString());
            if (numeroSolicitud.intValue() <= 0) {
                throw new IllegalArgumentException("Número no positivo");
            }
        } catch (Exception e) {
            return error(HttpStatus.BAD_REQUEST,
                    "El número de solicitud debe ser un entero positivo.");
        }

        try {
            // Los datos financieros son recuperados desde el backend,
            // nunca se reciben del navegador.
            Map<String, Object> estudio =
                    evaluacionCreditoService.evaluarSolicitud(numeroSolicitud);
            String respuesta = agenteIAService.consultarConEstudio(mensaje, estudio, historial);

            Map<String, Object> resultado = new LinkedHashMap<String, Object>();
            resultado.put("status", 200);
            resultado.put("tipoRespuesta", "ANALISIS_IA_PRELIMINAR");
            resultado.put("numeroSolicitud", numeroSolicitud);
            resultado.put("respuesta", respuesta);
            resultado.put("decisionAutomatica", false);
            resultado.put("modificaSolicitud", false);
            return ResponseEntity.ok(resultado);

        } catch (IllegalArgumentException e) {
            return error(HttpStatus.NOT_FOUND,
                    "No fue posible consultar la solicitud indicada.");
        } catch (Exception e) {
            // No revelar detalles del servicio OpenAI ni información sensible.
            return error(HttpStatus.SERVICE_UNAVAILABLE,
                    "No fue posible generar el análisis financiero con IA.");
        }
    }


    // Interpreta escenarios YA calculados por el simulador de BridgeApi.
    // Los valores de amortizacion vuelven desde Angular y NO se pueden usar
    // como evidencia autenticada ni para aprobar creditos. El motor de
    // indicadores calcula de nuevo la exposicion de cada escenario usando
    // los datos oficiales del estudio consultado en este endpoint.
    @PostMapping("/interpretar-escenarios")
    public ResponseEntity<Map<String, Object>> interpretarEscenarios(
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "X-Femprobien-User", required = false)
            String usuarioHeader) {
        if (!endpointHabilitado) {
            return error(HttpStatus.FORBIDDEN, "El agente IA no esta habilitado.");
        }
        // El encabezado NO acredita identidad. Solo pruebas internas mientras
        // la autenticacion del backend no use un principal de servidor confiable.
        String usuario = normalizarUsuario(usuarioHeader);
        if (usuario == null) {
            return error(HttpStatus.UNAUTHORIZED, "No fue posible identificar el usuario de WMS.");
        }
        try {
            if (!rolesService.tienePermiso(usuario, "APROBAR_SOLICITUDES")) {
                return error(HttpStatus.FORBIDDEN, "Permisos insuficientes para FEMPROBIEN.");
            }
        } catch (Exception e) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "No fue posible validar permisos.");
        }

        try {
            if (request == null) {
                return error(HttpStatus.BAD_REQUEST, "La peticion es obligatoria.");
            }
            Integer numeroSolicitud = enteroPositivo(request.get("numeroSolicitud"), 999999999);
            String tipo = String.valueOf(request.get("tipo"));
            if (!"SIMULACION".equals(tipo) && !"COMPARACION".equals(tipo)) {
                return error(HttpStatus.BAD_REQUEST, "Tipo de interpretacion invalido.");
            }
            if (!(request.get("escenarios") instanceof List)) {
                return error(HttpStatus.BAD_REQUEST, "Los escenarios son obligatorios.");
            }
            List<?> enviados = (List<?>) request.get("escenarios");
            if (enviados.size() != ("SIMULACION".equals(tipo) ? 1 : 3)) {
                return error(HttpStatus.BAD_REQUEST, "Cantidad de escenarios invalida.");
            }

            // SOLO la evaluacion oficial se consulta directamente desde SQL/BridgeApi.
            Map<String, Object> estudio =
                    evaluacionCreditoService.evaluarSolicitud(numeroSolicitud);
            Map<String, Object> indicadoresBase = mapa(estudio.get("indicadoresFinancieros"));
            Map<String, Object> cartera = mapa(estudio.get("cartera"));

            List<Map<String, Object>> escenarios = new ArrayList<>();
            BigDecimal montoComun = null;
            BigDecimal tnaComun = null;
            String frecuenciaComun = null;
            for (Object item : enviados) {
                if (!(item instanceof Map)) {
                    throw new IllegalArgumentException("Formato de escenario invalido.");
                }
                Map<String, Object> e = mapa(item);
                BigDecimal monto = decimalRequerido(e.get("monto"), true, "monto");
                BigDecimal tna = decimalRequerido(e.get("tna"), false, "tasa TNA");
                if (tna.compareTo(new BigDecimal("100")) > 0) {
                    throw new IllegalArgumentException("TNA fuera de rango.");
                }
                BigDecimal meses = decimalRequerido(e.get("plazoMeses"), true, "plazo");
                if (meses.compareTo(new BigDecimal("480")) > 0) {
                    throw new IllegalArgumentException("Plazo fuera de rango.");
                }
                int cantidad = enteroPositivo(e.get("cantidadCuotas"), 240);
                String frecuencia = String.valueOf(e.get("frecuencia"));
                int pagosAnio = pagosPorAnio(frecuencia);
                if (pagosAnio == 0 ||
                        meses.multiply(new BigDecimal(pagosAnio))
                                .divide(new BigDecimal("12"), 5, RoundingMode.HALF_UP)
                                .subtract(new BigDecimal(cantidad)).abs()
                                .compareTo(new BigDecimal("0.001")) > 0) {
                    throw new IllegalArgumentException("Frecuencia y cuotas inconsistentes.");
                }
                BigDecimal cuota = decimalRequerido(e.get("cuota"), true, "cuota");
                BigDecimal intereses = decimalRequerido(e.get("interesesTotales"), false, "intereses");
                BigDecimal total = decimalRequerido(e.get("total"), true, "total");
                if (total.compareTo(monto) < 0 || total.compareTo(cuota) < 0) {
                    throw new IllegalArgumentException("Totales inconsistentes.");
                }
                if (montoComun != null && (monto.compareTo(montoComun) != 0 ||
                        tna.compareTo(tnaComun) != 0 ||
                        !frecuencia.equals(frecuenciaComun))) {
                    throw new IllegalArgumentException(
                            "La comparacion debe compartir monto, TNA y frecuencia.");
                }
                montoComun = monto;
                tnaComun = tna;
                frecuenciaComun = frecuencia;

                BigDecimal mensual = cuota.multiply(new BigDecimal(pagosAnio))
                        .divide(new BigDecimal("12"), 2, RoundingMode.HALF_UP);
                MotorFinancieroService.Entrada entrada = new MotorFinancieroService.Entrada();
                entrada.salarioBasicoMensual = opcional(indicadoresBase.get("salarioBasicoMensual"));
                entrada.nominaMensualCompleta =
                        Boolean.TRUE.equals(indicadoresBase.get("nominaMensualCompleta"));
                entrada.deduccionesValidadasParaMotor =
                        Boolean.TRUE.equals(indicadoresBase.get("deduccionesValidadasParaMotor"));
                entrada.deduccionesMensuales =
                        entrada.deduccionesValidadasParaMotor
                                ? opcional(indicadoresBase.get("deduccionesMensuales")) : null;
                entrada.deduccionesMensualesObservadas =
                        opcional(indicadoresBase.get("deduccionesMensualesObservadas"));
                entrada.saldoCreditosVigentes =
                        opcional(indicadoresBase.get("saldoCreditosVigentes"));
                entrada.carteraActualizada =
                        Boolean.TRUE.equals(cartera.get("carteraActualizada"));
                entrada.montoSolicitado = monto;
                entrada.plazoMeses = meses.setScale(0, RoundingMode.HALF_UP).intValue();
                entrada.cuotaNuevaMensual = mensual;
                Map<String, Object> motor = motorFinancieroService.generarIndicadoresFinancieros(entrada);

                Map<String, Object> salida = new LinkedHashMap<>();
                salida.put("plazoMeses", meses);
                salida.put("frecuencia", frecuencia);
                salida.put("cuotaPorPeriodo", cuota);
                salida.put("cuotaMensualEquivalente", mensual);
                salida.put("interesesTotales", intereses);
                salida.put("total", total);
                salida.put("topeActual", motor.get("porcentajeDeduccionesActual"));
                salida.put("topeProyectado", motor.get("porcentajeDeduccionesProyectado"));
                salida.put("estadoNumericoPreliminar", motor.get("estadoAnalisisFinanciero"));
                salida.put("pendientes", motor.get("pendientes"));
                salida.put("incumplimientos", motor.get("incumplimientos"));
                salida.put("escenarioObservado", motor.get("escenarioObservado"));
                escenarios.add(salida);
            }
            escenarios.sort(Comparator.comparing(e -> (BigDecimal)e.get("plazoMeses")));
            if ("COMPARACION".equals(tipo)) {
                for (int i = 1; i < escenarios.size(); i++) {
                    if (((BigDecimal)escenarios.get(i - 1).get("plazoMeses"))
                            .compareTo((BigDecimal)escenarios.get(i).get("plazoMeses")) == 0) {
                        throw new IllegalArgumentException("Los plazos deben ser distintos.");
                    }
                }
            }
            Map<String, Object> analisis = new LinkedHashMap<>();
            analisis.put("tipo", tipo);
            analisis.put("escenarios", escenarios);
            analisis.put("origenCalculos", "MotorFinancieroService sobre evaluacion del servidor");
            analisis.put("escenariosReportadosPorNavegador", true);
            analisis.put("limiteReglamentarioPorcentaje", 50);
            analisis.put("plazoGeneralMaximoMeses", 24);
            List<String> conclusiones = new ArrayList<>();
            if ("SIMULACION".equals(tipo)) {
                Map<String,Object> e = escenarios.get(0);
                conclusiones.add("La cuota por periodo es " + cop(e.get("cuotaPorPeriodo")) +
                        "; equivalente mensual promedio " + cop(e.get("cuotaMensualEquivalente")) +
                        ". Este promedio no equivale necesariamente al descuento exacto en cada mes.");
                conclusiones.add("Intereses calculados: " + cop(e.get("interesesTotales")) +
                        "; total estimado: " + cop(e.get("total")) + ".");
                agregarHallazgos(e, conclusiones);
            } else {
                Map<String,Object> corto = escenarios.get(0);
                Map<String,Object> largo = escenarios.get(escenarios.size() - 1);
                BigDecimal ahorroMensual =
                        ((BigDecimal)corto.get("cuotaMensualEquivalente"))
                                .subtract((BigDecimal)largo.get("cuotaMensualEquivalente"));
                conclusiones.add("Del plazo de " + corto.get("plazoMeses") +
                        " al de " + largo.get("plazoMeses") +
                        " meses, la cuota mensual equivalente " +
                        (ahorroMensual.signum() > 0 ? "disminuye en " :
                                (ahorroMensual.signum() < 0 ? "aumenta en " : "no cambia (")) +
                        cop(ahorroMensual.abs()) +
                        (ahorroMensual.signum() == 0 ? ")" : "") + ".");
                conclusiones.add("El cambio del plazo mas corto al mas largo modifica los " +
                        "intereses totales en " +
                        cop(((BigDecimal)largo.get("interesesTotales"))
                                .subtract((BigDecimal)corto.get("interesesTotales"))) +
                        ". Una cuota menor no significa automaticamente menor costo total.");
                conclusiones.add("La cuota mensual equivalente es un promedio " +
                        "aritmetico, no representa necesariamente el descuento real de cada mes.");
                for (Map<String,Object> e : escenarios) {
                    if (e.get("topeProyectado") == null) {
                        conclusiones.add("Alternativa " + e.get("plazoMeses") +
                                " meses: requiere validar nomina, deducciones y/o salario " +
                                "antes de certificar capacidad de pago.");
                    }
                    conclusiones.add("Alternativa " + e.get("plazoMeses") + " meses: " +
                            "cuota mensual equivalente " + cop(e.get("cuotaMensualEquivalente")) +
                            ", TOPE proyectado " +
                            (e.get("topeProyectado") == null ? "sin validar" :
                                    e.get("topeProyectado") + "%") +
                            ", estado numerico preliminar " + e.get("estadoNumericoPreliminar") + ".");
                    if (((BigDecimal)e.get("plazoMeses")).compareTo(new BigDecimal("24")) > 0) {
                        conclusiones.add("La alternativa de " + e.get("plazoMeses") +
                                " meses excede el plazo general reglamentario de 24 meses.");
                    }
                }
            }
            conclusiones.add("Las dos quincenas no acreditan recurrencia futura. " +
                    "Los resultados no aprueban ni rechazan una solicitud.");
            conclusiones.add("Los valores de amortizacion llegaron desde el navegador y " +
                    "no fueron recalculados en este endpoint; confirmar contra el simulador servidor.");
            analisis.put("conclusiones", conclusiones);

            String respuestaIA = "";
            String advertenciaIA = "";
            try {
                respuestaIA = agenteIAService.interpretarEscenarios(tipo, estudio, analisis);
            } catch (Exception e) {
                advertenciaIA = "El analisis numerico esta disponible; " +
                        "la explicacion generativa no pudo completarse.";
            }
            Map<String, Object> respuesta = new LinkedHashMap<>();
            respuesta.put("status", 200);
            respuesta.put("numeroSolicitud", numeroSolicitud);
            respuesta.put("analisis", analisis);
            respuesta.put("respuestaIA", respuestaIA);
            respuesta.put("advertenciaIA", advertenciaIA);
            respuesta.put("decisionAutomatica", false);
            respuesta.put("modificaSolicitud", false);
            return ResponseEntity.ok(respuesta);
        } catch (IllegalArgumentException ex) {
            return error(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            return error(HttpStatus.SERVICE_UNAVAILABLE,
                    "No fue posible interpretar los escenarios financieros.");
        }
    }

    private void agregarHallazgos(Map<String, Object> e, List<String> conclusiones) {
        if (((BigDecimal)e.get("plazoMeses")).compareTo(new BigDecimal("24")) > 0) {
            conclusiones.add("El plazo excede el maximo general de 24 meses (articulo 10).");
        }
        if (e.get("topeActual") == null || e.get("topeProyectado") == null) {
            conclusiones.add("No se puede certificar TOPE actual o proyectado: " +
                    "faltan deducciones mensuales validadas y/o salario confirmado.");
        } else {
            conclusiones.add("TOPE actual " + e.get("topeActual") + "%; " +
                    "TOPE proyectado " + e.get("topeProyectado") +
                    "%; limite general de descuentos 50% (articulo 5).");
        }
        Object fallos = e.get("incumplimientos");
        if (fallos instanceof List && !((List<?>)fallos).isEmpty()) {
            conclusiones.add("Controles numericos observados: " + fallos.toString());
        }
        Object pendientes = e.get("pendientes");
        if (pendientes instanceof List && !((List<?>)pendientes).isEmpty()) {
            conclusiones.add("Validaciones pendientes: " + pendientes.toString());
        }
    }

    private String cop(Object valor) {
        return "$" + ((BigDecimal)valor).setScale(2, RoundingMode.HALF_UP).toPlainString() + " COP";
    }

    private int pagosPorAnio(String frecuencia) {
        switch (frecuencia) {
            case "QUINCENAL": return 24;
            case "MENSUAL": return 12;
            case "BIMESTRAL": return 6;
            case "TRIMESTRAL": return 4;
            case "CUATRIMESTRAL": return 3;
            case "SEMESTRAL": return 2;
            case "ANUAL": return 1;
            default: return 0;
        }
    }

    private BigDecimal decimalRequerido(Object valor, boolean positivo, String campo) {
        if (valor == null || valor instanceof String) {
            throw new IllegalArgumentException("Valor numerico invalido: " + campo);
        }
        try {
            BigDecimal n = new BigDecimal(valor.toString());
            if (n.abs().compareTo(new BigDecimal("100000000000000")) > 0 ||
                    (positivo ? n.signum() <= 0 : n.signum() < 0)) {
                throw new IllegalArgumentException("Rango invalido: " + campo);
            }
            return n;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Valor numerico invalido: " + campo);
        }
    }

    private Integer enteroPositivo(Object valor, int maximo) {
        try {
            int n = new BigDecimal(String.valueOf(valor)).intValueExact();
            if (n <= 0 || n > maximo) throw new IllegalArgumentException("Entero fuera de rango.");
            return n;
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new IllegalArgumentException("Entero invalido.");
        }
    }

    private BigDecimal opcional(Object valor) {
        if (valor == null) return null;
        try { return new BigDecimal(valor.toString()); }
        catch (Exception ex) { return null; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapa(Object origen) {
        return origen instanceof Map ? (Map<String, Object>)origen : new LinkedHashMap<>();
    }

    private String normalizarUsuario(String nombre) {
        if (nombre == null) return null;
        String usuario = nombre.trim().toLowerCase(Locale.ROOT);
        return usuario.matches("[a-z0-9._-]{2,60}") ? usuario : null;
    }

    private ResponseEntity<Map<String, Object>> error(
            HttpStatus estado, String mensaje) {
        Map<String, Object> resultado = new LinkedHashMap<String, Object>();
        resultado.put("status", estado.value());
        resultado.put("error", mensaje);
        return ResponseEntity.status(estado).body(resultado);
    }
}
