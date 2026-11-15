package com.security.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Entidad de auditoría para la migración de imágenes locales a Cloudinary.
 * Registra cada operación de la Fase 6D (report, dryRun, execute).
 * No contiene credenciales ni paths absolutos sensibles.
 *
 * Estados posibles:
 * SCANNED                     - Solo analizado en report()
 * DRY_RUN                     - Simulado en dryRun()
 * MIGRATED                    - Migrado exitosamente a Cloudinary
 * SKIPPED_ALREADY_CLOUDINARY  - Ya estaba en Cloudinary, se saltó
 * SKIPPED_ALREADY_HAS_GALLERY - El producto ya tiene galería, no se duplicó
 * MISSING_LOCAL_FILE          - Archivo físico local no encontrado
 * INVALID_FILE                - Archivo existe pero no es imagen válida (ext/mime/magic bytes)
 * INVALID_PATH                - URL contiene path traversal o ruta no permitida
 * FAILED                      - Error durante la migración (Cloudinary o BD)
 * CLOUDINARY_WITHOUT_GALLERY  - products.image_url → Cloudinary, pero sin product_images
 * ORPHAN_CANDIDATE            - Asset posiblemente huérfano (solo reporte, no se borra)
 */
@Entity
@Table(
    name = "media_migration_logs",
    schema = "ops",
    indexes = {
        @Index(name = "idx_mm_entity_type", columnList = "entity_type"),
        @Index(name = "idx_mm_entity_id",   columnList = "entity_id"),
        @Index(name = "idx_mm_status",       columnList = "status"),
        @Index(name = "idx_mm_migrated_at",  columnList = "migrated_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MediaMigrationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tipo de entidad: PRODUCT_IMAGE_URL, PRODUCT_GALLERY_IMAGE, BRAND_LOGO */
    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    /** ID del producto o marca afectado */
    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    /** URL original (local), ej: http://localhost:8080/uploads/products/archivo.jpg */
    @Column(name = "old_url", columnDefinition = "TEXT")
    private String oldUrl;

    /** URL nueva en Cloudinary después de la migración */
    @Column(name = "new_url", columnDefinition = "TEXT")
    private String newUrl;

    /** public_id asignado en Cloudinary */
    @Column(name = "public_id", length = 500)
    private String publicId;

    /**
     * Estado de la operación.
     * Ver Javadoc de clase para valores válidos.
     */
    @Column(name = "status", nullable = false, length = 50)
    private String status;

    /** Mensaje de error si status = FAILED */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** Modo de invocación: REPORT, DRY_RUN, EXECUTE */
    @Column(name = "action", length = 100)
    private String action;

    @Column(name = "migrated_at", nullable = false)
    @Builder.Default
    private LocalDateTime migratedAt = LocalDateTime.now();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
