package com.security.dto.admin.analytics.clustering;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public class ClusteringModelConfig {
    @JsonProperty("version_esquema")
    private Integer versionEsquema;

    @JsonProperty("version_modelo")
    private String versionModelo;

    @JsonProperty("fecha_generacion_utc")
    private String fechaGeneracionUtc;

    private AlgorithmConfig algoritmo;
    private InputConfig entrada;
    private ScalerConfig escalador;
    private ModelDataConfig modelo;
    @JsonProperty("perfiles_clusters")
    private Map<String, ClusterProfileConfig> perfiles;

    public Integer getVersionEsquema() { return versionEsquema; }
    public void setVersionEsquema(Integer versionEsquema) { this.versionEsquema = versionEsquema; }

    public String getVersionModelo() { return versionModelo; }
    public void setVersionModelo(String versionModelo) { this.versionModelo = versionModelo; }

    public String getFechaGeneracionUtc() { return fechaGeneracionUtc; }
    public void setFechaGeneracionUtc(String fechaGeneracionUtc) { this.fechaGeneracionUtc = fechaGeneracionUtc; }

    public AlgorithmConfig getAlgoritmo() { return algoritmo; }
    public void setAlgoritmo(AlgorithmConfig algoritmo) { this.algoritmo = algoritmo; }

    public InputConfig getEntrada() { return entrada; }
    public void setEntrada(InputConfig entrada) { this.entrada = entrada; }

    public ScalerConfig getEscalador() { return escalador; }
    public void setEscalador(ScalerConfig escalador) { this.escalador = escalador; }

    public ModelDataConfig getModelo() { return modelo; }
    public void setModelo(ModelDataConfig modelo) { this.modelo = modelo; }

    public Map<String, ClusterProfileConfig> getPerfiles() { return perfiles; }
    public void setPerfiles(Map<String, ClusterProfileConfig> perfiles) { this.perfiles = perfiles; }
}
