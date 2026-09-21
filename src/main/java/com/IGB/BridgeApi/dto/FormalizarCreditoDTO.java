package com.IGB.BridgeApi.dto;

import java.math.BigDecimal;

public class FormalizarCreditoDTO {

    private BigDecimal montoAprobado;
    private Integer plazoAprobado;
    private Integer numeroCuotas;
    private BigDecimal valorCuota;
    private String fechaPrimeraCuota;
    private String periodicidad;
    private String observacion;
    private String usuario;

    public FormalizarCreditoDTO() {
    }

    public BigDecimal getMontoAprobado() {
        return montoAprobado;
    }

    public void setMontoAprobado(BigDecimal montoAprobado) {
        this.montoAprobado = montoAprobado;
    }

    public Integer getPlazoAprobado() {
        return plazoAprobado;
    }

    public void setPlazoAprobado(Integer plazoAprobado) {
        this.plazoAprobado = plazoAprobado;
    }

    public Integer getNumeroCuotas() {
        return numeroCuotas;
    }

    public void setNumeroCuotas(Integer numeroCuotas) {
        this.numeroCuotas = numeroCuotas;
    }

    public BigDecimal getValorCuota() {
        return valorCuota;
    }

    public void setValorCuota(BigDecimal valorCuota) {
        this.valorCuota = valorCuota;
    }

    public String getFechaPrimeraCuota() {
        return fechaPrimeraCuota;
    }

    public void setFechaPrimeraCuota(String fechaPrimeraCuota) {
        this.fechaPrimeraCuota = fechaPrimeraCuota;
    }

    public String getPeriodicidad() {
        return periodicidad;
    }

    public void setPeriodicidad(String periodicidad) {
        this.periodicidad = periodicidad;
    }

    public String getObservacion() {
        return observacion;
    }

    public void setObservacion(String observacion) {
        this.observacion = observacion;
    }

    public String getUsuario() {
        return usuario;
    }

    public void setUsuario(String usuario) {
        this.usuario = usuario;
    }
}
