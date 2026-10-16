package com.security.dto.admin.analytics;

import java.math.BigDecimal;
import java.util.List;

public class PurchaseOrderPredictionDTO {
    private Long orderId;
    private String orderNumber;
    private String modelVersion;
    private String algorithm;
    private Short predictedCluster;
    private String clusterName;
    private String clusterDescription;
    private String recommendedAction;
    private Double distanceToCentroid;
    private List<Double> distancesToAllCentroids;
    private List<Double> featureValues;
    private Short storedCluster;
    private Boolean matchesStoredAssignment;
    private String assignmentMethod = "JSON_CENTROID_DISTANCE";

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public String getOrderNumber() { return orderNumber; }
    public void setOrderNumber(String orderNumber) { this.orderNumber = orderNumber; }

    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }

    public String getAlgorithm() { return algorithm; }
    public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }

    public Short getPredictedCluster() { return predictedCluster; }
    public void setPredictedCluster(Short predictedCluster) { this.predictedCluster = predictedCluster; }

    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }

    public String getClusterDescription() { return clusterDescription; }
    public void setClusterDescription(String clusterDescription) { this.clusterDescription = clusterDescription; }

    public String getRecommendedAction() { return recommendedAction; }
    public void setRecommendedAction(String recommendedAction) { this.recommendedAction = recommendedAction; }

    public Double getDistanceToCentroid() { return distanceToCentroid; }
    public void setDistanceToCentroid(Double distanceToCentroid) { this.distanceToCentroid = distanceToCentroid; }

    public List<Double> getDistancesToAllCentroids() { return distancesToAllCentroids; }
    public void setDistancesToAllCentroids(List<Double> distancesToAllCentroids) { this.distancesToAllCentroids = distancesToAllCentroids; }

    public List<Double> getFeatureValues() { return featureValues; }
    public void setFeatureValues(List<Double> featureValues) { this.featureValues = featureValues; }

    public Short getStoredCluster() { return storedCluster; }
    public void setStoredCluster(Short storedCluster) { this.storedCluster = storedCluster; }

    public Boolean getMatchesStoredAssignment() { return matchesStoredAssignment; }
    public void setMatchesStoredAssignment(Boolean matchesStoredAssignment) { this.matchesStoredAssignment = matchesStoredAssignment; }

    public String getAssignmentMethod() { return assignmentMethod; }
    public void setAssignmentMethod(String assignmentMethod) { this.assignmentMethod = assignmentMethod; }
}
