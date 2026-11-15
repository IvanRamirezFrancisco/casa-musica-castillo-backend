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
public class FaqDto {
    private Long id;
    private Long categoryId;
    private String categoryName;
    private String question;
    private String answer;
    private Integer displayOrder;
    private Boolean active;
    private Boolean featured;
    
    // Solo Admin
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
