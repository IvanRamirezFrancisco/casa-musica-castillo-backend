package com.security.search;

import java.util.List;

/**
 * Objeto interno para modelar la intención y normalización de una búsqueda.
 * No se expone al cliente.
 */
public record NormalizedSearchQuery(
        String original,
        String normalizedOriginal,
        String canonical,
        List<String> variants
) {
}
