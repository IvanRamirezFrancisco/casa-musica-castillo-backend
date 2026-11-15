package com.security.controller;

import com.security.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Controller para manejar la subida de archivos de imágenes.
 * Requiere permisos de gestión de productos para subir/eliminar imágenes.
 * @deprecated Use AdminProductImageController and AdminBrandController instead.
 */
@Deprecated
@RestController
@RequestMapping("/api/upload")
@PreAuthorize("hasAuthority('PRODUCT_CREATE') or hasAuthority('PRODUCT_UPDATE') or hasAuthority('BRAND_MANAGE') or hasAuthority('CATEGORY_MANAGE')")
public class FileUploadController {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadController.class);
    private static final Set<String> ALLOWED_FORMATS = Set.of("png", "jpeg", "webp");

    // Directorio donde se guardarán las imágenes
    @Value("${upload.path:uploads/products}")
    private String uploadPath;

    // URL base para acceder a las imágenes
    @Value("${upload.url:http://localhost:8080/uploads/products}")
    private String uploadUrl;

    /**
     * Sube una sola imagen
     * POST /api/upload/single
     */
    @PostMapping("/single")
    public ResponseEntity<?> uploadSingleImage(@RequestParam("file") MultipartFile file) {
        try {
            // Validar que el archivo no esté vacío
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "El archivo está vacío"));
            }

            // Validar tipo de archivo real (PNG/JPG/WEBP) - MIME sniffing
            ImageValidationResult validationResult = validateImage(file);
            if (validationResult == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Solo se permiten imágenes PNG, JPG o WEBP válidas"));
            }

            // Validar tamaño (máximo 10MB)
            if (file.getSize() > 10 * 1024 * 1024) {
                return ResponseEntity.badRequest().body(Map.of("error", "El archivo no debe superar 10MB"));
            }

            // Generar nombre único para el archivo
            String originalFilename = file.getOriginalFilename();
            String newFilename = UUID.randomUUID().toString() + validationResult.extension();

            // Crear directorio si no existe
            Path uploadDir = Paths.get(uploadPath);
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }

            // Guardar el archivo
            Path filePath = uploadDir.resolve(newFilename);
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

            // Construir URL de acceso
            String fileUrl = uploadUrl + "/" + newFilename;

            // Respuesta exitosa
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("url", fileUrl);
            response.put("filename", newFilename);
            response.put("originalName", originalFilename);
            response.put("size", file.getSize());

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            logger.error("Error al guardar el archivo subido", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error al guardar el archivo"));
        }
    }

    /**
     * Sube múltiples imágenes en lote
     * POST /api/upload/multiple
     */
    @PostMapping("/multiple")
    public ResponseEntity<?> uploadMultipleImages(@RequestParam("files") MultipartFile[] files) {
        // files.length es un int del request — se registra como valor numérico puro,
        // sin datos de cadena controlados por el usuario (CWE-117 safe)
        int fileCount = files.length;
        logger.debug("📤 Recibida petición de upload múltiple: {} archivos", fileCount);

        List<Map<String, Object>> uploadedFiles = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < files.length; i++) {
            MultipartFile file = files[i];
            // i+1 y fileCount son ints internos; filename sanitizado; size es long del
            // sistema de archivos
            int fileIndex = i + 1;
            long fileSize = file.getSize();
            logger.debug("  📄 Procesando archivo {}/{}: {} ({} bytes)",
                    fileIndex, fileCount,
                    LogSanitizer.sanitizeFilename(file.getOriginalFilename()),
                    fileSize);

            try {
                // Validar archivo
                if (file.isEmpty()) {
                    String error = "Archivo " + (i + 1) + ": está vacío";
                    errors.add(error);
                    logger.warn("  ⚠️ {}", error);
                    continue;
                }

                ImageValidationResult validationResult = validateImage(file);
                if (validationResult == null) {
                    String error = "Archivo " + (i + 1) + ": no es una imagen PNG/JPG/WEBP válida";
                    errors.add(error);
                    logger.warn("  ⚠️ {}", error);
                    continue;
                }

                if (file.getSize() > 10 * 1024 * 1024) {
                    String error = "Archivo " + (i + 1) + ": supera el tamaño máximo de 10MB";
                    errors.add(error);
                    logger.warn("  ⚠️ {}", error);
                    continue;
                }

                // Guardar archivo
                String originalFilename = file.getOriginalFilename();
                String newFilename = UUID.randomUUID().toString() + validationResult.extension();

                Path uploadDir = Paths.get(uploadPath);
                if (!Files.exists(uploadDir)) {
                    logger.info("  📁 Creando directorio: {}", uploadDir.toAbsolutePath());
                    Files.createDirectories(uploadDir);
                }

                Path filePath = uploadDir.resolve(newFilename);
                Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

                String fileUrl = uploadUrl + "/" + newFilename;

                logger.info("  ✅ Archivo guardado: {}", fileUrl);

                // Agregar a la lista de archivos subidos
                Map<String, Object> fileInfo = new HashMap<>();
                fileInfo.put("url", fileUrl);
                fileInfo.put("filename", newFilename);
                fileInfo.put("originalName", originalFilename);
                fileInfo.put("size", file.getSize());
                fileInfo.put("index", i);

                uploadedFiles.add(fileInfo);

            } catch (IOException e) {
                String error = "Archivo " + (i + 1) + ": error al guardar - " + e.getMessage();
                errors.add(error);
                logger.error("  ❌ {}", error, e);
            }
        }

        // Construir respuesta
        logger.info("✅ Upload completado: {}/{} archivos subidos exitosamente",
                uploadedFiles.size(), files.length);

        Map<String, Object> response = new HashMap<>();
        response.put("success", uploadedFiles.size() > 0);
        response.put("uploadedCount", uploadedFiles.size());
        response.put("totalFiles", files.length);
        response.put("files", uploadedFiles);

        if (!errors.isEmpty()) {
            response.put("errors", errors);
            logger.warn("⚠️ Errores durante el upload: {}", errors);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Elimina una imagen del servidor
     * DELETE /api/upload/{filename}
     */
    @DeleteMapping("/{filename}")
    public ResponseEntity<?> deleteImage(@PathVariable String filename) {
        try {
            Path filePath = Paths.get(uploadPath).resolve(filename);

            if (!Files.exists(filePath)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Archivo no encontrado"));
            }

            Files.delete(filePath);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Archivo eliminado correctamente",
                    "filename", filename));

        } catch (IOException e) {
            logger.error("Error al eliminar el archivo {}: {}",
                    LogSanitizer.sanitizeFilename(filename), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error al eliminar el archivo: " + e.getMessage()));
        }
    }

    /**
     * Maneja excepciones cuando el archivo es demasiado grande
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleMaxSizeException(MaxUploadSizeExceededException exc) {
        logger.error("❌ Archivo demasiado grande: {}", exc.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "El tamaño total de los archivos supera el límite permitido (50MB total, 10MB por archivo)"));
    }

    private ImageValidationResult validateImage(MultipartFile file) {
        // ── Detección WebP por magic bytes (RIFF....WEBP) ───────────────────────────
        // javax.imageio no incluye un decoder WebP nativo; lo identificamos por firma.
        try (InputStream is = file.getInputStream()) {
            byte[] header = new byte[12];
            int read = is.read(header);
            if (read == 12
                    && header[0] == 0x52 && header[1] == 0x49 && header[2] == 0x46 && header[3] == 0x46 // RIFF
                    && header[8] == 0x57 && header[9] == 0x45 && header[10] == 0x42 && header[11] == 0x50) { // WEBP
                return new ImageValidationResult("image/webp", ".webp");
            }
        } catch (IOException ex) {
            logger.error("Error leyendo magic bytes del archivo", ex);
            return null;
        }

        // ── Detección PNG / JPEG por ImageIO ────────────────────────────────────────
        ImageReader reader = null;
        try (ImageInputStream imageInputStream = ImageIO.createImageInputStream(file.getInputStream())) {
            if (imageInputStream == null) {
                return null;
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInputStream);
            if (!readers.hasNext()) {
                return null;
            }

            reader = readers.next();
            String formatName = reader.getFormatName().toLowerCase();
            if (formatName.equals("jpg")) {
                formatName = "jpeg";
            }

            if (!ALLOWED_FORMATS.contains(formatName)) {
                return null;
            }

            reader.setInput(imageInputStream, true, true);
            BufferedImage image = reader.read(0);
            if (image == null) {
                return null;
            }

            String mimeType = formatName.equals("png") ? "image/png" : "image/jpeg";
            String extension = formatName.equals("png") ? ".png" : ".jpg";
            return new ImageValidationResult(mimeType, extension);
        } catch (IOException ex) {
            logger.error("Error validando imagen", ex);
            return null;
        } finally {
            if (reader != null) {
                reader.dispose();
            }
        }
    }

    private record ImageValidationResult(String mimeType, String extension) {
    }
}
