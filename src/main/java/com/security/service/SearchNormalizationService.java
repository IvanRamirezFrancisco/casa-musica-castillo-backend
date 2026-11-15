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
    private static final Map<String, String> TYPO_CORRECTIONS = new HashMap<>();
    private static final List<String> DOMAIN_VOCABULARY = List.of(
            "guitarra", "acustica", "clasica", "electrica", "electroacustica",
            "requinto", "jarana", "huapanguera", "quinta", "ukulele", "violin",
            "bajo", "bateria", "teclado", "piano", "amplificador", "microfono",
            "pedal", "trompeta", "saxofon", "flauta", "armonica", "acordeon",
            "cuerda", "cuerdas", "capotraste", "afinador", "estuche", "funda",
            "atril", "baquetas", "cable", "interfaz", "consola", "bocina",
            "yamaha", "fender", "shure", "ibanez", "casio", "roland", "gibson",
            "epiphone", "squier", "marshall", "boss", "cort", "takamine",
            "daddario", "ernie", "hohner", "pearl", "tama", "zildjian", "remo",
            "valenciana", "paracho", "castillo"
    );

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
        SYNONYMS.put("acusticas", "acustica");
        SYNONYMS.put("acusticos", "acustico");
        SYNONYMS.put("electricas", "electrica");
        SYNONYMS.put("electricos", "electrico");
        SYNONYMS.put("clasicas", "clasica");
        SYNONYMS.put("clasicos", "clasico");

        // Errores leves de escritura comunes en tienda de música
        TYPO_CORRECTIONS.put("guitara", "guitarra");
        TYPO_CORRECTIONS.put("gitarra", "guitarra");
        TYPO_CORRECTIONS.put("gutarra", "guitarra");
        TYPO_CORRECTIONS.put("guiatarra", "guitarra");
        TYPO_CORRECTIONS.put("guitaras", "guitarra");
        TYPO_CORRECTIONS.put("acustca", "acustica");
        TYPO_CORRECTIONS.put("acustco", "acustica");
        TYPO_CORRECTIONS.put("akustica", "acustica");
        TYPO_CORRECTIONS.put("ascustica", "acustica");
        TYPO_CORRECTIONS.put("yamha", "yamaha");
        TYPO_CORRECTIONS.put("yamaa", "yamaha");
        TYPO_CORRECTIONS.put("yamah", "yamaha");
        TYPO_CORRECTIONS.put("yahama", "yamaha");
        TYPO_CORRECTIONS.put("microfno", "microfono");
        TYPO_CORRECTIONS.put("micrfono", "microfono");
        TYPO_CORRECTIONS.put("microphono", "microfono");
        TYPO_CORRECTIONS.put("eletrica", "electrica");
        TYPO_CORRECTIONS.put("eletrico", "electrico");
        TYPO_CORRECTIONS.put("elctrica", "electrica");
        TYPO_CORRECTIONS.put("amplficador", "amplificador");
        TYPO_CORRECTIONS.put("ampificador", "amplificador");
        TYPO_CORRECTIONS.put("trompta", "trompeta");
        TYPO_CORRECTIONS.put("saxfon", "saxofon");
        TYPO_CORRECTIONS.put("violn", "violin");
        TYPO_CORRECTIONS.put("violen", "violin");
        TYPO_CORRECTIONS.put("tecldo", "teclado");
        TYPO_CORRECTIONS.put("batria", "bateria");
        TYPO_CORRECTIONS.put("fendr", "fender");
        TYPO_CORRECTIONS.put("shur", "shure");
        TYPO_CORRECTIONS.put("ibanz", "ibanez");
        TYPO_CORRECTIONS.put("ibañez", "ibanez");
    }

    /**
     * Construye un objeto estructurado de búsqueda, manteniendo original, normalizado,
     * forma canónica corregida, variantes y tokens individuales.
     */
    public NormalizedSearchQuery buildSearchQuery(String originalKeyword) {
        if (!StringUtils.hasText(originalKeyword)) {
            return new NormalizedSearchQuery(originalKeyword, "", "", List.of(), List.of(), false);
        }

        // 1. Limpieza base para normalización (remover controles, trimming y lowercase)
        String cleaned = originalKeyword
                .replaceAll("[\\p{Cntrl}]", " ")
                .trim()
                .replaceAll("\\s+", " ");
        if (cleaned.length() > 100) {
            cleaned = cleaned.substring(0, 100).trim();
        }
        if (cleaned.isEmpty()) {
            return new NormalizedSearchQuery(originalKeyword, "", "", List.of(), List.of(), false);
        }
        cleaned = cleaned.toLowerCase(Locale.ROOT);

        // 2. Remoción de acentos/diacríticos
        String normalizedOriginal = Normalizer.normalize(cleaned, Normalizer.Form.NFD);
        normalizedOriginal = normalizedOriginal.replaceAll("\\p{M}", "");

        // 3. Obtener el canonical aplicando corrección tipográfica y sinónimos palabra por palabra
        String[] words = normalizedOriginal.split(" ");
        boolean typoCorrected = false;
        List<String> tokens = new ArrayList<>();

        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (TYPO_CORRECTIONS.containsKey(w)) {
                w = TYPO_CORRECTIONS.get(w);
                typoCorrected = true;
            } else if (SYNONYMS.containsKey(w)) {
                w = SYNONYMS.get(w);
            } else if (w.length() >= 5) {
                String fuzzyMatch = findClosestVocabularyWord(w);
                if (fuzzyMatch != null && !fuzzyMatch.equals(w)) {
                    w = fuzzyMatch;
                    typoCorrected = true;
                }
            }
            words[i] = w;
            if (w.length() >= 2 && tokens.size() < 5 && !tokens.contains(w)) {
                tokens.add(w);
            }
        }
        String canonical = String.join(" ", words);

        // 4. Construcción de variantes expandidas
        Set<String> variantsSet = new LinkedHashSet<>();
        variantsSet.add(normalizedOriginal);
        variantsSet.add(canonical);
        variantsSet.addAll(tokens);

        return new NormalizedSearchQuery(
                originalKeyword,
                normalizedOriginal,
                canonical,
                new ArrayList<>(variantsSet),
                tokens,
                typoCorrected
        );
    }

    /**
     * Busca una palabra del vocabulario musical con distancia de edición <= 1
     * (o <= 2 para palabras de 8+ letras) cuando el usuario comete un error tipográfico leve.
     */
    private String findClosestVocabularyWord(String token) {
        if (DOMAIN_VOCABULARY.contains(token)) {
            return token;
        }
        int maxDist = token.length() >= 8 ? 2 : 1;
        String best = null;
        int bestDist = maxDist + 1;
        for (String candidate : DOMAIN_VOCABULARY) {
            if (Math.abs(candidate.length() - token.length()) > maxDist) {
                continue;
            }
            // Mismo carácter inicial para evitar falsos positivos agresivos
            if (candidate.charAt(0) != token.charAt(0)) {
                continue;
            }
            int dist = levenshteinDistance(token, candidate);
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        return best;
    }

    private int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }
        return dp[a.length()][b.length()];
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
