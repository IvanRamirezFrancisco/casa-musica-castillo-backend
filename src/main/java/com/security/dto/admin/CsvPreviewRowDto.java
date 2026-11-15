package com.security.dto.admin;

import java.util.List;
import java.util.Map;

/**
 * Fila individual de la vista previa de importación CSV.
 *
 * @param rowNumber Número de fila en el archivo original (1-based, sin contar
 *                  cabecera)
 * @param cells     Mapa de columna → valor parseado (ej. {"SKU":
 *                  "GEN-AMP-0001", "Nombre": "Guitarra"})
 * @param valid     {@code true} si la fila pasó todas las validaciones
 * @param errors    Lista de errores encontrados en la fila (vacía si es válida)
 */
public record CsvPreviewRowDto(
        int rowNumber,
        Map<String, String> cells,
        boolean valid,
        List<String> errors) {
}
