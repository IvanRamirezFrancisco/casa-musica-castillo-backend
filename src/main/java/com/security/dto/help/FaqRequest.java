package com.security.dto.help;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class FaqRequest {
    @NotNull(message = "La categoría es requerida")
    private Long categoryId;

    @NotBlank(message = "La pregunta es requerida")
    @Size(max = 300, message = "La pregunta no puede exceder 300 caracteres")
    private String question;

    @NotBlank(message = "La respuesta es requerida")
    private String answer;

    @Min(value = 0, message = "El orden no puede ser negativo")
    private Integer displayOrder;

    private Boolean active;
    
    private Boolean featured;
}
