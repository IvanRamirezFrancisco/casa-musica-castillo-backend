package com.security.dto.alexa;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlexaProductSummaryDTO {
    private Long id;
    private String name;
    private String brand;
    private String category;
    private BigDecimal price;
    private String currency;
    private String availabilityStatus; // DISPONIBLE, POCAS_UNIDADES, AGOTADO, NO_DISPONIBLE
    private boolean available;
    private String shortDescription;
    private String imageUrl;
    private boolean offer;
}
