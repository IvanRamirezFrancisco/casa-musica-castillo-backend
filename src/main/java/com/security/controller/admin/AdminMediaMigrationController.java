package com.security.controller.admin;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controlador protegido para la migración controlada de imágenes locales a Cloudinary.
 *
 * FASE 6E: CIERRE DE MIGRACIÓN
 * Todos los endpoints han sido deshabilitados permanentemente (410 Gone).
 * La migración fue ejecutada con éxito y el historial se conserva en BD
 * (ops.media_migration_logs).
 */
@RestController
@RequestMapping("/api/admin/media-migration")
@PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_ADMIN') or hasAuthority('SYSTEM_SETTINGS')")
public class AdminMediaMigrationController {

    private static final String CLOSED_MESSAGE = "La migración multimedia ya fue cerrada. La ejecución manual está deshabilitada.";

    @GetMapping("/report")
    public ResponseEntity<?> generateReport() {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(Map.of("message", CLOSED_MESSAGE));
    }

    @PostMapping("/dry-run")
    public ResponseEntity<?> dryRun() {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(Map.of("message", CLOSED_MESSAGE));
    }

    @PostMapping("/execute")
    public ResponseEntity<?> execute() {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(Map.of("message", CLOSED_MESSAGE));
    }
}
