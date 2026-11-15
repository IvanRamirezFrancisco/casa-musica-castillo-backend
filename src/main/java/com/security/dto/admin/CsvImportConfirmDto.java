package com.security.dto.admin;

import com.security.enums.CollisionRule;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Solicitud de confirmación de importación CSV.
 *
 * <p>
 * Después de que el usuario revisa la previsualización, envía esta solicitud
 * indicando qué filas desea importar y la regla de colisión.
 * </p>
 *
 * @param selectedRows Números de fila a importar (1-based, referenciados del
 *                     preview)
 * @param rule         Regla de colisión para SKUs existentes: UPDATE o SKIP
 */
public record CsvImportConfirmDto(
        @NotEmpty(message = "Debe seleccionar al menos una fila") List<Integer> selectedRows,

        @NotNull(message = "Debe indicar la regla de colisión") CollisionRule rule) {
}
