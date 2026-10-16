package com.security.dto.admin.analytics.clustering;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class InputConfig {
    @JsonProperty("unidad_analisis")
    private String unidadAnalisis;
    
    private List<String> caracteristicas;
    
    @JsonProperty("columnas_log1p")
    private List<String> columnasLog1p;

    public String getUnidadAnalisis() { return unidadAnalisis; }
    public void setUnidadAnalisis(String unidadAnalisis) { this.unidadAnalisis = unidadAnalisis; }

    public List<String> getCaracteristicas() { return caracteristicas; }
    public void setCaracteristicas(List<String> caracteristicas) { this.caracteristicas = caracteristicas; }

    public List<String> getColumnasLog1p() { return columnasLog1p; }
    public void setColumnasLog1p(List<String> columnasLog1p) { this.columnasLog1p = columnasLog1p; }
}
