package com.security.dto.help;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class HelpCategoryRequest {
    @NotBlank(message = "El nombre es requerido")
    @Size(max = 150, message = "El nombre no puede exceder 150 caracteres")
    private String name;

    @Size(max = 500, message = "La descripción no puede exceder 500 caracteres")
    private String description;

    @Size(max = 50, message = "El icono no puede exceder 50 caracteres")
    private String iconKey;

    @Min(value = 0, message = "El orden no puede ser negativo")
    private Integer displayOrder;

    private Boolean active;
}
