package com.security.dto.alexa;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlexaProductSearchResponseDTO {
    private String query;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private List<AlexaProductSummaryDTO> products;
}
