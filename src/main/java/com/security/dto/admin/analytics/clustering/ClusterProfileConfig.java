package com.security.dto.admin.analytics.clustering;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ClusterProfileConfig {
    private Short cluster;
    private String nombre;
    private String descripcion;
    
    @JsonProperty("accion_sugerida")
    private String accionComercial;

    public Short getCluster() { return cluster; }
    public void setCluster(Short cluster) { this.cluster = cluster; }

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }

    public String getDescripcion() { return descripcion; }
    public void setDescripcion(String descripcion) { this.descripcion = descripcion; }

    public String getAccionComercial() { return accionComercial; }
    public void setAccionComercial(String accionComercial) { this.accionComercial = accionComercial; }
}
