package com.security.service;

import com.security.dto.admin.ColumnMetadataDto;
import com.security.dto.admin.ExportConfigDto;
import com.security.entity.Product;
import com.security.entity.User;
import com.security.repository.ProductRepository;
import com.security.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Servicio de exportación a CSV para Productos y Usuarios.
 *
 * <p>
 * Solo exporta columnas útiles para un humano. Se omiten contraseñas,
 * tokens, hashes y fechas internas no relevantes.
 * </p>
 *
 * <p>
 * Formato de salida: UTF-8 con BOM (para compatibilidad con Excel en Windows),
 * valores separados por comas, cadenas con comillas dobles si contienen coma o
 * salto de línea.
 * </p>
 */
@Service
@Transactional(readOnly = true)
public class CsvExportService {

    private static final Logger log = LoggerFactory.getLogger(CsvExportService.class);

    // ── Cabeceras CSV ─────────────────────────────────────────────────────────

    private static final String PRODUCT_HEADERS = "SKU,Nombre,Categoria,Marca,Precio,Precio_Descuento,Stock,Activo,Destacado";

    private static final String USER_HEADERS = "Nombre,Apellidos,Correo,Telefono,Rol,Activo,EsCliente,TotalOrdenes";

    // ── Dependencias ──────────────────────────────────────────────────────────

    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    public CsvExportService(ProductRepository productRepository,
            UserRepository userRepository) {
        this.productRepository = productRepository;
        this.userRepository = userRepository;
    }

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Exporta todos los productos activos e inactivos a CSV.
     *
     * @return bytes del archivo CSV listo para descargar
     */
    public byte[] exportProducts() throws IOException {
        List<Product> products = productRepository.findAll();
        log.info("[CsvExport] Exportando {} productos", products.size());

        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                PrintWriter writer = new PrintWriter(
                        new OutputStreamWriter(bos, StandardCharsets.UTF_8))) {

            // BOM para Excel Windows
            bos.write(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF });

            writer.println(PRODUCT_HEADERS);
            for (Product p : products) {
                writer.println(String.join(",",
                        escape(p.getSku()),
                        escape(p.getName()),
                        escape(p.getCategory() != null ? p.getCategory().getName() : ""),
                        escape(p.getBrand() != null ? p.getBrand().getName() : ""),
                        p.getPrice() != null ? p.getPrice().toPlainString() : "",
                        p.getDiscountPrice() != null ? p.getDiscountPrice().toPlainString() : "",
                        p.getStock() != null ? String.valueOf(p.getStock()) : "0",
                        p.getActive() != null ? (p.getActive() ? "SI" : "NO") : "NO",
                        p.getFeatured() != null ? (p.getFeatured() ? "SI" : "NO") : "NO"));
            }
            writer.flush();
            return bos.toByteArray();
        }
    }

    /**
     * Exporta todos los usuarios (clientes y empleados) a CSV.
     * Se excluyen contraseñas, tokens y datos sensibles de seguridad.
     *
     * @return bytes del archivo CSV listo para descargar
     */
    public byte[] exportUsers() throws IOException {
        List<User> users = userRepository.findAll();
        log.info("[CsvExport] Exportando {} usuarios", users.size());

        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                PrintWriter writer = new PrintWriter(
                        new OutputStreamWriter(bos, StandardCharsets.UTF_8))) {

            bos.write(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF });

            writer.println(USER_HEADERS);
            for (User u : users) {
                String rol = u.getRoles() != null && !u.getRoles().isEmpty()
                        ? u.getRoles().iterator().next().getName()
                        : "SIN_ROL";

                writer.println(String.join(",",
                        escape(u.getFirstName()),
                        escape(u.getLastName()),
                        escape(u.getEmail()),
                        escape(u.getPhone() != null ? u.getPhone() : ""),
                        escape(rol),
                        u.getEnabled() != null ? (u.getEnabled() ? "SI" : "NO") : "NO",
                        u.getIsCustomer() != null ? (u.getIsCustomer() ? "SI" : "NO") : "NO",
                        u.getTotalOrders() != null ? String.valueOf(u.getTotalOrders()) : "0"));
            }
            writer.flush();
            return bos.toByteArray();
        }
    }

    // ── Utilidades ────────────────────────────────────────────────────────────

    /**
     * Escapa un valor para CSV: si contiene coma, comilla doble o salto de línea
     * lo envuelve en comillas dobles y duplica las comillas internas.
     */
    private String escape(String value) {
        if (value == null)
            return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // ── Metadatos de columnas disponibles ───────────────────────────────────

    /** Todas las columnas exportables de productos con sus metadatos */
    private static final List<ColumnMetadataDto> PRODUCT_COLUMN_METADATA = List.of(
            new ColumnMetadataDto("sku", "SKU", true),
            new ColumnMetadataDto("nombre", "Nombre", true),
            new ColumnMetadataDto("categoria", "Categoría", true),
            new ColumnMetadataDto("marca", "Marca", false),
            new ColumnMetadataDto("precio", "Precio", true),
            new ColumnMetadataDto("precio_descuento", "Precio Descuento", false),
            new ColumnMetadataDto("stock", "Stock", true),
            new ColumnMetadataDto("activo", "Activo", false),
            new ColumnMetadataDto("destacado", "Destacado", false),
            new ColumnMetadataDto("descripcion", "Descripción", false),
            new ColumnMetadataDto("descripcion_detallada", "Descripción Detallada", false),
            new ColumnMetadataDto("modelo", "Modelo", false),
            new ColumnMetadataDto("dimensiones", "Dimensiones", false),
            new ColumnMetadataDto("peso", "Peso (kg)", false),
            new ColumnMetadataDto("imagen_url", "URL Imagen", false),
            new ColumnMetadataDto("vistas", "Vistas", false),
            new ColumnMetadataDto("ventas", "Ventas", false),
            new ColumnMetadataDto("rating", "Rating Promedio", false),
            new ColumnMetadataDto("fecha_creacion", "Fecha Creación", false));

    /** Mapa key → función extractora de valor para cada columna de producto */
    private static final Map<String, Function<Product, String>> PRODUCT_EXTRACTORS = new LinkedHashMap<>();
    static {
        PRODUCT_EXTRACTORS.put("sku", p -> p.getSku());
        PRODUCT_EXTRACTORS.put("nombre", p -> p.getName());
        PRODUCT_EXTRACTORS.put("categoria", p -> p.getCategory() != null ? p.getCategory().getName() : "");
        PRODUCT_EXTRACTORS.put("marca", p -> p.getBrand() != null ? p.getBrand().getName() : "");
        PRODUCT_EXTRACTORS.put("precio", p -> p.getPrice() != null ? p.getPrice().toPlainString() : "");
        PRODUCT_EXTRACTORS.put("precio_descuento",
                p -> p.getDiscountPrice() != null ? p.getDiscountPrice().toPlainString() : "");
        PRODUCT_EXTRACTORS.put("stock", p -> p.getStock() != null ? String.valueOf(p.getStock()) : "0");
        PRODUCT_EXTRACTORS.put("activo", p -> p.getActive() != null ? (p.getActive() ? "SI" : "NO") : "NO");
        PRODUCT_EXTRACTORS.put("destacado", p -> p.getFeatured() != null ? (p.getFeatured() ? "SI" : "NO") : "NO");
        PRODUCT_EXTRACTORS.put("descripcion", p -> p.getDescription() != null ? p.getDescription() : "");
        PRODUCT_EXTRACTORS.put("descripcion_detallada",
                p -> p.getDetailedDescription() != null ? p.getDetailedDescription() : "");
        PRODUCT_EXTRACTORS.put("modelo", p -> p.getModel() != null ? p.getModel() : "");
        PRODUCT_EXTRACTORS.put("dimensiones", p -> p.getDimensions() != null ? p.getDimensions() : "");
        PRODUCT_EXTRACTORS.put("peso", p -> p.getWeight() != null ? String.valueOf(p.getWeight()) : "");
        PRODUCT_EXTRACTORS.put("imagen_url", p -> p.getImageUrl() != null ? p.getImageUrl() : "");
        PRODUCT_EXTRACTORS.put("vistas", p -> p.getViews() != null ? String.valueOf(p.getViews()) : "0");
        PRODUCT_EXTRACTORS.put("ventas", p -> p.getSalesCount() != null ? String.valueOf(p.getSalesCount()) : "0");
        PRODUCT_EXTRACTORS.put("rating",
                p -> p.getAverageRating() != null ? p.getAverageRating().toPlainString() : "0");
        PRODUCT_EXTRACTORS.put("fecha_creacion", p -> p.getCreatedAt() != null ? p.getCreatedAt().toString() : "");
    }

    /** Mapa sortBy → propiedad JPA para ordenamiento */
    private static final Map<String, String> SORT_FIELD_MAP = Map.ofEntries(
            Map.entry("nombre", "name"),
            Map.entry("sku", "sku"),
            Map.entry("precio", "price"),
            Map.entry("stock", "stock"),
            Map.entry("categoria", "category.name"),
            Map.entry("marca", "brand.name"),
            Map.entry("fecha_creacion", "createdAt"),
            Map.entry("vistas", "views"),
            Map.entry("ventas", "salesCount"),
            Map.entry("rating", "averageRating"));

    /**
     * Retorna la lista de columnas disponibles para exportación de productos.
     */
    public List<ColumnMetadataDto> getAvailableProductColumns() {
        return PRODUCT_COLUMN_METADATA;
    }

    /**
     * Exporta productos con configuración personalizada de columnas, orden y
     * límite.
     *
     * @param config configuración enviada desde el frontend
     * @return bytes del CSV con BOM UTF-8
     */
    public byte[] exportProductsWithConfig(ExportConfigDto config) throws IOException {
        // — Resolver ordenamiento —
        String jpaField = SORT_FIELD_MAP.getOrDefault(config.sortBy(), "name");
        Sort.Direction direction = "desc".equalsIgnoreCase(config.sortDir())
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;
        Sort sort = Sort.by(direction, jpaField);

        List<Product> products = productRepository.findAll(sort);

        // — Aplicar límite —
        if (config.limit() > 0 && config.limit() < products.size()) {
            products = products.subList(0, config.limit());
        }

        // — Filtrar columnas válidas —
        List<String> selectedColumns = config.columns().stream()
                .filter(PRODUCT_EXTRACTORS::containsKey)
                .collect(Collectors.toList());

        if (selectedColumns.isEmpty()) {
            // Fallback: exportar columnas obligatorias
            selectedColumns = PRODUCT_COLUMN_METADATA.stream()
                    .filter(ColumnMetadataDto::required)
                    .map(ColumnMetadataDto::key)
                    .collect(Collectors.toList());
        }

        // — Resolver labels de cabecera —
        Map<String, String> keyToLabel = PRODUCT_COLUMN_METADATA.stream()
                .collect(Collectors.toMap(ColumnMetadataDto::key, ColumnMetadataDto::label));

        log.info("[CsvExport] Exportando {} productos con {} columnas, sort={}:{}, limit={}",
                products.size(), selectedColumns.size(), config.sortBy(), config.sortDir(), config.limit());

        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                PrintWriter writer = new PrintWriter(
                        new OutputStreamWriter(bos, StandardCharsets.UTF_8))) {

            // BOM para Excel Windows
            bos.write(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF });

            // Cabecera
            writer.println(selectedColumns.stream()
                    .map(k -> keyToLabel.getOrDefault(k, k))
                    .collect(Collectors.joining(",")));

            // Datos
            for (Product p : products) {
                List<String> finalColumns = selectedColumns;
                writer.println(finalColumns.stream()
                        .map(col -> escape(PRODUCT_EXTRACTORS.get(col).apply(p)))
                        .collect(Collectors.joining(",")));
            }

            writer.flush();
            return bos.toByteArray();
        }
    }

    /**
     * Genera un CSV plantilla con las cabeceras obligatorias + opcionales comunes
     * y 2 filas de ejemplo.
     *
     * @return bytes del CSV plantilla con BOM UTF-8
     */
    public byte[] generateProductTemplate() throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                PrintWriter writer = new PrintWriter(
                        new OutputStreamWriter(bos, StandardCharsets.UTF_8))) {

            bos.write(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF });

            // Cabecera completa para importación
            writer.println(
                    "SKU,Nombre,Categoria,Marca,Precio,Precio_Descuento,Stock,Activo,Destacado,Descripcion,Modelo,Dimensiones,Peso,Atributos");

            // Ejemplo 1
            writer.println(
                    "EJMP-001,Ejemplo - Guitarra Acustica,Guitarras,Yamaha,4599.99,3999.99,25,SI,SI,Guitarra acustica de ejemplo,FG800,100x38x12 cm,2.1,Material:Abeto | Cuerdas:Nylon");

            // Ejemplo 2
            writer.println(
                    "EJMP-002,Ejemplo - Amplificador 40W,Amplificadores,Fender,8999.00,,10,SI,NO,Amplificador de ejemplo,Champion 40,45x40x25 cm,9.5,Potencia:40W | Canales:2");

            writer.flush();
            return bos.toByteArray();
        }
    }
}
