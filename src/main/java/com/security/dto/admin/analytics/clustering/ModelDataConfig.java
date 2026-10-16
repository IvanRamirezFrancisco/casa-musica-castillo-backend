package com.security.dto.admin.analytics.clustering;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class ModelDataConfig {
    @JsonProperty("centroides_escalados")
    private List<List<Double>> centroidesEscalados;

    public List<List<Double>> getCentroidesEscalados() { return centroidesEscalados; }
    public void setCentroidesEscalados(List<List<Double>> centroidesEscalados) { this.centroidesEscalados = centroidesEscalados; }
}
