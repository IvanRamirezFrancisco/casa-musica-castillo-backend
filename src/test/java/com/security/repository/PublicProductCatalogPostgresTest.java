package com.security.repository;

import com.security.entity.Brand;
import com.security.entity.Category;
import com.security.entity.Product;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:55448/catalog_search_test",
        "spring.datasource.username=catalog_search_test",
        "spring.datasource.password=${CMC_CATALOG_TEST_PASSWORD}",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:catalog-search-test-schema.sql",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "logging.level.org.springframework.data.jpa.repository.query=INFO"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "CMC_CATALOG_TEST_PASSWORD", matches = ".+")
@ExtendWith(OutputCaptureExtension.class)
class PublicProductCatalogPostgresTest {

    @Autowired
    private PublicProductRepository repository;
    @Autowired
    private TestEntityManager entities;

    @Test
    void ranksAndCountsOnlyMatchingActiveProductsAcrossPages(CapturedOutput output) {
        var category = category("Cuerdas");
        var otherCategory = category("Otros");
        var brand = brand("Marca de prueba");
        var otherBrand = brand("Otra marca");

        product("Instrumento por SKU", "guitarra", null, category, brand, true);
        product("Guitárra", "NAME", null, category, brand, true);
        product("Instrumento azul", "DESCRIPTION", "Incluye guitarra", category, brand, true);
        product("Guitarra sin publicar", "INACTIVE", null, category, brand, false);
        product("Guitarra de otra categoría", "OTHER-CATEGORY", null, otherCategory, brand, true);
        product("Guitarra de otra marca", "OTHER-BRAND", null, category, otherBrand, true);
        product("Teclado", "UNMATCHED", null, category, brand, true);
        entities.flush();
        entities.clear();

        var first = repository.findCatalogWithKeyword("guitarra", "guitarra", "guitarra", "guitarra",
                "guitarra", null, null, category.getId(), brand.getId(), category.getId(), brand.getId(),
                PageRequest.of(0, 2));
        var last = repository.findCatalogWithKeyword("guitarra", "guitarra", "guitarra", "guitarra",
                "guitarra", null, null, category.getId(), brand.getId(), category.getId(), brand.getId(),
                PageRequest.of(1, 2));
        var beyond = repository.findCatalogWithKeyword("guitarra", "guitarra", "guitarra", "guitarra",
                "guitarra", null, null, category.getId(), brand.getId(), category.getId(), brand.getId(),
                PageRequest.of(2, 2));
        var multiToken = repository.findCatalogWithKeyword("guitarra azul", "guitarra azul",
                "guitarra azul", "guitarra azul", "guitarra", "azul", null, null, null,
                category.getId(), brand.getId(), PageRequest.of(0, 1));

        assertThat(first.getContent()).extracting(Product::getSku).containsExactly("guitarra", "NAME");
        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(last.getContent()).extracting(Product::getSku).containsExactly("DESCRIPTION");
        assertThat(last.getTotalElements()).isEqualTo(3);
        assertThat(last.isLast()).isTrue();
        assertThat(beyond.getContent()).isEmpty();
        assertThat(beyond.getTotalElements()).isEqualTo(3);
        assertThat(multiToken.getContent()).extracting(Product::getSku).containsExactly("DESCRIPTION");
        assertThat(multiToken.getTotalElements()).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("UnknownParameterException", "Silently ignoring");
    }

    @Test
    void searchesComposedAndDecomposedAccentsWithoutUnaccentExtension() {
        assertThat(entities.getEntityManager().createNativeQuery(
                "SELECT count(*) FROM pg_extension WHERE extname = 'unaccent'").getSingleResult()).isEqualTo(0L);
        var category = category("Música");
        var brand = brand("Prueba");
        product("PIANÓ ELÉCTRICO", "COMPOSED", null, category, brand, true);
        product("Piano ele\u0301ctrico", "DECOMPOSED", null, category, brand, true);
        product("Piano acústico", "UNMATCHED", null, category, brand, true);
        entities.flush();
        entities.clear();

        var result = repository.findCatalogWithKeyword("piano electrico", "piano electrico",
                "piano electrico", "piano electrico", "piano", "electrico", null, null, null,
                category.getId(), brand.getId(), PageRequest.of(0, 10));
        assertThat(result.getContent()).extracting(Product::getSku)
                .containsExactlyInAnyOrder("COMPOSED", "DECOMPOSED");
        assertThat(result.getTotalElements()).isEqualTo(2);

        var empty = repository.findCatalogWithKeyword("trombon", "trombon", "trombon", "trombon",
                "trombon", null, null, null, null, category.getId(), brand.getId(), PageRequest.of(0, 10));
        assertThat(empty.getContent()).isEmpty();
        assertThat(empty.getTotalElements()).isZero();
    }

    private Category category(String name) {
        var category = new Category();
        category.setName(name);
        return entities.persist(category);
    }

    private Brand brand(String name) {
        var brand = new Brand();
        brand.setName(name);
        return entities.persist(brand);
    }

    private void product(String name, String sku, String description, Category category, Brand brand, boolean active) {
        var product = new Product();
        product.setName(name);
        product.setSku(sku);
        product.setDescription(description);
        product.setCategory(category);
        product.setBrand(brand);
        product.setActive(active);
        product.setStock(1);
        product.setPrice(BigDecimal.TEN);
        entities.persist(product);
    }
}
