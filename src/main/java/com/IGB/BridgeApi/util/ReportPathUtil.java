package com.IGB.BridgeApi.util;

import java.io.File;
import java.io.IOException;

public final class ReportPathUtil {

    private static final String WINDOWS_WILDFLY =
            "C:\\wildfly-10.1.0.Final";

    private static final String LINUX_WILDFLY =
            "/home/administrator/wildfly-10.1.0.Final";

    private ReportPathUtil() {
    }

    public static String getWildflyBasePath() {

        String osName =
                System.getProperty("os.name");

        if (osName != null &&
                osName.toLowerCase().contains("win")) {

            return WINDOWS_WILDFLY;
        }

        return LINUX_WILDFLY;
    }

    public static String getAccountStatementJrxml() {

        return getWildflyBasePath()
                + File.separator
                + "standalone"
                + File.separator
                + "jasper"
                + File.separator
                + "FEMPROBN_NOVAWEB"
                + File.separator
                + "employee"
                + File.separator
                + "accountStatement.jrxml";
    }

    public static String getAccountStatementJasper() {

        return getWildflyBasePath()
                + File.separator
                + "standalone"
                + File.separator
                + "jasper"
                + File.separator
                + "FEMPROBN_NOVAWEB"
                + File.separator
                + "employee"
                + File.separator
                + "accountStatement.jasper";
    }

    public static File getAccountStatementDirectory()
            throws IOException {

        File directory = new File(
                getWildflyBasePath()
                        + File.separator
                        + "standalone"
                        + File.separator
                        + "deployments"
                        + File.separator
                        + "shared.war"
                        + File.separator
                        + "FEMPROBN_NOVAWEB"
                        + File.separator
                        + "accountStatement"
        );

        if (!directory.exists()) {

            boolean created =
                    directory.mkdirs();

            if (!created &&
                    !directory.exists()) {

                throw new IOException(
                        "No fue posible crear el directorio: "
                                + directory.getAbsolutePath()
                );
            }
        }

        if (!directory.isDirectory()) {

            throw new IOException(
                    "La ruta no corresponde a un directorio: "
                            + directory.getAbsolutePath()
            );
        }

        return directory;
    }

    public static String getAccountStatementPdf(
            String codAsp)
            throws IOException {

        File directory =
                getAccountStatementDirectory();

        File pdf =
                new File(
                        directory,
                        codAsp + ".pdf"
                );

        return pdf.getAbsolutePath();
    }
}
