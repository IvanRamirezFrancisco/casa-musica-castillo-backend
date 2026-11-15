package com.security.util;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

@Component
public class ImageValidator {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5 MB

    public void validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("El archivo está vacío o no se proporcionó.");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("El archivo supera el tamaño máximo de 5MB.");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_MIME_TYPES.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException("Tipo de contenido no permitido. Solo se aceptan JPG, PNG y WEBP.");
        }

        String originalFilename = StringUtils.cleanPath(file.getOriginalFilename() != null ? file.getOriginalFilename() : "");
        String extension = getExtension(originalFilename).toLowerCase();
        
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Extensión de archivo no permitida. Solo se aceptan .jpg, .jpeg, .png y .webp.");
        }
        
        if (originalFilename.contains("..")) {
            throw new IllegalArgumentException("El nombre del archivo contiene caracteres inválidos de ruta.");
        }

        validateMagicBytes(file, extension);
    }

    private void validateMagicBytes(MultipartFile file, String extension) {
        try (InputStream is = file.getInputStream()) {
            byte[] header = new byte[12];
            int read = is.read(header);
            if (read < 4) {
                throw new IllegalArgumentException("Archivo corrupto o demasiado pequeño.");
            }

            // WebP check (RIFF...WEBP)
            if (extension.equals("webp")) {
                if (read == 12 && 
                    header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F' &&
                    header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
                    return;
                }
                throw new IllegalArgumentException("El archivo no es un WEBP válido.");
            }

            // JPEG check
            if (extension.equals("jpg") || extension.equals("jpeg")) {
                if ((header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8) {
                    return;
                }
                throw new IllegalArgumentException("El archivo no es un JPEG válido.");
            }

            // PNG check
            if (extension.equals("png")) {
                if ((header[0] & 0xFF) == 0x89 && (header[1] & 0xFF) == 0x50 && 
                    (header[2] & 0xFF) == 0x4E && (header[3] & 0xFF) == 0x47) {
                    return;
                }
                throw new IllegalArgumentException("El archivo no es un PNG válido.");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Error al leer el contenido del archivo para validar su formato.");
        }
    }

    private String getExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < filename.length() - 1) {
            return filename.substring(dotIndex + 1);
        }
        return "";
    }
}
