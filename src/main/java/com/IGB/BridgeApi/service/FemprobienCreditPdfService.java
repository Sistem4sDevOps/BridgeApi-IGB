package com.IGB.BridgeApi.service;

import net.sf.jasperreports.engine.*;
import net.sf.jasperreports.engine.util.JRLoader;
import net.sf.jasperreports.engine.util.JRSaver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class FemprobienCreditPdfService {

    private final FemprobienCreditService creditService;

    /*
     * Carpeta donde están:
     *
     * solicitudCredito.jrxml
     * solicitudCredito.jasper
     * formato_credito_p1.png
     * formato_credito_p2.png
     */
    @Value("${femprobien.creditos.ireport-dir:C:/wildfly-10.1.0.Final/standalone/jasper/FEMPROBN_NOVAWEB/FEMPROBIEN/formatos/credito}")
    private String ireportDir;

    /*
     * Carpeta raíz donde se guardan las firmas y huellas.
     *
     * Ejemplo:
     * C:/wildfly-10.1.0.Final/standalone/jasper/FEMPROBN_NOVAWEB/FEMPROBIEN/creditos/3/firma_solicitante_xxx.png
     */
    @Value("${femprobien.creditos.archivos:C:/wildfly-10.1.0.Final/standalone/jasper/FEMPROBN_NOVAWEB/FEMPROBIEN/creditos}")
    private String archivosBasePath;


    /*
     * El JasperReport se mantiene en memoria después de cargarlo
     * o compilarlo por primera vez.
     *
     * Esto evita compilar el JRXML en cada petición y evita el
     * crecimiento de Metaspace que tuvimos anteriormente.
     */
    private volatile JasperReport reporteCache;


    public FemprobienCreditPdfService(
            FemprobienCreditService creditService) {

        this.creditService = creditService;
    }


    /* =========================================================
       GENERAR PDF
       ========================================================= */

    public byte[] generarFormato(
            Integer numeroSolicitud
    ) throws Exception {

        validarNumeroSolicitud(
                numeroSolicitud
        );


        /*
         * Obtiene SELECT * de tblAsociadoCredito
         * y agrega historial_estados.
         */
        Map<String, Object> solicitud =
                creditService.consultarSolicitud(
                        numeroSolicitud
                );


        if (
                solicitud == null ||
                        solicitud.isEmpty()
        ) {

            throw new IllegalArgumentException(
                    "No se encontró la solicitud de crédito #" +
                            numeroSolicitud +
                            "."
            );
        }


        Path reportDir =
                obtenerDirectorioReporte();


        /*
         * Fondos exactos de las páginas oficiales.
         */
        File fondoPagina1 =
                validarArchivo(
                        reportDir.resolve(
                                "formato_credito_p1.png"
                        ),
                        "No se encontró el fondo de la página 1."
                );


        File fondoPagina2 =
                validarArchivo(
                        reportDir.resolve(
                                "formato_credito_p2.png"
                        ),
                        "No se encontró el fondo de la página 2."
                );


        /*
         * Carga el .jasper.
         *
         * Si no existe, o el JRXML es más reciente,
         * se compila UNA SOLA VEZ y se crea
         * solicitudCredito.jasper.
         */
        JasperReport report =
                obtenerReporte(
                        reportDir
                );


        /*
         * Los parámetros del JRXML utilizan los mismos nombres
         * que las columnas de tblAsociadoCredito.
         */
        Map<String, Object> parametros =
                new HashMap<String, Object>();


        parametros.putAll(
                solicitud
        );

        parametros.put(
                "fondoPagina1",
                fondoPagina1
        );


        parametros.put(
                "fondoPagina2",
                fondoPagina2
        );

        parametros.put(
                "firma_solicitante",
                prepararImagenTransparente(
                        resolverArchivoBiometrico(
                                solicitud.get(
                                        "firma_solicitante"
                                )
                        )
                )
        );

        parametros.put(
                "huella_solicitante",
                prepararImagenTransparente(
                        resolverArchivoBiometrico(
                                solicitud.get(
                                        "huella_solicitante"
                                )
                        )
                )
        );

        parametros.put(
                "firma_deudor1",
                prepararImagenTransparente(
                        resolverArchivoBiometrico(
                                solicitud.get(
                                        "firma_deudor1"
                                )
                        )
                )
        );


        parametros.put(
                "huella_deudor1",
                prepararImagenTransparente(
                        resolverArchivoBiometrico(
                                solicitud.get(
                                        "huella_deudor1"
                                )
                        )
                )
        );

        parametros.put(
                "firma_deudor2",
                prepararImagenTransparente(
                        resolverArchivoBiometrico(
                                solicitud.get(
                                        "firma_deudor2"
                                )
                        )
                )
        );


        parametros.put(
                "huella_deudor2",
                prepararImagenTransparente(
                        resolverArchivoBiometrico(
                                solicitud.get(
                                        "huella_deudor2"
                                )
                        )
                )
        );

        parametros.put(
                "firma_gerente",
                resolverArchivoBiometrico(
                        solicitud.get(
                                "firma_gerente"
                        )
                )
        );


        parametros.put(
                "firma_comite_credito",
                resolverArchivoBiometrico(
                        solicitud.get(
                                "firma_comite_credito"
                        )
                )
        );

        JasperPrint jasperPrint =
                JasperFillManager.fillReport(
                        report,
                        parametros,
                        new JREmptyDataSource(
                                2
                        )
                );


        return JasperExportManager
                .exportReportToPdf(
                        jasperPrint
                );
    }

    private JasperReport obtenerReporte(
            Path reportDir
    ) throws Exception {

        if (
                reporteCache != null
        ) {

            return reporteCache;
        }


        synchronized (
                this
        ) {

            if (
                    reporteCache != null
            ) {

                return reporteCache;
            }


            Path archivoJrxml =
                    reportDir.resolve(
                            "solicitudCredito.jrxml"
                    );


            Path archivoJasper =
                    reportDir.resolve(
                            "solicitudCredito.jasper"
                    );


            boolean existeJrxml =
                    Files.exists(
                            archivoJrxml
                    ) &&
                            Files.isRegularFile(
                                    archivoJrxml
                            );


            boolean existeJasper =
                    Files.exists(
                            archivoJasper
                    ) &&
                            Files.isRegularFile(
                                    archivoJasper
                            );


            if (
                    existeJasper &&
                            (
                                    !existeJrxml ||
                                            jasperEstaActualizado(
                                                    archivoJrxml,
                                                    archivoJasper
                                            )
                            )
            ) {

                System.out.println(
                        "[FEMPROBIEN-PDF] Cargando reporte compilado: " +
                                archivoJasper
                                        .toAbsolutePath()
                                        .toString()
                );


                reporteCache =
                        (JasperReport) JRLoader.loadObject(
                                archivoJasper.toFile()
                        );


                return reporteCache;
            }


            if (
                    existeJrxml
            ) {

                if (
                        !existeJasper
                ) {

                    System.out.println(
                            "[FEMPROBIEN-PDF] No existe solicitudCredito.jasper."
                    );

                } else {

                    System.out.println(
                            "[FEMPROBIEN-PDF] solicitudCredito.jrxml es más reciente " +
                                    "que solicitudCredito.jasper."
                    );
                }


                System.out.println(
                        "[FEMPROBIEN-PDF] Compilando JRXML: " +
                                archivoJrxml
                                        .toAbsolutePath()
                                        .toString()
                );


                JasperReport reporteCompilado =
                        JasperCompileManager.compileReport(
                                archivoJrxml.toString()
                        );


                JRSaver.saveObject(
                        reporteCompilado,
                        archivoJasper.toFile()
                );


                System.out.println(
                        "[FEMPROBIEN-PDF] Reporte compilado guardado en: " +
                                archivoJasper
                                        .toAbsolutePath()
                                        .toString()
                );


                reporteCache =
                        reporteCompilado;


                return reporteCache;
            }

            throw new IllegalStateException(
                    "No se encontró solicitudCredito.jrxml ni " +
                            "solicitudCredito.jasper en: " +
                            reportDir
                                    .toAbsolutePath()
                                    .toString()
            );
        }
    }


    private boolean jasperEstaActualizado(
            Path jrxml,
            Path jasper
    ) {

        try {

            FileTime fechaJrxml =
                    Files.getLastModifiedTime(
                            jrxml
                    );


            FileTime fechaJasper =
                    Files.getLastModifiedTime(
                            jasper
                    );


            return fechaJasper
                    .compareTo(
                            fechaJrxml
                    ) >= 0;


        } catch (
                Exception e
        ) {

            return true;
        }
    }


    private void validarNumeroSolicitud(
            Integer numeroSolicitud
    ) {

        if (
                numeroSolicitud == null ||
                        numeroSolicitud <= 0
        ) {

            throw new IllegalArgumentException(
                    "Número de solicitud inválido."
            );
        }
    }


    private Path obtenerDirectorioReporte() {

        if (
                ireportDir == null ||
                        ireportDir
                                .trim()
                                .isEmpty()
        ) {

            throw new IllegalStateException(
                    "No está configurada la propiedad " +
                            "femprobien.creditos.ireport-dir."
            );
        }


        Path directory =
                Paths.get(
                                ireportDir.trim()
                        )
                        .toAbsolutePath()
                        .normalize();


        if (
                !Files.exists(
                        directory
                ) ||
                        !Files.isDirectory(
                                directory
                        )
        ) {

            throw new IllegalStateException(
                    "No existe la carpeta de reportes FEMPROBIEN: " +
                            directory.toString()
            );
        }


        return directory;
    }


    private File validarArchivo(
            Path path,
            String error
    ) {

        if (
                path == null ||
                        !Files.exists(
                                path
                        ) ||
                        !Files.isRegularFile(
                                path
                        )
        ) {

            throw new IllegalStateException(
                    error +
                            " Ruta esperada: " +
                            String.valueOf(
                                    path
                            )
            );
        }


        return path.toFile();
    }

    private File resolverArchivoBiometrico(
            Object rutaObj
    ) {

        if (
                rutaObj == null
        ) {

            return null;
        }


        String ruta =
                rutaObj
                        .toString()
                        .trim();


        if (
                ruta.isEmpty()
        ) {

            return null;
        }


        if (
                archivosBasePath == null ||
                        archivosBasePath
                                .trim()
                                .isEmpty()
        ) {

            return null;
        }


        Path base =
                Paths.get(
                                archivosBasePath.trim()
                        )
                        .toAbsolutePath()
                        .normalize();


        Path rutaRecibida =
                Paths.get(
                        ruta
                );


        Path archivo;


        if (
                rutaRecibida.isAbsolute()
        ) {

            archivo =
                    rutaRecibida
                            .toAbsolutePath()
                            .normalize();

        } else {

            archivo =
                    base.resolve(
                                    ruta
                            )
                            .normalize();


            if (
                    !archivo.startsWith(
                            base
                    )
            ) {

                throw new IllegalArgumentException(
                        "Ruta biométrica inválida: " +
                                ruta
                );
            }
        }


        if (
                !Files.exists(
                        archivo
                ) ||
                        !Files.isRegularFile(
                                archivo
                        )
        ) {

            System.out.println(
                    "[FEMPROBIEN-PDF] Archivo biométrico no encontrado: " +
                            archivo.toString()
            );


            return null;
        }


        return archivo.toFile();
    }

    private File prepararImagenTransparente(
            File archivoOriginal
    ) {

        if (
                archivoOriginal == null ||
                        !archivoOriginal.exists() ||
                        !archivoOriginal.isFile()
        ) {

            return null;
        }


        try {

            BufferedImage original =
                    ImageIO.read(
                            archivoOriginal
                    );


            if (
                    original == null
            ) {

                return archivoOriginal;
            }


            String nombre =
                    archivoOriginal.getName();


            int punto =
                    nombre.lastIndexOf(
                            '.'
                    );


            String nombreSinExtension =
                    punto > 0
                            ? nombre.substring(
                            0,
                            punto
                    )
                            : nombre;


            File archivoSalida =
                    new File(
                            archivoOriginal.getParentFile(),
                            nombreSinExtension +
                                    "_pdf_limpio.png"
                    );


            if (
                    archivoSalida.exists() &&
                            archivoSalida.lastModified() >=
                                    archivoOriginal.lastModified()
            ) {

                return archivoSalida;
            }


            BufferedImage transparente =
                    new BufferedImage(
                            original.getWidth(),
                            original.getHeight(),
                            BufferedImage.TYPE_INT_ARGB
                    );


            int minX =
                    original.getWidth();

            int minY =
                    original.getHeight();

            int maxX =
                    -1;

            int maxY =
                    -1;


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

                    Color color =
                            new Color(
                                    original.getRGB(
                                            x,
                                            y
                                    ),
                                    true
                            );


                    int rojo =
                            color.getRed();

                    int verde =
                            color.getGreen();

                    int azul =
                            color.getBlue();


                    int luminancia =
                            (int) Math.round(
                                    0.299 * rojo +
                                            0.587 * verde +
                                            0.114 * azul
                            );


                    int alphaBase =
                            255 -
                                    luminancia;

                    if (
                            alphaBase < 48
                    ) {

                        transparente.setRGB(
                                x,
                                y,
                                0x00FFFFFF
                        );

                        continue;
                    }


                    int alpha =
                            (int) Math.min(
                                    255,
                                    (
                                            alphaBase -
                                                    48
                                    ) *
                                            1.55
                            );


                    if (
                            alpha < 22
                    ) {

                        transparente.setRGB(
                                x,
                                y,
                                0x00FFFFFF
                        );

                        continue;
                    }


                    int nuevoArgb =
                            (
                                    alpha <<
                                            24
                            ) |
                                    (
                                            rojo <<
                                                    16
                                    ) |
                                    (
                                            verde <<
                                                    8
                                    ) |
                                    azul;


                    transparente.setRGB(
                            x,
                            y,
                            nuevoArgb
                    );


                    if (
                            alpha >= 45
                    ) {

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


            BufferedImage resultado;


            if (
                    maxX >= minX &&
                            maxY >= minY
            ) {

                int paddingX =
                        Math.max(
                                2,
                                original.getWidth() /
                                        100
                        );

                int paddingY =
                        Math.max(
                                2,
                                original.getHeight() /
                                        100
                        );


                int xInicio =
                        Math.max(
                                0,
                                minX -
                                        paddingX
                        );

                int yInicio =
                        Math.max(
                                0,
                                minY -
                                        paddingY
                        );

                int xFin =
                        Math.min(
                                original.getWidth() -
                                        1,
                                maxX +
                                        paddingX
                        );

                int yFin =
                        Math.min(
                                original.getHeight() -
                                        1,
                                maxY +
                                        paddingY
                        );


                int ancho =
                        xFin -
                                xInicio +
                                1;

                int alto =
                        yFin -
                                yInicio +
                                1;


                BufferedImage recorte =
                        transparente.getSubimage(
                                xInicio,
                                yInicio,
                                ancho,
                                alto
                        );


                resultado =
                        new BufferedImage(
                                ancho,
                                alto,
                                BufferedImage.TYPE_INT_ARGB
                        );


                java.awt.Graphics2D graphics =
                        resultado.createGraphics();


                graphics.drawImage(
                        recorte,
                        0,
                        0,
                        null
                );


                graphics.dispose();

            } else {

                resultado =
                        transparente;
            }


            boolean guardado =
                    ImageIO.write(
                            resultado,
                            "png",
                            archivoSalida
                    );


            if (
                    !guardado
            ) {

                return archivoOriginal;
            }


            return archivoSalida;


        } catch (
                Exception e
        ) {

            System.out.println(
                    "[FEMPROBIEN-PDF] No fue posible limpiar imagen biométrica: " +
                            archivoOriginal.getAbsolutePath() +
                            ". Se usará la imagen original. Error: " +
                            e.getMessage()
            );


            return archivoOriginal;
        }
    }

}
