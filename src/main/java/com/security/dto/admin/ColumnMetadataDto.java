package com.security.dto.admin;

/**
 * Metadatos de una columna disponible para exportación CSV.
 *
 * @param key      Identificador interno (ej. "sku", "nombre")
 * @param label    Nombre legible para el usuario (ej. "SKU", "Nombre del
 *                 producto")
 * @param required {@code true} si la columna es obligatoria y no puede
 *                 deseleccionarse
 */
public record ColumnMetadataDto(
        String key,
        String label,
        boolean required) {
}
