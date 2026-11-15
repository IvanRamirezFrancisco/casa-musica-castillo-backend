package com.security.util;

import java.text.Normalizer;
import java.util.regex.Pattern;

public class SlugUtils {

    private static final Pattern NONLATIN = Pattern.compile("[^\\w-]");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s]");
    private static final int MAX_SLUG_LENGTH = 60;

    public static String toSlug(String input) {
        if (input == null || input.trim().isEmpty()) {
            return "";
        }
        String nowhitespace = WHITESPACE.matcher(input).replaceAll("-");
        String normalized = Normalizer.normalize(nowhitespace, Normalizer.Form.NFD);
        String slug = NONLATIN.matcher(normalized).replaceAll("");
        slug = slug.toLowerCase();
        
        // Remove duplicate hyphens
        slug = slug.replaceAll("-{2,}", "-");
        
        // Remove leading and trailing hyphens
        slug = slug.replaceAll("^-|-$", "");

        if (slug.length() > MAX_SLUG_LENGTH) {
            slug = slug.substring(0, MAX_SLUG_LENGTH);
            slug = slug.replaceAll("-$", ""); // In case we truncated at a hyphen
        }

        return slug;
    }

    public static String buildProductFolder(Long productId, String productName) {
        String slug = toSlug(productName);
        if (slug.isEmpty()) {
            slug = "producto-" + productId;
        }
        return "casa-musica/productos/" + productId + "-" + slug;
    }

    public static String buildBrandFolder(Long brandId, String brandName) {
        String slug = toSlug(brandName);
        if (slug.isEmpty()) {
            slug = "marca-" + brandId;
        }
        return "casa-musica/marcas/" + brandId + "-" + slug;
    }

    public static String buildCategoryFolder(Long categoryId, String categoryName) {
        String slug = toSlug(categoryName);
        if (slug.isEmpty()) {
            slug = "categoria-" + categoryId;
        }
        return "casa-musica/categorias/" + categoryId + "-" + slug;
    }
}
