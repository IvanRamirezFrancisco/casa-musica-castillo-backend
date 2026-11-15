package com.security.controller.admin;

import com.security.dto.ProductImageResponse;
import com.security.service.ProductImageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/products/{productId}/images")
@PreAuthorize("hasAuthority('PRODUCT_CREATE') or hasAuthority('PRODUCT_UPDATE')")
public class AdminProductImageController {

    private final ProductImageService productImageService;

    public AdminProductImageController(ProductImageService productImageService) {
        this.productImageService = productImageService;
    }

    @GetMapping
    public ResponseEntity<?> getImages(@PathVariable Long productId) {
        try {
            List<ProductImageResponse> responses = productImageService.getProductImages(productId);
            return ResponseEntity.ok(responses);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error al obtener imágenes: " + e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> uploadImage(
            @PathVariable Long productId,
            @RequestParam("file") MultipartFile file) {
        
        try {
            // Nota: En un entorno real, el ID del usuario se obtiene del SecurityContext
            // Long userId = ((CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getId();
            Long uploadedBy = null; 

            ProductImageResponse savedImage = productImageService.uploadProductImage(productId, file, uploadedBy);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Imagen subida exitosamente");
            response.put("data", savedImage);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException | IllegalStateException e) {
            // Maneja error de validación o conflicto (ej. límite de imágenes)
            return ResponseEntity.status(e instanceof IllegalStateException ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{imageId}")
    public ResponseEntity<?> deleteImage(
            @PathVariable Long productId,
            @PathVariable Long imageId) {
        
        try {
            productImageService.deleteImage(productId, imageId);
            return ResponseEntity.ok(Map.of("success", true, "message", "Imagen eliminada"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error al eliminar la imagen: " + e.getMessage()));
        }
    }

    @PatchMapping("/{imageId}/primary")
    public ResponseEntity<?> setPrimaryImage(
            @PathVariable Long productId,
            @PathVariable Long imageId) {
        
        try {
            productImageService.setPrimaryImage(productId, imageId);
            return ResponseEntity.ok(Map.of("success", true, "message", "Imagen principal actualizada"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error al actualizar imagen principal: " + e.getMessage()));
        }
    }
}
