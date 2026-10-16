package com.security.dto.help;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HelpCategoryDto {
    private Long id;
    private String name;
    private String slug;
    private String description;
    private String iconKey;
    private Integer displayOrder;
    private Boolean active; // Necesario para Admin (puede anularse en público si se desea)
    private Integer activeFaqCount; // Solo lo calcularemos para el DTO
    
    // Solo para Admin
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
