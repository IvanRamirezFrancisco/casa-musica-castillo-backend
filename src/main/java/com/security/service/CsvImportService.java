package com.security.service;

import com.security.dto.admin.CsvImportResultDto;
import com.security.dto.admin.CsvImportPreviewDto;
import com.security.dto.admin.CsvPreviewRowDto;
import com.security.dto.admin.CsvRowErrorDto;
import com.security.entity.Brand;
import com.security.entity.Category;
import com.security.entity.Product;
import com.security.entity.ProductAttribute;
import com.security.entity.User;
import com.security.enums.CollisionRule;
import com.security.repository.BrandRepository;
import com.security.repository.CategoryRepository;
import com.security.repository.ProductRepository;
import com.security.repository.UserRepository;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Servicio de importación de CSV para Productos y Usuarios.
 *
 * <p>
 * <b>Columnas OBLIGATORIAS del CSV de Productos:</b><br>
 * {@code SKU, Nombre, Categoria, Precio, Stock}
 * </p>
 *
 * <p>
 * <b>Columnas OPCIONALES del CSV de Productos:</b><br>
 * {@code Marca, Precio_Descuento, Activo, Destacado,
 *        Descripcion, DescripcionDetallada, Modelo,
 *        Dimensiones, Peso, PrecioDescuento, ImagenUrl, Atributos}
 * </p>
 *
 * <p>
 * <b>Formato de Atributos:</b> pares {@code Clave:Valor} separados por
 * {@code , | ;} — Ejemplo: {@code Material:Caoba | Color:Rojo; Tipo:Eléctrica}
 * </p>
 *
 * <p>
 * <b>Columnas esperadas en el CSV de Usuarios (en orden):</b><br>
 * {@code Nombre, Apellidos, Correo, Telefono, Rol, Activo, EsCliente}
 * </p>
 *
 * <p>
 * Estrategia de upsert: si el registro ya existe (por SKU / email) se
 * actualiza; si no existe, se inserta.
 * </p>
 *
 * <p>
 * Las filas con errores se registran en el resultado pero no interrumpen
 * el procesamiento del resto del archivo.
 * </p>
 */
@Service
public class CsvImportService {

    private static final Logger log = LoggerFactory.getLogger(CsvImportService.class);

    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;
    private final PasswordEncoder passwordEncoder;

    public CsvImportService(ProductRepository productRepository,
            UserRepository userRepository,
            CategoryRepository categoryRepository,
            BrandRepository brandRepository,
            PasswordEncoder passwordEncoder) {
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.categoryRepository = categoryRepository;
        this.brandRepository = brandRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // ── Cabeceras obligatorias y opcionales del CSV de Productos ────────────

    /** Cabeceras requeridas — si falta alguna se aborta la importación */
    private static final Set<String> REQUIRED_HEADERS = new HashSet<>(Arrays.asList(
            "SKU", "Nombre", "Categoria", "Precio", "Stock"));

    /**
     * Formato Commons CSV: primera fila = cabecera, espacios recortados, BOM
     * ignorado
     */
    private static final CSVFormat PRODUCT_CSV_FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreHeaderCase(true)
            .setTrim(true)
            .setIgnoreEmptyLines(true)
            .build();

    // ── Importar Productos ───────────────────────────────────────────────────

    /**
     * Importa productos desde un archivo CSV con parseo basado en cabeceras.
     *
     * <p>
     * <b>Cabeceras OBLIGATORIAS (case-insensitive):</b>
     * {@code SKU, Nombre, Categoria, Precio, Stock}
     *
     * <p>
     * <b>Cabeceras OPCIONALES:</b>
     * {@code Marca, Precio_Descuento, Activo, Destacado,
     *        Descripcion, DescripcionDetallada, Modelo,
     *        Dimensiones, Peso, PrecioDescuento, ImagenUrl, Atributos}
     *
     * <p>
     * <b>Seguridad:</b>
     * <ul>
     * <li>DoS: el tamaño máximo (5 MB) se valida en el controlador.</li>
     * <li>CSV Injection + XSS: cada celda pasa por
     * {@link #sanitizeCsvCell(String)}.</li>
     * <li>Validación de cabeceras: aborta si faltan campos obligatorios.</li>
     * </ul>
     *
     * <p>
     * <b>Anti-N+1:</b>
     * <ul>
     * <li>SKUs → {@code findBySkuIn()} en una sola consulta.</li>
     * <li>Marcas → {@code findByNameIgnoreCaseIn()} en una sola consulta.</li>
     * <li>Cats → {@code findByNameIn()} en una sola consulta.</li>
     * </ul>
     */
    @Transactional
    public CsvImportResultDto importProducts(MultipartFile file, CollisionRule rule) throws IOException {

        List<CsvRowErrorDto> errors = new ArrayList<>();

        // ── Fase 1: Parsear CSV con Apache Commons CSV (por cabecera) ────────
        List<CSVRecord> records;
        Set<String> foundHeaders;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(bomAwareStream(file), StandardCharsets.UTF_8));
                CSVParser parser = PRODUCT_CSV_FORMAT.parse(reader)) {

            // Obtener cabeceras encontradas (Commons CSV las normaliza a minúsculas por
            // setIgnoreHeaderCase)
            foundHeaders = new HashSet<>(parser.getHeaderNames());
            records = parser.getRecords(); // carga todas las filas en memoria (CSV ≤ 5 MB → seguro)
        }

        // ── Validación de cabeceras obligatorias ────────────────────────────
        List<String> missingHeaders = REQUIRED_HEADERS.stream()
                .filter(h -> foundHeaders.stream().noneMatch(f -> f.equalsIgnoreCase(h)))
                .sorted()
                .collect(Collectors.toList());

        if (!missingHeaders.isEmpty()) {
            // Error fatal — abortar importación con mensaje claro
            String msg = "El CSV no contiene las cabeceras obligatorias: " + missingHeaders
                    + ". Cabeceras encontradas: " + new ArrayList<>(foundHeaders);
            log.warn("[CsvImport] Abortando importacion — cabeceras faltantes: {}", missingHeaders);
            errors.add(new CsvRowErrorDto(0, "", msg));
            return new CsvImportResultDto(0, 0, 0, 0, 1, errors);
        }

        if (records.isEmpty()) {
            return new CsvImportResultDto(0, 0, 0, 0, 0, errors);
        }

        // ── Fase 2: Extraer valores únicos para consultas batch ──────────────
        List<String> allSkus = records.stream()
                .map(r -> cell(r, "SKU"))
                .filter(s -> !s.isBlank())
                .distinct()
                .collect(Collectors.toList());

        List<String> allCatNames = records.stream()
                .map(r -> cell(r, "Categoria"))
                .filter(s -> !s.isBlank())
                .distinct()
                .collect(Collectors.toList());

        List<String> allBrandNames = records.stream()
                .map(r -> cell(r, "Marca"))
                .filter(s -> !s.isBlank())
                .distinct()
                .collect(Collectors.toList());

        // Una sola consulta por entidad relacionada
        Map<String, Product> existingBySkuMap = productRepository.findBySkuIn(allSkus)
                .stream().collect(Collectors.toMap(Product::getSku, Function.identity()));

        Map<String, Category> categoryMap = categoryRepository.findByNameIn(allCatNames)
                .stream().collect(Collectors.toMap(
                        cat -> cat.getName().toLowerCase(),
                        Function.identity(),
                        (a, b) -> a)); // en caso de duplicados en BD, queda el primero

        Map<String, Brand> brandMap = brandRepository.findByNameIgnoreCaseIn(allBrandNames)
                .stream().collect(Collectors.toMap(
                        br -> br.getName().toLowerCase(),
                        Function.identity(),
                        (a, b) -> a));

        // ── Fase 3: Iterar registros aplicando CollisionRule ─────────────────
        List<Product> toSave = new ArrayList<>();
        int inserted = 0;
        int updated = 0;
        int skipped = 0;

        for (CSVRecord record : records) {
            long lineNumber = record.getRecordNumber() + 1; // +1 por la cabecera consumida
            String rawLine = record.toList().stream().collect(Collectors.joining(","));

            // ── Campos OBLIGATORIOS ────────────────────────────────────────────
            String sku = sanitizeCsvCell(cell(record, "SKU"));
            String name = sanitizeCsvCell(cell(record, "Nombre"));
            String catName = sanitizeCsvCell(cell(record, "Categoria"));
            String brandName = sanitizeCsvCell(cell(record, "Marca"));
            String priceStr = cell(record, "Precio");
            String stockStr = cell(record, "Stock");

            // ── Campos OPCIONALES — legacy (ya existían) ──────────────────────
            String discountStr = cell(record, "Precio_Descuento");
            String activoStr = cell(record, "Activo");
            String featuredStr = cell(record, "Destacado");

            // ── Campos OPCIONALES — nuevos ────────────────────────────────────
            String descripcion = optCell(record, "Descripcion");
            String descripcionDetallada = optCell(record, "DescripcionDetallada");
            String modelo = optCell(record, "Modelo");
            String dimensiones = optCell(record, "Dimensiones");
            String pesoStr = optCell(record, "Peso");
            String precioDescuentoAlt = optCell(record, "PrecioDescuento"); // alias sin guion bajo
            String imagenUrl = optCell(record, "ImagenUrl");
            String atributosStr = optCell(record, "Atributos");

            // ── Validaciones de campos obligatorios (SKU y Nombre son siempre requeridos)
            if (sku.isBlank()) {
                errors.add(new CsvRowErrorDto((int) lineNumber, rawLine, "SKU vacio"));
                continue;
            }
            if (name.isBlank()) {
                errors.add(new CsvRowErrorDto((int) lineNumber, rawLine, "Nombre vacio"));
                continue;
            }

            // ── Validar Precio ────────────────────────────────────────────────
            BigDecimal price;
            try {
                price = new BigDecimal(priceStr.replace(",", "."));
                if (price.compareTo(BigDecimal.ZERO) <= 0)
                    throw new NumberFormatException();
            } catch (NumberFormatException e) {
                errors.add(new CsvRowErrorDto((int) lineNumber, rawLine,
                        "Precio invalido: '" + priceStr + "'"));
                continue;
            }

            // ── Resolver Categoria (Borrador si falta) ────────────────────────
            boolean isDraft = false;

            Category category = null;
            if (!catName.isBlank()) {
                category = categoryMap.get(catName.toLowerCase());
                if (category == null) {
                    // Categoría informada pero no existe en BD → Borrador
                    isDraft = true;
                    log.debug("[CsvImport] Borrador — categoria no encontrada: '{}' (fila {})", catName, lineNumber);
                }
            } else {
                // Categoría vacía en el CSV → Borrador
                isDraft = true;
            }

            // ── Resolver Marca (Borrador si falta) ────────────────────────────
            Brand brand = null;
            if (!brandName.isBlank()) {
                brand = brandMap.get(brandName.toLowerCase());
                if (brand == null) {
                    // Marca informada pero no existe en BD → Borrador
                    isDraft = true;
                    log.debug("[CsvImport] Borrador — marca no encontrada: '{}' (fila {})", brandName, lineNumber);
                }
            }
            // Marca vacía no fuerza Borrador (campo verdaderamente opcional)

            // ── Precio con descuento ──────────────────────────────────────────
            // Si vienen ambas columnas (Precio_Descuento y PrecioDescuento) se usa
            // la legacy; si solo viene el alias nuevo, se usa ese.
            String effectiveDiscountStr = !discountStr.isBlank() ? discountStr
                    : !precioDescuentoAlt.isBlank() ? precioDescuentoAlt : "";

            BigDecimal discountPrice = null;
            if (!effectiveDiscountStr.isBlank()) {
                try {
                    discountPrice = new BigDecimal(effectiveDiscountStr.replace(",", "."));
                } catch (NumberFormatException e) {
                    errors.add(new CsvRowErrorDto((int) lineNumber, rawLine,
                            "Precio descuento invalido: '" + effectiveDiscountStr + "'"));
                    continue;
                }
            }

            // ── Stock (obligatorio, default 0) ────────────────────────────────
            int stock = 0;
            if (!stockStr.isBlank()) {
                try {
                    stock = Integer.parseInt(stockStr);
                } catch (NumberFormatException e) {
                    errors.add(new CsvRowErrorDto((int) lineNumber, rawLine,
                            "Stock invalido: '" + stockStr + "'"));
                    continue;
                }
            }

            // ── Peso (opcional — parseo local, no aborta la fila si es inválido) ──
            Double weight = null;
            if (StringUtils.hasText(pesoStr)) {
                try {
                    weight = Double.parseDouble(pesoStr.replace(",", "."));
                } catch (NumberFormatException e) {
                    log.warn("[CsvImport] Peso invalido '{}' en fila {} — se omite el campo", pesoStr, lineNumber);
                }
            }

            // ── Atributos dinámicos ───────────────────────────────────────────
            // Formato: "Clave:Valor" separados por , | ;
            // Ejemplo: "Material:Caoba | Color:Rojo; Tipo:Eléctrica, Calibre:0.10"
            List<ProductAttribute> parsedAttributes = new ArrayList<>();
            if (StringUtils.hasText(atributosStr)) {
                String[] pares = atributosStr.split("[,|;]+");
                int order = 0;
                for (String par : pares) {
                    par = par.trim();
                    if (par.isBlank())
                        continue;
                    String[] kv = par.split(":", 2);
                    if (kv.length == 2) {
                        String attrKey = sanitizeCsvCell(kv[0].trim());
                        String attrValue = sanitizeCsvCell(kv[1].trim());
                        if (StringUtils.hasText(attrKey) && StringUtils.hasText(attrValue)) {
                            parsedAttributes.add(new ProductAttribute(attrKey, attrValue, order++));
                        }
                    }
                }
            }

            // Si el producto es Borrador, se fuerza Inactivo independientemente
            // del valor en el CSV. El admin puede activarlo desde el panel.
            boolean activo = isDraft ? false : parseBoolean(activoStr, true);
            boolean featured = isDraft ? false : parseBoolean(featuredStr, false);

            // ── Aplicar CollisionRule ─────────────────────────────────────────
            Product existing = existingBySkuMap.get(sku);
            if (existing != null) {
                if (rule == CollisionRule.SKIP) {
                    skipped++;
                    continue;
                }
                // Campos obligatorios + previamente existentes
                existing.setName(name);
                existing.setCategory(category);
                existing.setBrand(brand);
                existing.setPrice(price);
                existing.setDiscountPrice(discountPrice);
                existing.setStock(stock);
                existing.setActive(activo);
                existing.setFeatured(featured);

                // Campos opcionales — solo sobreescribir si la columna viene en el CSV
                if (StringUtils.hasText(descripcion))
                    existing.setDescription(sanitizeCsvCell(descripcion));
                if (StringUtils.hasText(descripcionDetallada))
                    existing.setDetailedDescription(sanitizeCsvCell(descripcionDetallada));
                if (StringUtils.hasText(modelo))
                    existing.setModel(sanitizeCsvCell(modelo));
                if (StringUtils.hasText(dimensiones))
                    existing.setDimensions(sanitizeCsvCell(dimensiones));
                if (weight != null)
                    existing.setWeight(weight);
                if (StringUtils.hasText(imagenUrl))
                    existing.setImageUrl(sanitizeCsvCell(imagenUrl));

                // Atributos: reemplazar solo si la columna Atributos viene en el CSV
                if (StringUtils.hasText(atributosStr)) {
                    existing.getCustomAttributes().clear();
                    for (ProductAttribute attr : parsedAttributes) {
                        attr.setProduct(existing);
                        existing.getCustomAttributes().add(attr);
                    }
                }

                toSave.add(existing);
                updated++;
            } else {
                Product product = new Product();
                product.setSku(sku);
                product.setName(name);
                product.setCategory(category);
                product.setBrand(brand);
                product.setPrice(price);
                product.setDiscountPrice(discountPrice);
                product.setStock(stock);
                product.setActive(activo);
                product.setFeatured(featured);

                // Campos opcionales
                if (StringUtils.hasText(descripcion))
                    product.setDescription(sanitizeCsvCell(descripcion));
                if (StringUtils.hasText(descripcionDetallada))
                    product.setDetailedDescription(sanitizeCsvCell(descripcionDetallada));
                if (StringUtils.hasText(modelo))
                    product.setModel(sanitizeCsvCell(modelo));
                if (StringUtils.hasText(dimensiones))
                    product.setDimensions(sanitizeCsvCell(dimensiones));
                if (weight != null)
                    product.setWeight(weight);
                if (StringUtils.hasText(imagenUrl))
                    product.setImageUrl(sanitizeCsvCell(imagenUrl));

                // Atributos
                if (!parsedAttributes.isEmpty()) {
                    for (ProductAttribute attr : parsedAttributes) {
                        attr.setProduct(product);
                        product.getCustomAttributes().add(attr);
                    }
                }

                toSave.add(product);
                inserted++;
            }
        }

        // ── Fase 4: Batch save ────────────────────────────────────────────────
        if (!toSave.isEmpty()) {
            productRepository.saveAll(toSave);
        }

        int totalRows = records.size();
        int success = inserted + updated;
        int errorCount = errors.size();
        log.info("[CsvImport] Productos — rule:{}, total:{}, ok:{}, insert:{}, update:{}, skip:{}, errors:{}",
                rule, totalRows, success, inserted, updated, skipped, errorCount);

        return new CsvImportResultDto(totalRows, success, inserted, updated, errorCount, errors);
    }

    /**
     * Sobrecarga de compatibilidad: delega con regla UPDATE.
     */
    @Transactional
    public CsvImportResultDto importProducts(MultipartFile file) throws IOException {
        return importProducts(file, CollisionRule.UPDATE);
    }

    // ── Preview de Productos (sin guardar) ──────────────────────────────────

    /**
     * Parsea y valida un CSV de productos SIN persistir nada.
     * Devuelve una previsualización con todas las filas, indicando cuáles son
     * válidas
     * y cuáles tienen errores, para que el usuario decida qué importar.
     *
     * @param file archivo CSV subido
     * @return preview con cabeceras, filas validadas y conteos
     */
    public CsvImportPreviewDto previewProducts(MultipartFile file) throws IOException {
        List<CsvPreviewRowDto> previewRows = new ArrayList<>();

        // — Fase 1: Parsear CSV —
        List<CSVRecord> records;
        List<String> headerList;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(bomAwareStream(file), StandardCharsets.UTF_8));
                CSVParser parser = PRODUCT_CSV_FORMAT.parse(reader)) {

            headerList = new ArrayList<>(parser.getHeaderNames());
            records = parser.getRecords();
        }

        // — Validar cabeceras obligatorias —
        List<String> missingHeaders = REQUIRED_HEADERS.stream()
                .filter(h -> headerList.stream().noneMatch(f -> f.equalsIgnoreCase(h)))
                .sorted()
                .collect(Collectors.toList());

        if (!missingHeaders.isEmpty()) {
            String msg = "El CSV no contiene las cabeceras obligatorias: " + missingHeaders;
            List<String> errList = new ArrayList<>();
            errList.add(msg);
            previewRows.add(new CsvPreviewRowDto(0, Map.of("error", msg), false, errList));
            return new CsvImportPreviewDto(headerList, previewRows, 0, 0, 1,
                    file.getOriginalFilename(),
                    file.getSize() / 1024.0);
        }

        // — Cargar datos existentes para validación de colisiones —
        List<String> allSkus = records.stream()
                .map(r -> cell(r, "SKU"))
                .filter(s -> !s.isBlank())
                .distinct()
                .collect(Collectors.toList());

        Set<String> existingSkus = productRepository.findBySkuIn(allSkus)
                .stream().map(Product::getSku).collect(Collectors.toSet());

        Set<String> existingCategories = categoryRepository.findAll()
                .stream().map(cat -> cat.getName().toLowerCase()).collect(Collectors.toSet());

        Set<String> existingBrands = brandRepository.findAll()
                .stream().map(br -> br.getName().toLowerCase()).collect(Collectors.toSet());

        int validCount = 0;
        int errorCount = 0;

        // — Fase 2: Validar cada fila sin persistir —
        for (CSVRecord record : records) {
            int rowNum = (int) record.getRecordNumber() + 1;
            List<String> rowErrors = new ArrayList<>();
            Map<String, String> cells = new LinkedHashMap<>();

            // Extraer todas las celdas
            for (String header : headerList) {
                cells.put(header, cell(record, header));
            }

            String sku = cell(record, "SKU").trim();
            String name = cell(record, "Nombre").trim();
            String catName = cell(record, "Categoria").trim();
            String priceStr = cell(record, "Precio").trim();
            String stockStr = cell(record, "Stock").trim();

            // Validaciones
            if (sku.isBlank())
                rowErrors.add("SKU vacío");
            if (name.isBlank())
                rowErrors.add("Nombre vacío");

            if (!priceStr.isBlank()) {
                try {
                    java.math.BigDecimal p = new java.math.BigDecimal(priceStr.replace(",", "."));
                    if (p.compareTo(java.math.BigDecimal.ZERO) <= 0)
                        rowErrors.add("Precio debe ser mayor a 0");
                } catch (NumberFormatException e) {
                    rowErrors.add("Precio inválido: '" + priceStr + "'");
                }
            } else {
                rowErrors.add("Precio vacío");
            }

            if (!stockStr.isBlank()) {
                try {
                    Integer.parseInt(stockStr);
                } catch (NumberFormatException e) {
                    rowErrors.add("Stock inválido: '" + stockStr + "'");
                }
            }

            // Verificar si el SKU ya existe
            if (!sku.isBlank() && existingSkus.contains(sku)) {
                cells.put("_existente", "SI");
            }

            // Verificar categoría
            if (!catName.isBlank() && !existingCategories.contains(catName.toLowerCase())) {
                rowErrors.add("Categoría '" + catName + "' no existe — se creará como borrador");
            }

            // Verificar marca
            String brandName = cell(record, "Marca").trim();
            if (!brandName.isBlank() && !existingBrands.contains(brandName.toLowerCase())) {
                rowErrors.add("Marca '" + brandName + "' no existe — se creará como borrador");
            }

            boolean valid = rowErrors.isEmpty();
            if (valid)
                validCount++;
            else
                errorCount++;

            previewRows.add(new CsvPreviewRowDto(rowNum, cells, valid, rowErrors));
        }

        log.info("[CsvImport] Preview productos: {} filas, {} válidas, {} con errores",
                records.size(), validCount, errorCount);

        return new CsvImportPreviewDto(
                headerList,
                previewRows,
                records.size(),
                validCount,
                errorCount,
                file.getOriginalFilename(),
                file.getSize() / 1024.0);
    }

    /**
     * Importa solo las filas seleccionadas por el usuario después del preview.
     *
     * @param file         archivo CSV original (se re-parsea)
     * @param selectedRows números de fila a importar (1-based del preview)
     * @param rule         regla de colisión
     * @return resultado de la importación
     */
    @Transactional
    public CsvImportResultDto importProductsFromPreview(
            MultipartFile file, List<Integer> selectedRows, CollisionRule rule) throws IOException {

        if (selectedRows == null || selectedRows.isEmpty()) {
            return new CsvImportResultDto(0, 0, 0, 0, 0, List.of());
        }

        Set<Integer> selectedSet = new HashSet<>(selectedRows);
        List<CsvRowErrorDto> errors = new ArrayList<>();

        // — Parsear CSV —
        List<CSVRecord> records;
        Set<String> foundHeaders;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(bomAwareStream(file), StandardCharsets.UTF_8));
                CSVParser parser = PRODUCT_CSV_FORMAT.parse(reader)) {
            foundHeaders = new HashSet<>(parser.getHeaderNames());
            records = parser.getRecords();
        }

        // Filtrar solo las filas seleccionadas
        List<CSVRecord> filteredRecords = new ArrayList<>();
        for (CSVRecord record : records) {
            int rowNum = (int) record.getRecordNumber() + 1;
            if (selectedSet.contains(rowNum)) {
                filteredRecords.add(record);
            }
        }

        if (filteredRecords.isEmpty()) {
            return new CsvImportResultDto(0, 0, 0, 0, 0, errors);
        }

        // — Batch lookups (mismo patrón que importProducts) —
        List<String> allSkus = filteredRecords.stream()
                .map(r -> cell(r, "SKU"))
                .filter(s -> !s.isBlank())
                .distinct()
                .collect(Collectors.toList());

        List<String> allCatNames = filteredRecords.stream()
                .map(r -> cell(r, "Categoria"))
                .filter(s -> !s.isBlank())
                .distinct()
                .collect(Collectors.toList());

        List<String> allBrandNames = filteredRecords.stream()
                .map(r -> cell(r, "Marca"))
                .filter(s -> !s.isBlank())
                .distinct()
                .collect(Collectors.toList());

        Map<String, Product> existingBySkuMap = productRepository.findBySkuIn(allSkus)
                .stream().collect(Collectors.toMap(Product::getSku, java.util.function.Function.identity()));

        Map<String, Category> categoryMap = categoryRepository.findByNameIn(allCatNames)
                .stream().collect(Collectors.toMap(
                        cat -> cat.getName().toLowerCase(),
                        java.util.function.Function.identity(),
                        (a, b) -> a));

        Map<String, Brand> brandMap = brandRepository.findByNameIgnoreCaseIn(allBrandNames)
                .stream().collect(Collectors.toMap(
                        br -> br.getName().toLowerCase(),
                        java.util.function.Function.identity(),
                        (a, b) -> a));

        // — Procesar filas seleccionadas —
        List<Product> toSave = new ArrayList<>();
        int inserted = 0, updated = 0, skipped = 0;

        for (CSVRecord record : filteredRecords) {
            long lineNumber = record.getRecordNumber() + 1;
            String rawLine = record.toList().stream().collect(Collectors.joining(","));

            String sku = sanitizeCsvCell(cell(record, "SKU"));
            String name = sanitizeCsvCell(cell(record, "Nombre"));
            String catName = sanitizeCsvCell(cell(record, "Categoria"));
            String brandName = sanitizeCsvCell(cell(record, "Marca"));
            String priceStr = cell(record, "Precio");
            String stockStr = cell(record, "Stock");
            String discountStr = cell(record, "Precio_Descuento");
            String activoStr = cell(record, "Activo");
            String featuredStr = cell(record, "Destacado");
            String descripcion = optCell(record, "Descripcion");
            String descripcionDetallada = optCell(record, "DescripcionDetallada");
            String modelo = optCell(record, "Modelo");
            String dimensiones = optCell(record, "Dimensiones");
            String pesoStr = optCell(record, "Peso");
            String precioDescuentoAlt = optCell(record, "PrecioDescuento");
            String imagenUrl = optCell(record, "ImagenUrl");
            String atributosStr = optCell(record, "Atributos");

            if (sku.isBlank()) {
                errors.add(new CsvRowErrorDto((int) lineNumber, rawLine, "SKU vacío"));
                continue;
            }
            if (name.isBlank()) {
                errors.add(new CsvRowErrorDto((int) lineNumber, rawLine, "Nombre vacío"));
                continue;
            }

            BigDecimal price;
            try {
                price = new BigDecimal(priceStr.replace(",", "."));
                if (price.compareTo(BigDecimal.ZERO) <= 0)
                    throw new NumberFormatException();
            } catch (NumberFormatException e) {
                errors.add(new CsvRowErrorDto((int) lineNumber, rawLine, "Precio inválido: '" + priceStr + "'"));
                continue;
            }

            boolean isDraft = false;
            Category category = null;
            if (!catName.isBlank()) {
                category = categoryMap.get(catName.toLowerCase());
                if (category == null)
                    isDraft = true;
            } else {
                isDraft = true;
            }

            Brand brand = null;
            if (!brandName.isBlank()) {
                brand = brandMap.get(brandName.toLowerCase());
                if (brand == null)
                    isDraft = true;
            }

            String effectiveDiscountStr = !discountStr.isBlank() ? discountStr
                    : !precioDescuentoAlt.isBlank() ? precioDescuentoAlt : "";

            BigDecimal discountPrice = null;
            if (!effectiveDiscountStr.isBlank()) {
                try {
                    discountPrice = new BigDecimal(effectiveDiscountStr.replace(",", "."));
                } catch (NumberFormatException e) {
                    errors.add(new CsvRowErrorDto((int) lineNumber, rawLine, "Precio descuento inválido"));
                    continue;
                }
            }

            int stock = 0;
            if (!stockStr.isBlank()) {
                try {
                    stock = Integer.parseInt(stockStr);
                } catch (NumberFormatException e) {
                    errors.add(new CsvRowErrorDto((int) lineNumber, rawLine, "Stock inválido"));
                    continue;
                }
            }

            Double weight = null;
            if (StringUtils.hasText(pesoStr)) {
                try {
                    weight = Double.parseDouble(pesoStr.replace(",", "."));
                } catch (NumberFormatException ignore) {
                }
            }

            List<ProductAttribute> parsedAttributes = new ArrayList<>();
            if (StringUtils.hasText(atributosStr)) {
                String[] pares = atributosStr.split("[,|;]+");
                int order = 0;
                for (String par : pares) {
                    par = par.trim();
                    if (par.isBlank())
                        continue;
                    String[] kv = par.split(":", 2);
                    if (kv.length == 2) {
                        String attrKey = sanitizeCsvCell(kv[0].trim());
                        String attrValue = sanitizeCsvCell(kv[1].trim());
                        if (StringUtils.hasText(attrKey) && StringUtils.hasText(attrValue)) {
                            parsedAttributes.add(new ProductAttribute(attrKey, attrValue, order++));
                        }
                    }
                }
            }

            boolean activo = isDraft ? false : parseBoolean(activoStr, true);
            boolean featured = isDraft ? false : parseBoolean(featuredStr, false);

            Product existing = existingBySkuMap.get(sku);
            if (existing != null) {
                if (rule == CollisionRule.SKIP) {
                    skipped++;
                    continue;
                }
                existing.setName(name);
                existing.setCategory(category);
                existing.setBrand(brand);
                existing.setPrice(price);
                existing.setDiscountPrice(discountPrice);
                existing.setStock(stock);
                existing.setActive(activo);
                existing.setFeatured(featured);
                if (StringUtils.hasText(descripcion))
                    existing.setDescription(sanitizeCsvCell(descripcion));
                if (StringUtils.hasText(descripcionDetallada))
                    existing.setDetailedDescription(sanitizeCsvCell(descripcionDetallada));
                if (StringUtils.hasText(modelo))
                    existing.setModel(sanitizeCsvCell(modelo));
                if (StringUtils.hasText(dimensiones))
                    existing.setDimensions(sanitizeCsvCell(dimensiones));
                if (weight != null)
                    existing.setWeight(weight);
                if (StringUtils.hasText(imagenUrl))
                    existing.setImageUrl(sanitizeCsvCell(imagenUrl));
                if (StringUtils.hasText(atributosStr)) {
                    existing.getCustomAttributes().clear();
                    for (ProductAttribute attr : parsedAttributes) {
                        attr.setProduct(existing);
                        existing.getCustomAttributes().add(attr);
                    }
                }
                toSave.add(existing);
                updated++;
            } else {
                Product product = new Product();
                product.setSku(sku);
                product.setName(name);
                product.setCategory(category);
                product.setBrand(brand);
                product.setPrice(price);
                product.setDiscountPrice(discountPrice);
                product.setStock(stock);
                product.setActive(activo);
                product.setFeatured(featured);
                if (StringUtils.hasText(descripcion))
                    product.setDescription(sanitizeCsvCell(descripcion));
                if (StringUtils.hasText(descripcionDetallada))
                    product.setDetailedDescription(sanitizeCsvCell(descripcionDetallada));
                if (StringUtils.hasText(modelo))
                    product.setModel(sanitizeCsvCell(modelo));
                if (StringUtils.hasText(dimensiones))
                    product.setDimensions(sanitizeCsvCell(dimensiones));
                if (weight != null)
                    product.setWeight(weight);
                if (StringUtils.hasText(imagenUrl))
                    product.setImageUrl(sanitizeCsvCell(imagenUrl));
                if (!parsedAttributes.isEmpty()) {
                    for (ProductAttribute attr : parsedAttributes) {
                        attr.setProduct(product);
                        product.getCustomAttributes().add(attr);
                    }
                }
                toSave.add(product);
                inserted++;
            }
        }

        if (!toSave.isEmpty()) {
            productRepository.saveAll(toSave);
        }

        int totalRows = filteredRecords.size();
        int success = inserted + updated;
        int errorCountFinal = errors.size();
        log.info(
                "[CsvImport] Import from preview — rule:{}, selected:{}, ok:{}, insert:{}, update:{}, skip:{}, errors:{}",
                rule, selectedRows.size(), success, inserted, updated, skipped, errorCountFinal);

        return new CsvImportResultDto(totalRows, success, inserted, updated, errorCountFinal, errors);
    }

    // ── Importar Usuarios ────────────────────────────────────────────────────

    /** Cabeceras obligatorias del CSV de Usuarios */
    private static final Set<String> REQUIRED_USER_HEADERS = new HashSet<>(Arrays.asList(
            "Nombre", "Apellidos", "Correo"));

    private static final CSVFormat USER_CSV_FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreHeaderCase(true)
            .setTrim(true)
            .setIgnoreEmptyLines(true)
            .build();

    /**
     * Importa usuarios desde un archivo CSV con parseo por cabecera.
     *
     * <p>
     * <b>Cabeceras soportadas:</b>
     * {@code Nombre, Apellidos, Correo, Telefono, Rol (ignorado), Activo, EsCliente}
     *
     * <p>
     * Para usuarios nuevos se genera una contraseña temporal aleatoria.
     */
    @Transactional
    public CsvImportResultDto importUsers(MultipartFile file) throws IOException {

        List<CsvRowErrorDto> errors = new ArrayList<>();

        // ── Parsear CSV con Commons CSV ──────────────────────────────────────
        List<CSVRecord> records;
        Set<String> foundHeaders;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(bomAwareStream(file), StandardCharsets.UTF_8));
                CSVParser parser = USER_CSV_FORMAT.parse(reader)) {

            foundHeaders = new HashSet<>(parser.getHeaderNames());
            records = parser.getRecords();
        }

        // ── Validación de cabeceras obligatorias ─────────────────────────────
        List<String> missingHeaders = REQUIRED_USER_HEADERS.stream()
                .filter(h -> foundHeaders.stream().noneMatch(f -> f.equalsIgnoreCase(h)))
                .sorted()
                .collect(Collectors.toList());

        if (!missingHeaders.isEmpty()) {
            String msg = "El CSV no contiene las cabeceras obligatorias: " + missingHeaders
                    + ". Cabeceras encontradas: " + new ArrayList<>(foundHeaders);
            log.warn("[CsvImport] Abortando importacion usuarios — cabeceras faltantes: {}", missingHeaders);
            errors.add(new CsvRowErrorDto(0, "", msg));
            return new CsvImportResultDto(0, 0, 0, 0, 1, errors);
        }

        if (records.isEmpty()) {
            return new CsvImportResultDto(0, 0, 0, 0, 0, errors);
        }

        // ── Procesar registros ────────────────────────────────────────────────
        int inserted = 0;
        int updated = 0;

        for (CSVRecord record : records) {
            long lineNumber = record.getRecordNumber() + 1;
            String rawLine = record.toList().stream().collect(Collectors.joining(","));

            String firstName = cell(record, "Nombre");
            String lastName = cell(record, "Apellidos");
            String email = cell(record, "Correo");
            String phone = cell(record, "Telefono");
            // "Rol" se ignora — los roles se gestionan desde el panel de admin
            String activoStr = cell(record, "Activo");
            String isCustomerStr = cell(record, "EsCliente");

            if (firstName.isBlank()) {
                errors.add(new CsvRowErrorDto((int) lineNumber, rawLine, "Nombre vacio"));
                continue;
            }
            if (email.isBlank() || !email.contains("@")) {
                errors.add(new CsvRowErrorDto((int) lineNumber, rawLine, "Email invalido: '" + email + "'"));
                continue;
            }

            boolean activo = parseBoolean(activoStr, true);
            boolean isCustomer = parseBoolean(isCustomerStr, false);

            boolean isNew = false;
            User user = userRepository.findByEmail(email).orElse(null);
            if (user == null) {
                user = new User();
                user.setEmail(email);
                String baseUsername = email.split("@")[0];
                String username = baseUsername;
                int suffix = 1;
                while (userRepository.existsByUsername(username)) {
                    username = baseUsername + suffix++;
                }
                user.setUsername(username);
                user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
                isNew = true;
            }

            user.setFirstName(firstName);
            user.setLastName(lastName);
            if (!phone.isBlank())
                user.setPhone(phone);
            user.setEnabled(activo);
            user.setIsCustomer(isCustomer);

            userRepository.save(user);

            if (isNew)
                inserted++;
            else
                updated++;
        }

        int totalRows = records.size();
        int success = inserted + updated;
        int errorCount = errors.size();
        log.info("[CsvImport] Usuarios — total:{}, ok:{}, insert:{}, update:{}, errors:{}",
                totalRows, success, inserted, updated, errorCount);

        return new CsvImportResultDto(totalRows, success, inserted, updated, errorCount, errors);
    }

    // ── Utilidades ────────────────────────────────────────────────────────────

    /**
     * Lee una celda del registro CSV por nombre de cabecera de forma segura.
     * Retorna cadena vacía si la cabecera no existe o el valor es null.
     */
    private String cell(CSVRecord record, String header) {
        try {
            String value = record.get(header);
            return value == null ? "" : value.trim();
        } catch (IllegalArgumentException e) {
            // La cabecera no existe en el CSV — columna opcional ausente
            return "";
        }
    }

    /**
     * Igual que {@link #cell} pero semánticamente deja claro que la columna
     * es opcional. Retorna cadena vacía si la cabecera no está presente.
     */
    private String optCell(CSVRecord record, String header) {
        return cell(record, header);
    }

    /**
     * Sanitiza una celda CSV contra:
     * <ul>
     * <li><b>CSV Injection</b>: si empieza con {@code = + - @} prepende un
     * apóstrofe.</li>
     * <li><b>XSS</b>: escapa entidades HTML básicas con
     * {@code HtmlUtils.htmlEscape}.</li>
     * </ul>
     */
    private String sanitizeCsvCell(String cell) {
        if (cell == null || cell.isBlank())
            return cell == null ? "" : cell;
        String safe = HtmlUtils.htmlEscape(cell, "UTF-8");
        if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        return safe;
    }

    /** Elimina el BOM UTF-8 si está presente al inicio del stream. */
    private java.io.InputStream bomAwareStream(MultipartFile file) throws IOException {
        java.io.InputStream is = file.getInputStream();
        is.mark(3);
        byte[] bom = new byte[3];
        if (is.read(bom) == 3
                && (bom[0] & 0xFF) == 0xEF
                && (bom[1] & 0xFF) == 0xBB
                && (bom[2] & 0xFF) == 0xBF) {
            return is;
        }
        is.reset();
        return is;
    }

    /** Interpreta SI/NO, TRUE/FALSE, 1/0. */
    private boolean parseBoolean(String value, boolean defaultValue) {
        if (value == null || value.isBlank())
            return defaultValue;
        return value.equalsIgnoreCase("SI")
                || value.equalsIgnoreCase("TRUE")
                || value.equals("1")
                || value.equalsIgnoreCase("YES");
    }
}
