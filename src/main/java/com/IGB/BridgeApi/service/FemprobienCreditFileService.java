package com.IGB.BridgeApi.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
public class FemprobienCreditFileService {

    private static final long MAX_FILE_SIZE =
            3L * 1024L * 1024L;

    private static final int MAX_IMAGE_WIDTH = 5000;
    private static final int MAX_IMAGE_HEIGHT = 5000;

    private static final int FIRMA_MIN_WIDTH = 500;
    private static final int FIRMA_MIN_HEIGHT = 150;

    private static final int HUELLA_MIN_WIDTH = 300;
    private static final int HUELLA_MIN_HEIGHT = 300;

    private static final int FIRMA_OUTPUT_WIDTH = 900;
    private static final int FIRMA_OUTPUT_HEIGHT = 300;

    private static final int HUELLA_OUTPUT_WIDTH = 600;
    private static final int HUELLA_OUTPUT_HEIGHT = 600;

    private static final DateTimeFormatter FILE_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    @Value("${femprobien.creditos.archivos}")
    private String basePath;

    public String guardarImagen(
            Integer numeroSolicitud,
            MultipartFile archivo,
            String tipoArchivo) throws IOException {

        if (
                archivo == null ||
                        archivo.isEmpty()
        ) {

            return null;
        }

        if (
                numeroSolicitud == null ||
                        numeroSolicitud <= 0
        ) {

            throw new IllegalArgumentException(
                    "Número de solicitud inválido."
            );
        }

        validarTipoLogico(
                tipoArchivo
        );

        byte[] contenido =
                archivo.getBytes();

        validarTamano(
                contenido
        );

        detectarExtension(
                contenido
        );

        BufferedImage imagen =
                leerImagenReal(
                        contenido
                );

        validarDimensionesMaximas(
                imagen
        );

        try {

            validarCalidadBiometrica(
                    imagen,
                    tipoArchivo
            );

        } catch (IllegalArgumentException e) {

            throw new IllegalArgumentException(
                    nombreTipoArchivo(
                            tipoArchivo
                    ) +
                            ": " +
                            e.getMessage(),
                    e
            );
        }


        BufferedImage imagenProcesada;

        if (
                esHuella(
                        tipoArchivo
                )
        ) {

            imagenProcesada =
                    normalizarHuella(
                            imagen
                    );

        } else {

            imagenProcesada =
                    normalizarFirma(
                            imagen
                    );
        }

        byte[] contenidoProcesado =
                convertirPng(
                        imagenProcesada
                );

        validarTamano(
                contenidoProcesado
        );

        Path carpetaBase =
                obtenerCarpetaBase();

        Path carpetaSolicitud =
                carpetaBase
                        .resolve(
                                numeroSolicitud.toString()
                        )
                        .normalize();

        if (
                !carpetaSolicitud.startsWith(
                        carpetaBase
                )
        ) {

            throw new IllegalArgumentException(
                    "Ruta de almacenamiento inválida."
            );
        }

        Files.createDirectories(
                carpetaSolicitud
        );

        String nombreArchivo =
                tipoArchivo +
                        "_" +
                        LocalDateTime.now().format(FILE_DATE_FORMAT) +
                        "_" +
                        UUID.randomUUID().toString().substring(0, 8) +
                        ".png";

        Path destino =
                carpetaSolicitud
                        .resolve(
                                nombreArchivo
                        )
                        .normalize();

        if (
                !destino.startsWith(
                        carpetaSolicitud
                )
        ) {

            throw new IllegalArgumentException(
                    "Ruta de archivo inválida."
            );
        }

        Files.write(
                destino,
                contenidoProcesado,
                StandardOpenOption.CREATE_NEW
        );

        return numeroSolicitud +
                "/" +
                nombreArchivo;
    }

    public void eliminarArchivoRelativo(
            String rutaRelativa) {

        if (
                rutaRelativa == null ||
                        rutaRelativa.trim().isEmpty()
        ) {

            return;
        }

        try {

            Path carpetaBase =
                    obtenerCarpetaBase();

            Path archivo =
                    carpetaBase
                            .resolve(
                                    rutaRelativa
                            )
                            .normalize();

            if (
                    !archivo.startsWith(
                            carpetaBase
                    )
            ) {

                return;
            }

            Files.deleteIfExists(
                    archivo
            );

        } catch (
                Exception ignored
        ) {

        }
    }

    public Path resolverArchivoRelativo(
            String rutaRelativa) throws IOException {

        if (
                rutaRelativa == null ||
                        rutaRelativa.trim().isEmpty()
        ) {

            return null;
        }

        Path carpetaBase =
                obtenerCarpetaBase();

        Path archivo =
                carpetaBase
                        .resolve(
                                rutaRelativa.trim()
                        )
                        .normalize();

        if (
                !archivo.startsWith(
                        carpetaBase
                )
        ) {

            throw new IllegalArgumentException(
                    "La ruta del archivo biométrico es inválida."
            );
        }

        if (
                !Files.exists(
                        archivo
                ) ||
                        !Files.isRegularFile(
                                archivo
                        )
        ) {

            return null;
        }

        return archivo;
    }

    private Path obtenerCarpetaBase()
            throws IOException {

        if (
                basePath == null ||
                        basePath.trim().isEmpty()
        ) {

            throw new IllegalStateException(
                    "No está configurada la propiedad femprobien.creditos.archivos."
            );
        }

        Path carpetaBase =
                Paths
                        .get(
                                basePath.trim()
                        )
                        .toAbsolutePath()
                        .normalize();

        Files.createDirectories(
                carpetaBase
        );

        return carpetaBase;
    }

    private void validarTamano(
            byte[] contenido) {

        if (
                contenido == null ||
                        contenido.length == 0
        ) {

            throw new IllegalArgumentException(
                    "El archivo está vacío."
            );
        }

        if (
                contenido.length >
                        MAX_FILE_SIZE
        ) {

            throw new IllegalArgumentException(
                    "Cada archivo de firma o huella puede pesar máximo 3 MB."
            );
        }
    }


    private void validarTipoLogico(
            String tipoArchivo) {

        if (
                tipoArchivo == null
        ) {

            throw new IllegalArgumentException(
                    "Tipo de archivo inválido."
            );
        }

        boolean permitido =
                "firma_solicitante".equals(tipoArchivo) ||
                        "huella_solicitante".equals(tipoArchivo) ||
                        "firma_deudor1".equals(tipoArchivo) ||
                        "huella_deudor1".equals(tipoArchivo) ||
                        "firma_deudor2".equals(tipoArchivo) ||
                        "huella_deudor2".equals(tipoArchivo);

        if (
                !permitido
        ) {

            throw new IllegalArgumentException(
                    "Tipo de archivo no permitido."
            );
        }
    }


    private String nombreTipoArchivo(
            String tipoArchivo) {

        if (
                "firma_solicitante".equals(
                        tipoArchivo
                )
        ) {

            return "Firma del solicitante";
        }

        if (
                "huella_solicitante".equals(
                        tipoArchivo
                )
        ) {

            return "Huella del solicitante";
        }

        if (
                "firma_deudor1".equals(
                        tipoArchivo
                )
        ) {

            return "Firma del deudor solidario 1";
        }

        if (
                "huella_deudor1".equals(
                        tipoArchivo
                )
        ) {

            return "Huella del deudor solidario 1";
        }

        if (
                "firma_deudor2".equals(
                        tipoArchivo
                )
        ) {

            return "Firma del deudor solidario 2";
        }

        if (
                "huella_deudor2".equals(
                        tipoArchivo
                )
        ) {

            return "Huella del deudor solidario 2";
        }

        return "Archivo biométrico";
    }


    private boolean esHuella(
            String tipoArchivo) {

        return tipoArchivo != null &&
                tipoArchivo.startsWith(
                        "huella_"
                );
    }

    private void validarCalidadBiometrica(
            BufferedImage imagen,
            String tipoArchivo) {

        BufferedImage sobreBlanco =
                crearSobreFondoBlanco(
                        imagen
                );

        if (
                esHuella(
                        tipoArchivo
                )
        ) {

            validarHuella(
                    sobreBlanco
            );

        } else {

            validarFirma(
                    sobreBlanco
            );
        }
    }

    private void validarFirma(
            BufferedImage imagen) {

        int ancho =
                imagen.getWidth();

        int alto =
                imagen.getHeight();


        if (
                ancho < FIRMA_MIN_WIDTH ||
                        alto < FIRMA_MIN_HEIGHT
        ) {

            throw new IllegalArgumentException(
                    "La firma tiene una resolución muy baja. " +
                            "Debe tener mínimo " +
                            FIRMA_MIN_WIDTH +
                            " x " +
                            FIRMA_MIN_HEIGHT +
                            " píxeles."
            );
        }


        double relacion =
                ancho /
                        (double) alto;


        if (
                relacion < 1.80 ||
                        relacion > 4.50
        ) {

            throw new IllegalArgumentException(
                    "La firma debe venir recortada en formato horizontal."
            );
        }


        MetricasImagen metricas =
                calcularMetricas(
                        imagen
                );


        int luminanciaFondo =
                estimarLuminanciaFondo(
                        imagen
                );


        int umbralTinta =
                Math.max(
                        30,
                        Math.min(
                                235,
                                luminanciaFondo -
                                        25
                        )
                );


        MetricasTinta metricasTinta =
                calcularMetricasTinta(
                        imagen,
                        umbralTinta
                );


        System.out.println(
                "[FEMPROBIEN-BIOMETRIA] FIRMA " +
                        "promedio=" +
                        metricas.luminanciaPromedio +
                        ", desviacion=" +
                        metricas.desviacionLuminancia +
                        ", gradiente=" +
                        metricas.gradientePromedio +
                        ", fondo=" +
                        luminanciaFondo +
                        ", umbralTinta=" +
                        umbralTinta +
                        ", porcentajeTinta=" +
                        metricasTinta.porcentaje +
                        ", anchoTinta=" +
                        metricasTinta.proporcionAncho +
                        ", altoTinta=" +
                        metricasTinta.proporcionAlto
        );


        if (
                metricas.luminanciaPromedio < 45
        ) {

            throw new IllegalArgumentException(
                    "La imagen de la firma está demasiado oscura. " +
                            "Toma nuevamente la fotografía con mejor iluminación."
            );
        }


        if (
                metricas.luminanciaPromedio > 254
        ) {

            throw new IllegalArgumentException(
                    "La imagen de la firma está demasiado clara o parece vacía."
            );
        }


        if (
                metricas.desviacionLuminancia < 5
        ) {

            throw new IllegalArgumentException(
                    "La firma no tiene suficiente contraste. " +
                            "Usa tinta oscura y procura una iluminación uniforme."
            );
        }


        if (
                metricasTinta.porcentaje < 0.001
        ) {

            throw new IllegalArgumentException(
                    "No se detectó correctamente el trazo de la firma. " +
                            "Ajusta el recorte para que la firma quede dentro del marco."
            );
        }


        if (
                metricasTinta.porcentaje > 0.75
        ) {

            throw new IllegalArgumentException(
                    "La firma tiene demasiado fondo oscuro. " +
                            "Vuelve a tomarla con mejor iluminación o ajusta el recorte."
            );
        }


        if (
                metricasTinta.proporcionAncho < 0.08 ||
                        metricasTinta.proporcionAlto < 0.04
        ) {

            throw new IllegalArgumentException(
                    "El trazo de la firma ocupa un área demasiado pequeña. " +
                            "Ajusta el recorte dejando únicamente la firma."
            );
        }


        if (
                metricas.gradientePromedio < 0.35
        ) {

            throw new IllegalArgumentException(
                    "La imagen de la firma parece demasiado desenfocada. " +
                            "Toma nuevamente la fotografía procurando enfocar el trazo."
            );
        }
    }


    private void validarHuella(
            BufferedImage imagen) {

        int ancho =
                imagen.getWidth();

        int alto =
                imagen.getHeight();


        if (
                ancho < HUELLA_MIN_WIDTH ||
                        alto < HUELLA_MIN_HEIGHT
        ) {

            throw new IllegalArgumentException(
                    "La huella tiene una resolución muy baja. " +
                            "Debe tener mínimo " +
                            HUELLA_MIN_WIDTH +
                            " x " +
                            HUELLA_MIN_HEIGHT +
                            " píxeles."
            );
        }


        double relacion =
                ancho /
                        (double) alto;


        if (
                relacion < 0.80 ||
                        relacion > 1.25
        ) {

            throw new IllegalArgumentException(
                    "La huella debe venir recortada en formato cuadrado."
            );
        }


        MetricasImagen metricas =
                calcularMetricas(
                        imagen
                );


        int luminanciaFondo =
                estimarLuminanciaFondo(
                        imagen
                );


        int umbralHuella =
                Math.max(
                        30,
                        Math.min(
                                235,
                                luminanciaFondo -
                                        22
                        )
                );


        MetricasTinta metricasHuella =
                calcularMetricasTinta(
                        imagen,
                        umbralHuella
                );


        System.out.println(
                "[FEMPROBIEN-BIOMETRIA] HUELLA " +
                        "promedio=" +
                        metricas.luminanciaPromedio +
                        ", desviacion=" +
                        metricas.desviacionLuminancia +
                        ", gradiente=" +
                        metricas.gradientePromedio +
                        ", fondo=" +
                        luminanciaFondo +
                        ", umbralHuella=" +
                        umbralHuella +
                        ", porcentajeDetalle=" +
                        metricasHuella.porcentaje +
                        ", anchoDetalle=" +
                        metricasHuella.proporcionAncho +
                        ", altoDetalle=" +
                        metricasHuella.proporcionAlto
        );


        if (
                metricas.luminanciaPromedio < 40
        ) {

            throw new IllegalArgumentException(
                    "La fotografía de la huella está demasiado oscura. " +
                            "Tómala nuevamente con mejor iluminación."
            );
        }


        if (
                metricas.luminanciaPromedio > 253.5
        ) {

            throw new IllegalArgumentException(
                    "La fotografía de la huella está demasiado clara o parece vacía."
            );
        }


        if (
                metricas.desviacionLuminancia < 7
        ) {

            throw new IllegalArgumentException(
                    "La huella no tiene suficiente contraste. " +
                            "Toma nuevamente la fotografía procurando que se distingan las crestas."
            );
        }


        if (
                metricasHuella.porcentaje < 0.008
        ) {

            throw new IllegalArgumentException(
                    "No se distingue correctamente la huella. " +
                            "Ajusta el recorte para que la huella ocupe la mayor parte del marco."
            );
        }


        if (
                metricasHuella.porcentaje > 0.82
        ) {

            throw new IllegalArgumentException(
                    "La huella tiene demasiado fondo oscuro. " +
                            "Toma nuevamente la fotografía con mejor iluminación."
            );
        }


        if (
                metricasHuella.proporcionAncho < 0.15 ||
                        metricasHuella.proporcionAlto < 0.20
        ) {

            throw new IllegalArgumentException(
                    "La huella ocupa un área demasiado pequeña. " +
                            "Acerca el recorte alrededor de la huella."
            );
        }


        if (
                metricas.gradientePromedio < 0.80
        ) {

            throw new IllegalArgumentException(
                    "La fotografía de la huella parece demasiado desenfocada. " +
                            "Toma nuevamente la fotografía procurando que las líneas de la huella sean visibles."
            );
        }
    }


    private int estimarLuminanciaFondo(
            BufferedImage imagen) {

        int[] histograma =
                new int[256];


        int ancho =
                imagen.getWidth();

        int alto =
                imagen.getHeight();


        int paso =
                Math.max(
                        1,
                        Math.min(
                                ancho,
                                alto
                        ) /
                                700
                );


        long total =
                0;


        for (
                int y = 0;
                y < alto;
                y += paso
        ) {

            for (
                    int x = 0;
                    x < ancho;
                    x += paso
            ) {

                int gris =
                        luminancia(
                                imagen.getRGB(
                                        x,
                                        y
                                )
                        );


                histograma[
                        Math.max(
                                0,
                                Math.min(
                                        255,
                                        gris
                                )
                        )
                        ]++;


                total++;
            }
        }


        if (
                total <= 0
        ) {

            return 255;
        }


        long objetivo =
                (long) Math.ceil(
                        total *
                                0.90
                );


        long acumulado =
                0;


        for (
                int gris = 0;
                gris <= 255;
                gris++
        ) {

            acumulado +=
                    histograma[gris];


            if (
                    acumulado >= objetivo
            ) {

                return gris;
            }
        }


        return 255;
    }


    private MetricasTinta calcularMetricasTinta(
            BufferedImage imagen,
            int umbral) {

        int ancho =
                imagen.getWidth();

        int alto =
                imagen.getHeight();


        long totalPixeles =
                (long) ancho *
                        (long) alto;


        long pixelesTinta =
                0;


        int minX =
                ancho;

        int minY =
                alto;

        int maxX =
                -1;

        int maxY =
                -1;


        for (
                int y = 0;
                y < alto;
                y++
        ) {

            for (
                    int x = 0;
                    x < ancho;
                    x++
            ) {

                int gris =
                        luminancia(
                                imagen.getRGB(
                                        x,
                                        y
                                )
                        );


                if (
                        gris <= umbral
                ) {

                    pixelesTinta++;


                    if (
                            x < minX
                    ) {

                        minX =
                                x;
                    }


                    if (
                            y < minY
                    ) {

                        minY =
                                y;
                    }


                    if (
                            x > maxX
                    ) {

                        maxX =
                                x;
                    }


                    if (
                            y > maxY
                    ) {

                        maxY =
                                y;
                    }
                }
            }
        }


        double porcentaje =
                totalPixeles <= 0
                        ? 0
                        : pixelesTinta /
                        (double) totalPixeles;


        double proporcionAncho =
                0;


        double proporcionAlto =
                0;


        if (
                maxX >= minX &&
                        maxY >= minY
        ) {

            proporcionAncho =
                    (
                            maxX -
                                    minX +
                                    1
                    ) /
                            (double) ancho;


            proporcionAlto =
                    (
                            maxY -
                                    minY +
                                    1
                    ) /
                            (double) alto;
        }


        return new MetricasTinta(
                porcentaje,
                proporcionAncho,
                proporcionAlto
        );
    }


    private MetricasImagen calcularMetricas(
            BufferedImage imagen) {

        int ancho =
                imagen.getWidth();

        int alto =
                imagen.getHeight();

        int paso =
                Math.max(
                        1,
                        Math.min(
                                ancho,
                                alto
                        ) /
                                700
                );

        long cantidad =
                0;

        double suma =
                0;

        double sumaCuadrados =
                0;

        long pixelesOscuros =
                0;

        double sumaGradiente =
                0;

        long cantidadGradientes =
                0;

        for (
                int y = 0;
                y < alto;
                y += paso
        ) {

            for (
                    int x = 0;
                    x < ancho;
                    x += paso
            ) {

                int gris =
                        luminancia(
                                imagen.getRGB(
                                        x,
                                        y
                                )
                        );

                cantidad++;

                suma +=
                        gris;

                sumaCuadrados +=
                        gris *
                                gris;

                if (
                        gris < 205
                ) {

                    pixelesOscuros++;
                }

                if (
                        x + paso <
                                ancho
                ) {

                    int derecha =
                            luminancia(
                                    imagen.getRGB(
                                            x + paso,
                                            y
                                    )
                            );

                    sumaGradiente +=
                            Math.abs(
                                    gris -
                                            derecha
                            );

                    cantidadGradientes++;
                }

                if (
                        y + paso <
                                alto
                ) {

                    int abajo =
                            luminancia(
                                    imagen.getRGB(
                                            x,
                                            y + paso
                                    )
                            );

                    sumaGradiente +=
                            Math.abs(
                                    gris -
                                            abajo
                            );

                    cantidadGradientes++;
                }
            }
        }

        double promedio =
                cantidad == 0
                        ? 0
                        : suma /
                        cantidad;

        double varianza =
                cantidad == 0
                        ? 0
                        : (
                        sumaCuadrados /
                                cantidad
                ) -
                        (
                                promedio *
                                        promedio
                        );

        if (
                varianza < 0
        ) {

            varianza =
                    0;
        }

        double desviacion =
                Math.sqrt(
                        varianza
                );

        double porcentajeOscuro =
                cantidad == 0
                        ? 0
                        : pixelesOscuros /
                        (double) cantidad;

        double gradientePromedio =
                cantidadGradientes == 0
                        ? 0
                        : sumaGradiente /
                        cantidadGradientes;

        return new MetricasImagen(
                promedio,
                desviacion,
                porcentajeOscuro,
                gradientePromedio
        );
    }


    private int luminancia(
            int argb) {

        Color color =
                new Color(
                        argb,
                        true
                );

        return (int) Math.round(
                0.299 *
                        color.getRed() +
                        0.587 *
                                color.getGreen() +
                        0.114 *
                                color.getBlue()
        );
    }


    private BufferedImage normalizarFirma(
            BufferedImage original) {

        BufferedImage base =
                crearSobreFondoBlanco(
                        original
                );

        return ajustarEnLienzo(
                base,
                FIRMA_OUTPUT_WIDTH,
                FIRMA_OUTPUT_HEIGHT,
                10
        );
    }


    private BufferedImage normalizarHuella(
            BufferedImage original) {

        BufferedImage base =
                crearSobreFondoBlanco(
                        original
                );

        BufferedImage gris =
                convertirHuellaEscalaGrises(
                        base
                );

        return ajustarEnLienzo(
                gris,
                HUELLA_OUTPUT_WIDTH,
                HUELLA_OUTPUT_HEIGHT,
                10
        );
    }


    private BufferedImage crearSobreFondoBlanco(
            BufferedImage original) {

        BufferedImage resultado =
                new BufferedImage(
                        original.getWidth(),
                        original.getHeight(),
                        BufferedImage.TYPE_INT_RGB
                );

        Graphics2D graphics =
                resultado.createGraphics();

        try {

            graphics.setColor(
                    Color.WHITE
            );

            graphics.fillRect(
                    0,
                    0,
                    resultado.getWidth(),
                    resultado.getHeight()
            );

            graphics.drawImage(
                    original,
                    0,
                    0,
                    null
            );

        } finally {

            graphics.dispose();
        }

        return resultado;
    }


    private BufferedImage ajustarEnLienzo(
            BufferedImage original,
            int anchoDestino,
            int altoDestino,
            int margen) {

        BufferedImage resultado =
                new BufferedImage(
                        anchoDestino,
                        altoDestino,
                        BufferedImage.TYPE_INT_RGB
                );

        Graphics2D graphics =
                resultado.createGraphics();

        try {

            graphics.setColor(
                    Color.WHITE
            );

            graphics.fillRect(
                    0,
                    0,
                    anchoDestino,
                    altoDestino
            );

            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC
            );

            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY
            );

            graphics.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );

            int anchoDisponible =
                    anchoDestino -
                            (
                                    margen *
                                            2
                            );

            int altoDisponible =
                    altoDestino -
                            (
                                    margen *
                                            2
                            );

            double escala =
                    Math.min(
                            anchoDisponible /
                                    (double) original.getWidth(),
                            altoDisponible /
                                    (double) original.getHeight()
                    );

            int anchoFinal =
                    Math.max(
                            1,
                            (int) Math.round(
                                    original.getWidth() *
                                            escala
                            )
                    );

            int altoFinal =
                    Math.max(
                            1,
                            (int) Math.round(
                                    original.getHeight() *
                                            escala
                            )
                    );

            int x =
                    (
                            anchoDestino -
                                    anchoFinal
                    ) /
                            2;

            int y =
                    (
                            altoDestino -
                                    altoFinal
                    ) /
                            2;

            graphics.drawImage(
                    original,
                    x,
                    y,
                    anchoFinal,
                    altoFinal,
                    null
            );

        } finally {

            graphics.dispose();
        }

        return resultado;
    }


    private BufferedImage convertirHuellaEscalaGrises(
            BufferedImage original) {

        BufferedImage resultado =
                new BufferedImage(
                        original.getWidth(),
                        original.getHeight(),
                        BufferedImage.TYPE_INT_RGB
                );

        final double contraste =
                1.12;

        for (
                int y = 0;
                y < original.getHeight();
                y++
        ) {

            for (
                    int x = 0;
                    x < original.getWidth();
                    x++
            ) {

                int gris =
                        luminancia(
                                original.getRGB(
                                        x,
                                        y
                                )
                        );

                int ajustado =
                        (int) Math.round(
                                (
                                        gris -
                                                128
                                ) *
                                        contraste +
                                        128
                        );

                ajustado =
                        Math.max(
                                0,
                                Math.min(
                                        255,
                                        ajustado
                                )
                        );

                int rgb =
                        new Color(
                                ajustado,
                                ajustado,
                                ajustado
                        )
                                .getRGB();

                resultado.setRGB(
                        x,
                        y,
                        rgb
                );
            }
        }

        return resultado;
    }


    private byte[] convertirPng(
            BufferedImage imagen)
            throws IOException {

        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        boolean guardado =
                ImageIO.write(
                        imagen,
                        "png",
                        output
                );

        if (
                !guardado
        ) {

            throw new IOException(
                    "No fue posible convertir la imagen biométrica a PNG."
            );
        }

        return output.toByteArray();
    }


    private String detectarExtension(
            byte[] contenido) {

        if (
                contenido.length >= 8 &&
                        (contenido[0] & 0xFF) == 0x89 &&
                        contenido[1] == 0x50 &&
                        contenido[2] == 0x4E &&
                        contenido[3] == 0x47 &&
                        contenido[4] == 0x0D &&
                        contenido[5] == 0x0A &&
                        contenido[6] == 0x1A &&
                        contenido[7] == 0x0A
        ) {

            return "png";
        }

        if (
                contenido.length >= 3 &&
                        (contenido[0] & 0xFF) == 0xFF &&
                        (contenido[1] & 0xFF) == 0xD8 &&
                        (contenido[2] & 0xFF) == 0xFF
        ) {

            return "jpg";
        }

        throw new IllegalArgumentException(
                "Solo se permiten imágenes PNG, JPG o JPEG reales."
        );
    }


    private BufferedImage leerImagenReal(
            byte[] contenido)
            throws IOException {

        BufferedImage imagen =
                ImageIO.read(
                        new ByteArrayInputStream(
                                contenido
                        )
                );

        if (
                imagen == null
        ) {

            throw new IllegalArgumentException(
                    "El archivo no contiene una imagen válida."
            );
        }

        if (
                imagen.getWidth() <= 0 ||
                        imagen.getHeight() <= 0
        ) {

            throw new IllegalArgumentException(
                    "La imagen tiene dimensiones inválidas."
            );
        }

        return imagen;
    }


    private void validarDimensionesMaximas(
            BufferedImage imagen) {

        if (
                imagen.getWidth() >
                        MAX_IMAGE_WIDTH ||
                        imagen.getHeight() >
                                MAX_IMAGE_HEIGHT
        ) {

            throw new IllegalArgumentException(
                    "La imagen no puede superar 5000 x 5000 píxeles."
            );
        }
    }


    private static class MetricasTinta {

        private final double porcentaje;
        private final double proporcionAncho;
        private final double proporcionAlto;


        private MetricasTinta(
                double porcentaje,
                double proporcionAncho,
                double proporcionAlto) {

            this.porcentaje =
                    porcentaje;

            this.proporcionAncho =
                    proporcionAncho;

            this.proporcionAlto =
                    proporcionAlto;
        }
    }


    private static class MetricasImagen {

        private final double luminanciaPromedio;
        private final double desviacionLuminancia;
        private final double porcentajeOscuro;
        private final double gradientePromedio;

        private MetricasImagen(
                double luminanciaPromedio,
                double desviacionLuminancia,
                double porcentajeOscuro,
                double gradientePromedio) {

            this.luminanciaPromedio =
                    luminanciaPromedio;

            this.desviacionLuminancia =
                    desviacionLuminancia;

            this.porcentajeOscuro =
                    porcentajeOscuro;

            this.gradientePromedio =
                    gradientePromedio;
        }
    }
}
