package com.security.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class ReviewContentPolicyServiceTest {

    private ReviewContentPolicyService policyService;

    @BeforeEach
    void setUp() {
        policyService = new ReviewContentPolicyService();
        // Configurar valores por defecto simulando @Value
        ReflectionTestUtils.setField(policyService, "moderationEnabled", true);
        ReflectionTestUtils.setField(policyService, "maxLinks", 2);
        ReflectionTestUtils.setField(policyService, "repeatedCharThreshold", 5);
        ReflectionTestUtils.setField(policyService, "suspiciousToPending", true);
    }

    @Test
    void testNormalContentIsAllowed() {
        String title = "Excelente producto";
        String comment = "Este instrumento tiene un sonido increíble. Lo recomiendo mucho para principiantes.";
        
        var result = policyService.evaluate(title, comment);
        
        assertEquals(ReviewContentPolicyService.PolicyResult.ALLOW, result.getResult());
        assertNotNull(result.getNormalizedText());
    }

    @Test
    void testProfanityIsRejected() {
        String title = "Malo";
        String comment = "Este producto es una basura, no sirve para nada.";
        
        var result = policyService.evaluate(title, comment);
        
        assertEquals(ReviewContentPolicyService.PolicyResult.REJECT, result.getResult());
        assertEquals("Profanity detected", result.getReason());
    }
    
    @Test
    void testProfanitySubstringsAreAllowed() {
        // "basurero" contiene "basura", pero al evaluar word boundaries no debería bloquearse
        String comment = "Lo tiré al basurero porque se rompió.";
        var result = policyService.evaluate(null, comment);
        
        assertEquals(ReviewContentPolicyService.PolicyResult.ALLOW, result.getResult());
    }

    @Test
    void testExcessiveRepetitionsAreFlagged() {
        String comment = "Me encantaaaaaaa este producto";
        
        var result = policyService.evaluate(null, comment);
        
        assertEquals(ReviewContentPolicyService.PolicyResult.REVIEW_REQUIRED, result.getResult());
        assertEquals("Excessive character repetition", result.getReason());
    }

    @Test
    void testSpamLinksAreFlagged() {
        String comment = "Visita https://spam.com, http://virus.com y www.malware.com";
        
        var result = policyService.evaluate(null, comment);
        
        assertEquals(ReviewContentPolicyService.PolicyResult.REVIEW_REQUIRED, result.getResult());
        assertEquals("Too many links", result.getReason());
    }

    @Test
    void testUnicodeEvasionAndZeroWidthCharsAreCleaned() {
        // p u t o separado con zero-width spaces (\u200B) e intentando evadir
        // "𝖕𝖆𝖑𝖆𝖇𝖗𝖆" = palabra en formato matemático, normalizado a "palabra"
        String title = "𝖕𝖆𝖑𝖆𝖇𝖗𝖆";
        String comment = "b\u200Ba\u200Bs\u200Bu\u200Br\u200Ba total"; // evasión de "basura"
        
        var result = policyService.evaluate(title, comment);
        
        // El zero-width space se remueve, "basura" se reconstituye y debe ser detectado
        assertEquals(ReviewContentPolicyService.PolicyResult.REJECT, result.getResult());
        assertEquals("Profanity detected", result.getReason());
    }

    @Test
    void testHtmlTagsAreNotExecutedButAllowedAsTextIfNoPolicyViolation() {
        // La política de contenido no rechaza HTML por defecto (es texto).
        // La protección XSS real ocurre en el Frontend y Output Encoding.
        String comment = "Me gusta mucho <script>alert(1)</script>";
        
        var result = policyService.evaluate(null, comment);
        
        assertEquals(ReviewContentPolicyService.PolicyResult.ALLOW, result.getResult());
    }
    
    @Test
    void testMeaninglessContentIsRejected() {
        // Contenido que al normalizarse y quitar espacios es demasiado corto
        String comment = "   \u200B   .   ";
        
        var result = policyService.evaluate(null, comment);
        
        assertEquals(ReviewContentPolicyService.PolicyResult.REJECT, result.getResult());
        assertEquals("Meaningless content after normalization", result.getReason());
    }
}
