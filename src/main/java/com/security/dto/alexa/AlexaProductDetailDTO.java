package com.security.dto.alexa;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class AlexaProductDetailDTO extends AlexaProductSummaryDTO {
    private String description;
    private String productUrl;

    public AlexaProductDetailDTO(Long id, String name, String brand, String category, BigDecimal price, String currency,
                                 String availabilityStatus, boolean available, String shortDescription, String imageUrl,
                                 boolean offer, String description, String productUrl) {
        super(id, name, brand, category, price, currency, availabilityStatus, available, shortDescription, imageUrl, offer);
        this.description = description;
        this.productUrl = productUrl;
    }
}
