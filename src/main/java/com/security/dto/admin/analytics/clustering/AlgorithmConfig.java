package com.security.dto.admin.analytics.clustering;

import com.fasterxml.jackson.annotation.JsonProperty;

public class AlgorithmConfig {
    private String nombre;
    
    @JsonProperty("numero_clusters")
    private Integer numeroClusters;
    
    @JsonProperty("random_state")
    private Integer randomState;
    
    @JsonProperty("n_init")
    private Integer nInit;

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }

    public Integer getNumeroClusters() { return numeroClusters; }
    public void setNumeroClusters(Integer numeroClusters) { this.numeroClusters = numeroClusters; }

    public Integer getRandomState() { return randomState; }
    public void setRandomState(Integer randomState) { this.randomState = randomState; }

    public Integer getNInit() { return nInit; }
    public void setNInit(Integer nInit) { this.nInit = nInit; }
}
