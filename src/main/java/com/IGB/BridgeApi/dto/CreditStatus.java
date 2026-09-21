package com.IGB.BridgeApi.dto;

public enum CreditStatus {
    PENDIENTE,
    EN_ESTUDIO,
    APROBADA,
    NEGADA,
    APLAZADA;

    public static CreditStatus from(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("El estado del crédito es obligatorio.");
        }

        try {
            return CreditStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Estado no válido. Estados permitidos: PENDIENTE, EN_ESTUDIO, APROBADA, NEGADA, APLAZADA."
            );
        }
    }
}
