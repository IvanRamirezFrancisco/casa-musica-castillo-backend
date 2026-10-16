package com.security.dto.admin.analytics.clustering;

import java.util.List;

public class ScalerConfig {
    private String tipo;
    private List<Double> media;
    private List<Double> escala;
    private List<Double> varianza;

    public String getTipo() { return tipo; }
    public void setTipo(String tipo) { this.tipo = tipo; }

    public List<Double> getMedia() { return media; }
    public void setMedia(List<Double> media) { this.media = media; }

    public List<Double> getEscala() { return escala; }
    public void setEscala(List<Double> escala) { this.escala = escala; }

    public List<Double> getVarianza() { return varianza; }
    public void setVarianza(List<Double> varianza) { this.varianza = varianza; }
}
