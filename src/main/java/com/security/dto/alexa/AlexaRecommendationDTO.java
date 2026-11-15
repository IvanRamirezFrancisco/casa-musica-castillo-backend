package com.security.dto.alexa;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlexaRecommendationDTO {
    private String category;
    private String level;
    private Integer budget;
    private String message;
    private List<AlexaProductSummaryDTO> products;
}
