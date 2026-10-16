package com.security.dto.admin.analytics;

public class PurchaseClusteringModelStatusDTO {
    private String versionEsquema;
    private String versionModelo;
    private String fechaGeneracionUtc;
    private String algoritmoNombre;
    private Integer numeroClusters;
    private Integer numeroCaracteristicas;
    private String unidadAnalisis;

    public String getVersionEsquema() { return versionEsquema; }
    public void setVersionEsquema(String versionEsquema) { this.versionEsquema = versionEsquema; }

    public String getVersionModelo() { return versionModelo; }
    public void setVersionModelo(String versionModelo) { this.versionModelo = versionModelo; }

    public String getFechaGeneracionUtc() { return fechaGeneracionUtc; }
    public void setFechaGeneracionUtc(String fechaGeneracionUtc) { this.fechaGeneracionUtc = fechaGeneracionUtc; }

    public String getAlgoritmoNombre() { return algoritmoNombre; }
    public void setAlgoritmoNombre(String algoritmoNombre) { this.algoritmoNombre = algoritmoNombre; }

    public Integer getNumeroClusters() { return numeroClusters; }
    public void setNumeroClusters(Integer numeroClusters) { this.numeroClusters = numeroClusters; }

    public Integer getNumeroCaracteristicas() { return numeroCaracteristicas; }
    public void setNumeroCaracteristicas(Integer numeroCaracteristicas) { this.numeroCaracteristicas = numeroCaracteristicas; }

    public String getUnidadAnalisis() { return unidadAnalisis; }
    public void setUnidadAnalisis(String unidadAnalisis) { this.unidadAnalisis = unidadAnalisis; }
}
