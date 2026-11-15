package com.security.controller.alexa;

import com.security.dto.alexa.*;
import com.security.service.alexa.AlexaCategoryQueryService;
import com.security.service.alexa.AlexaProductQueryService;
import com.security.service.alexa.AlexaRecommendationService;
import com.security.service.alexa.AlexaStoreInfoService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/alexa")
@RequiredArgsConstructor
@Validated
public class AlexaController {

    private final AlexaProductQueryService alexaProductQueryService;
    private final AlexaCategoryQueryService alexaCategoryQueryService;
    private final AlexaStoreInfoService alexaStoreInfoService;
    private final AlexaRecommendationService alexaRecommendationService;

    @GetMapping("/products/search")
    public ResponseEntity<AlexaProductSearchResponseDTO> searchProducts(
            @RequestParam @NotBlank(message = "El término de búsqueda no puede estar vacío") @Size(min = 2, max = 100, message = "El término de búsqueda debe tener entre 2 y 100 caracteres") String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(20) int size) {
        return ResponseEntity.ok(alexaProductQueryService.searchProducts(q, page, size));
    }

    @GetMapping("/products/category/{category}")
    public ResponseEntity<AlexaProductSearchResponseDTO> getProductsByCategory(
            @PathVariable @NotBlank @Size(min = 2, max = 100) String category,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(20) int size) {
        return ResponseEntity.ok(alexaProductQueryService.getProductsByCategory(category, page, size));
    }

    @GetMapping("/products/{id}")
    public ResponseEntity<AlexaProductDetailDTO> getProductById(@PathVariable @Min(1) Long id) {
        AlexaProductDetailDTO product = alexaProductQueryService.getProductById(id);
        if (product == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(product);
    }

    @GetMapping("/products/offers")
    public ResponseEntity<AlexaProductSearchResponseDTO> getOffers(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(20) int size) {
        return ResponseEntity.ok(alexaProductQueryService.getOffers(page, size));
    }

    @GetMapping("/categories")
    public ResponseEntity<List<AlexaCategoryDTO>> getCategories() {
        return ResponseEntity.ok(alexaCategoryQueryService.getCategories());
    }

    @GetMapping("/store/info")
    public ResponseEntity<AlexaStoreInfoDTO> getStoreInfo() {
        return ResponseEntity.ok(alexaStoreInfoService.getStoreInfo());
    }

    @GetMapping("/recommendations")
    public ResponseEntity<AlexaRecommendationDTO> getRecommendations(
            @RequestParam(required = false) @Size(max = 100) String category,
            @RequestParam(required = false) @Size(max = 50) String level,
            @RequestParam(required = false) @Min(1) Integer budget,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "5") @Min(1) @Max(10) int size) {
        return ResponseEntity.ok(alexaRecommendationService.getRecommendations(category, level, budget, page, size));
    }
}
