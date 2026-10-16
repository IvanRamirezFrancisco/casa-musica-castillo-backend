package com.security.service;

import com.security.search.NormalizedSearchQuery;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SearchNormalizationServiceTest {

    private final SearchNormalizationService service = new SearchNormalizationService();

    @Test
    void testExpansionGuitarras() {
        NormalizedSearchQuery query = service.buildSearchQuery("guitarras");
        assertEquals("guitarras", query.original());
        assertEquals("guitarras", query.normalizedOriginal());
        assertEquals("guitarra", query.canonical());
        
        List<String> variants = query.variants();
        assertEquals(2, variants.size());
        assertTrue(variants.contains("guitarras"));
        assertTrue(variants.contains("guitarra"));
    }

    @Test
    void testNoDuplicadosGuitarra() {
        NormalizedSearchQuery query = service.buildSearchQuery("guitarra");
        assertEquals("guitarra", query.normalizedOriginal());
        assertEquals("guitarra", query.canonical());
        
        List<String> variants = query.variants();
        assertEquals(1, variants.size()); // No debe haber duplicados
        assertEquals("guitarra", variants.get(0));
    }

    @Test
    void testAcentosYMayusculas() {
        NormalizedSearchQuery query = service.buildSearchQuery("MICRÓFONOS");
        assertEquals("microfonos", query.normalizedOriginal());
        assertEquals("microfono", query.canonical());
        assertTrue(query.variants().contains("microfonos"));
        assertTrue(query.variants().contains("microfono"));
    }

    @Test
    void testTypoYaNoEsAlias() {
        // En base a la FASE 5 del plan técnico, se removieron errores tipográficos como "microfno"
        NormalizedSearchQuery query = service.buildSearchQuery("microfno");
        assertEquals("microfno", query.canonical());
        assertEquals(1, query.variants().size());
    }

    @Test
    void testEspaciosYTrim() {
        NormalizedSearchQuery query = service.buildSearchQuery("  hola    mundo  ");
        assertEquals("hola mundo", query.normalizedOriginal());
    }

    @Test
    void testLimiteLongitud() {
        String longText = "a".repeat(150);
        NormalizedSearchQuery query = service.buildSearchQuery(longText);
        assertEquals(100, query.normalizedOriginal().length());
    }

    @Test
    void testNullYVacio() {
        NormalizedSearchQuery queryNull = service.buildSearchQuery(null);
        assertNull(queryNull.original());
        assertEquals("", queryNull.normalizedOriginal());
        assertTrue(queryNull.variants().isEmpty());
        
        NormalizedSearchQuery queryEmpty = service.buildSearchQuery("   ");
        assertEquals("", queryEmpty.normalizedOriginal());
        assertTrue(queryEmpty.variants().isEmpty());
    }
}
