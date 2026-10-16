package com.security.service;

import com.security.search.NormalizedSearchQuery;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.text.Normalizer;
import java.util.*;

/**
 * Servicio centralizado para normalizar términos de búsqueda en el backend.
 * Asegura insensibilidad a mayúsculas/minúsculas, acentos, y mapea sinónimos/errores comunes.
 */
@Service
public class SearchNormalizationService {

    private static final Map<String, String> SYNONYMS = new HashMap<>();

    static {
        // Plurales comunes y sinónimos reales controlados
        SYNONYMS.put("jaranas", "jarana");
        SYNONYMS.put("guitarras", "guitarra");
        SYNONYMS.put("microfonos", "microfono");
        SYNONYMS.put("bajos", "bajo");
        SYNONYMS.put("baterias", "bateria");
        SYNONYMS.put("teclados", "teclado");
        SYNONYMS.put("violines", "violin");
        SYNONYMS.put("amplificadores", "amplificador");
        SYNONYMS.put("pedales", "pedal");
        SYNONYMS.put("trompetas", "trompeta");
        SYNONYMS.put("saxofones", "saxofon");
        SYNONYMS.put("flautas", "flauta");
        SYNONYMS.put("ukuleles", "ukulele");
        SYNONYMS.put("ukeleles", "ukulele");
        SYNONYMS.put("ukelele", "ukulele");
        SYNONYMS.put("quintas", "quinta");
        SYNONYMS.put("huapangueras", "huapanguera");
        SYNONYMS.put("cuerdas", "cuerda");
    }

    /**
     * Construye un objeto estructurado de búsqueda, manteniendo original, normalizado y variantes.
     */
    public NormalizedSearchQuery buildSearchQuery(String originalKeyword) {
        if (!StringUtils.hasText(originalKeyword)) {
            return new NormalizedSearchQuery(originalKeyword, "", "", List.of());
        }

        // 1. Limpieza base para normalización (trimming y lowercase)
        String cleaned = originalKeyword.trim().replaceAll("\\s+", " ");
        if (cleaned.length() > 100) {
            cleaned = cleaned.substring(0, 100);
        }
        cleaned = cleaned.toLowerCase(Locale.ROOT);
        
        // 2. Remoción de acentos/diacríticos
        String normalizedOriginal = Normalizer.normalize(cleaned, Normalizer.Form.NFD);
        normalizedOriginal = normalizedOriginal.replaceAll("\\p{M}", "");

        // 3. Obtener el canonical aplicando sinónimos palabra por palabra
        String[] words = normalizedOriginal.split(" ");
        for (int i = 0; i < words.length; i++) {
            if (SYNONYMS.containsKey(words[i])) {
                words[i] = SYNONYMS.get(words[i]);
            }
        }
        String canonical = String.join(" ", words);

        // 4. Construcción de variantes expandidas
        Set<String> variantsSet = new LinkedHashSet<>();
        variantsSet.add(normalizedOriginal);
        variantsSet.add(canonical);

        return new NormalizedSearchQuery(
                originalKeyword,
                normalizedOriginal,
                canonical,
                new ArrayList<>(variantsSet)
        );
    }

    /**
     * @deprecated Mantenido por compatibilidad. Use buildSearchQuery().
     */
    @Deprecated
    public String normalizeForSearch(String keyword) {
        return buildSearchQuery(keyword).canonical();
    }

    /**
     * Escapa caracteres especiales utilizados por el operador LIKE en SQL,
     * previniendo que '%' o '_' causen escaneos imprevistos.
     */
    public String escapeForLike(String normalizedKeyword) {
        if (normalizedKeyword == null) return null;
        return normalizedKeyword
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
