package com.security.dto.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Configuración de exportación CSV enviada desde el frontend.
 *
 * <p>
 * Permite al usuario seleccionar columnas, ordenamiento y límite de registros.
 * </p>
 *
 * @param columns Lista de claves de columna a incluir (ej.
 *                ["sku","nombre","precio"])
 * @param sortBy  Campo de ordenamiento (ej. "nombre", "precio", "stock",
 *                "createdAt")
 * @param sortDir Dirección de ordenamiento: "asc" o "desc"
 * @param limit   Número máximo de registros a exportar (0 = todos, máx 5000)
 */
public record ExportConfigDto(
        @NotEmpty(message = "Debe seleccionar al menos una columna") List<String> columns,

        String sortBy,

        String sortDir,

        @Min(value = 0, message = "El límite no puede ser negativo") @Max(value = 5000, message = "El límite máximo es 5000 registros") int limit) {

    /**
     * Constructor con defaults: sortBy=nombre, sortDir=asc, limit=0 (todos).
     */
    public ExportConfigDto {
        if (sortBy == null || sortBy.isBlank())
            sortBy = "nombre";
        if (sortDir == null || sortDir.isBlank())
            sortDir = "asc";
    }
}
