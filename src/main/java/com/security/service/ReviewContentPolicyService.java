package com.security.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;

/**
 * Servicio para evaluación y moderación de contenido de reseñas.
 * Aplica normalización Unicode, checks de spam, evasión y lenguaje ofensivo.
 */
@Service
@Slf4j
public class ReviewContentPolicyService {

    @Value("${app.security.reviews.moderation.enabled:true}")
    private boolean moderationEnabled;

    @Value("${app.security.reviews.moderation.max-links:2}")
    private int maxLinks;

    @Value("${app.security.reviews.moderation.repeated-char-threshold:5}")
    private int repeatedCharThreshold;

    @Value("${app.security.reviews.moderation.suspicious-to-pending:true}")
    private boolean suspiciousToPending;

    // Patrón simple para encontrar URLs (http/https/www)
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)\\b(https?://|www\\.)\\S+\\b");
    
    // Palabras graves que justifican rechazo directo (blacklist controlada)
    // Se utiliza para detectar groserías inaceptables
    private static final Set<String> SEVERE_PROFANITY = Set.of(
            "idiota", "imbecil", "estupido", "basura", "mierda"
    );

    public enum PolicyResult {
        ALLOW,
        REVIEW_REQUIRED,
        REJECT
    }
    
    public static class EvaluationResult {
        private final PolicyResult result;
        private final String normalizedText;
        private final String reason;

        public EvaluationResult(PolicyResult result, String normalizedText, String reason) {
            this.result = result;
            this.normalizedText = normalizedText;
            this.reason = reason;
        }

        public PolicyResult getResult() { return result; }
        public String getNormalizedText() { return normalizedText; }
        public String getReason() { return reason; }
    }

    /**
     * Evalúa el título y el comentario para aplicar políticas de contenido.
     */
    public EvaluationResult evaluate(String title, String comment) {
        if (!moderationEnabled) {
            return new EvaluationResult(PolicyResult.ALLOW, comment, "Moderation disabled");
        }

        // 1. Normalización
        String combinedText = (title != null ? title + " " : "") + comment;
        String normalized = normalizeText(combinedText);
        String justCommentNormalized = normalizeText(comment);

        // 2. Revisión de links (SPAM)
        int linkCount = countLinks(normalized);
        if (linkCount > maxLinks) {
            log.warn("Revisión requerida por exceso de links ({}).", linkCount);
            return new EvaluationResult(suspiciousToPending ? PolicyResult.REVIEW_REQUIRED : PolicyResult.REJECT, 
                                        justCommentNormalized, "Too many links");
        }

        // 3. Profanity Check (Simple y Conservador)
        String lowercaseNormalized = normalized.toLowerCase();
        for (String word : SEVERE_PROFANITY) {
            // Evaluamos con word boundaries para evitar atrapar subcadenas inofensivas ("basurero" != "basura")
            if (lowercaseNormalized.matches(".*\\b" + word + "\\b.*")) {
                log.warn("Rechazo automático por lenguaje ofensivo detectado.");
                return new EvaluationResult(PolicyResult.REJECT, justCommentNormalized, "Profanity detected");
            }
        }

        // 4. Repeated Characters (Spam/Evasion)
        if (hasExcessiveRepetitions(normalized, repeatedCharThreshold)) {
            log.warn("Revisión requerida por repetición excesiva de caracteres.");
            return new EvaluationResult(suspiciousToPending ? PolicyResult.REVIEW_REQUIRED : PolicyResult.REJECT, 
                                        justCommentNormalized, "Excessive character repetition");
        }
        
        // 5. Demasiado corto después de normalizar
        if (justCommentNormalized.length() < 5) { // El @Size validará 10 originales, pero si se redujo mucho a basura:
             log.warn("Contenido carece de significado tras normalización.");
             return new EvaluationResult(PolicyResult.REJECT, justCommentNormalized, "Meaningless content after normalization");
        }

        return new EvaluationResult(PolicyResult.ALLOW, justCommentNormalized, "OK");
    }

    /**
     * Normaliza el texto usando NFKC, eliminando Zero-Width chars y colapsando espacios.
     */
    public String normalizeText(String input) {
        if (input == null) return "";
        // 1. Normalización Unicode NFKC (Compatibilidad - p.ej. 𝖕𝖆𝖑𝖆𝖇𝖗𝖆 -> palabra)
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKC);
        // 2. Quitar zero-width characters y format characters invisibles (Cf) usados para evasión (p.ej. p[ZWSP]u[ZWSP]t[ZWSP]a)
        normalized = normalized.replaceAll("[\\p{Cf}]", ""); 
        // 3. Colapsar espacios múltiples, tabs y saltos de línea extremos
        normalized = normalized.replaceAll("\\s+", " ").trim();
        return normalized;
    }

    private int countLinks(String text) {
        Matcher matcher = URL_PATTERN.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private boolean hasExcessiveRepetitions(String text, int threshold) {
        if (text == null || text.length() < threshold) return false;
        
        char prev = text.charAt(0);
        int count = 1;
        
        for (int i = 1; i < text.length(); i++) {
            char curr = text.charAt(i);
            // Ignoramos espacios y vocales acentuadas comunes (español) para evitar falsos positivos
            // Pero sí bloqueamos pppputo o aaaaaaa (si threshold=5)
            if (curr == prev && !Character.isWhitespace(curr)) {
                count++;
                if (count >= threshold) {
                    return true;
                }
            } else {
                count = 1;
                prev = curr;
            }
        }
        return false;
    }
}
