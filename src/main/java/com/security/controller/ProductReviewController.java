package com.security.controller;

import com.security.dto.ReviewDTO;
import com.security.exception.ResourceNotFoundException;
import com.security.service.ProductReviewService;
import com.security.service.ReviewHelpfulnessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Controller para gestión de reseñas de productos
 * Endpoints: /api/reviews
 * CORS se maneja globalmente en SecurityConfig
 */
@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
@Slf4j
public class ProductReviewController {

    private final ProductReviewService reviewService;
    private final ReviewHelpfulnessService helpfulnessService;

    /**
     * Crea una nueva reseña
     * POST /api/reviews
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ReviewDTO.ReviewResponse> createReview(
            @Valid @RequestBody ReviewDTO.CreateReviewRequest request,
            Authentication authentication) {

        log.info("Usuario {} creando reseña para producto {}",
                authentication.getName(), request.getProductId());

        Long userId = getUserIdFromAuth(authentication);
        var response = reviewService.createReview(userId, request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Verifica la elegibilidad del usuario para reseñar un producto
     * GET /api/reviews/product/{productId}/eligibility
     */
    @GetMapping("/product/{productId}/eligibility")
    public ResponseEntity<ReviewDTO.EligibilityResponse> checkEligibility(
            @PathVariable Long productId,
            Authentication authentication) {
        
        log.info("Verificando elegibilidad para producto {}", productId);
        
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
            return ResponseEntity.ok(ReviewDTO.EligibilityResponse.builder()
                    .canReview(false)
                    .hasPurchased(false)
                    .alreadyReviewed(false)
                    .build());
        }
        
        Long userId = getUserIdFromAuth(authentication);
        if (userId == null) {
             return ResponseEntity.ok(ReviewDTO.EligibilityResponse.builder()
                    .canReview(false)
                    .hasPurchased(false)
                    .alreadyReviewed(false)
                    .build());
        }

        var response = reviewService.checkEligibility(productId, userId);
        return ResponseEntity.ok(response);
    }

    /**
     * Obtiene reseñas de un producto con filtros
     * GET /api/reviews/product/{productId}
     */
    @GetMapping("/product/{productId}")
    public ResponseEntity<ReviewDTO.ReviewListResponse> getProductReviews(
            @PathVariable Long productId,
            @RequestParam(required = false) Integer rating,
            @RequestParam(required = false) Boolean verifiedOnly,
            @RequestParam(defaultValue = "RECENT") String sortBy,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        log.info("Obteniendo reseñas del producto {} con filtros: rating={}, verified={}, sort={}",
                productId, rating, verifiedOnly, sortBy);

        ReviewDTO.ReviewFilters filters = ReviewDTO.ReviewFilters.builder()
                .productId(productId)
                .rating(rating)
                .verifiedOnly(verifiedOnly)
                .sortBy(sortBy)
                .page(page)
                .size(size)
                .build();

        var response = reviewService.getProductReviews(productId, filters);

        return ResponseEntity.ok(response);
    }

    /**
     * Obtiene estadísticas de reseñas de un producto
     * GET /api/reviews/product/{productId}/statistics
     */
    @GetMapping("/product/{productId}/statistics")
    public ResponseEntity<ReviewDTO.RatingStatistics> getReviewStatistics(@PathVariable Long productId) {
        log.info("Obteniendo estadísticas de reseñas del producto {}", productId);

        var statistics = reviewService.getReviewStatistics(productId);
        return ResponseEntity.ok(statistics);
    }

    /**
     * Obtiene las reseñas del usuario autenticado
     * GET /api/reviews/my-reviews
     */
    @GetMapping("/my-reviews")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ReviewDTO.ReviewResponse>> getMyReviews(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        log.info("Usuario {} obteniendo sus reseñas", authentication.getName());

        Long userId = getUserIdFromAuth(authentication);
        var reviews = reviewService.getUserReviews(userId, page, size);

        return ResponseEntity.ok(reviews);
    }

    /**
     * Obtiene reseñas pendientes de aprobación (ADMIN)
     * GET /api/reviews/pending
     */
    @GetMapping("/pending")
    @PreAuthorize("hasAuthority('PRODUCT_READ')")
    public ResponseEntity<List<ReviewDTO.ReviewResponse>> getPendingReviews(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        log.info("Admin obteniendo reseñas pendientes de aprobación");

        var reviews = reviewService.getPendingReviews(page, size);

        return ResponseEntity.ok(reviews);
    }

    /**
     * Obtiene una reseña específica por ID
     * GET /api/reviews/{reviewId}
     */
    @GetMapping("/{reviewId}")
    public ResponseEntity<ReviewDTO.ReviewResponse> getReview(@PathVariable Long reviewId) {
        log.info("Obteniendo reseña {}", reviewId);

        // Crear filters básico para obtener la reseña
        ReviewDTO.ReviewFilters filters = ReviewDTO.ReviewFilters.builder()
                .page(0)
                .size(1)
                .build();

        var reviewList = reviewService.getProductReviews(reviewId, filters);
        var review = reviewList.getReviews().stream().findFirst().orElse(null);

        if (review == null) {
            throw new ResourceNotFoundException("Review", "id", reviewId);
        }

        return ResponseEntity.ok(review);
    }

    /**
     * Actualiza una reseña existente
     * PUT /api/reviews/{reviewId}
     */
    @PutMapping("/{reviewId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ReviewDTO.ReviewResponse> updateReview(
            @PathVariable Long reviewId,
            @Valid @RequestBody ReviewDTO.UpdateReviewRequest request,
            Authentication authentication) {

        log.info("Usuario {} actualizando reseña {}", authentication.getName(), reviewId);

        Long userId = getUserIdFromAuth(authentication);
        var response = reviewService.updateReview(reviewId, userId, request);

        return ResponseEntity.ok(response);
    }

    /**
     * Elimina una reseña
     * DELETE /api/reviews/{reviewId}
     */
    @DeleteMapping("/{reviewId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, String>> deleteReview(
            @PathVariable Long reviewId,
            Authentication authentication) {

        log.info("Usuario {} eliminando reseña {}", authentication.getName(), reviewId);

        Long userId = getUserIdFromAuth(authentication);
        reviewService.deleteReview(reviewId, userId);

        return ResponseEntity.ok(Map.of("message", "Reseña eliminada exitosamente"));
    }

    /**
     * Aprueba una reseña (ADMIN)
     * POST /api/reviews/{reviewId}/approve
     */
    @PostMapping("/{reviewId}/approve")
    @PreAuthorize("hasAuthority('PRODUCT_UPDATE')")
    public ResponseEntity<ReviewDTO.ReviewResponse> approveReview(@PathVariable Long reviewId) {
        log.info("Admin aprobando reseña {}", reviewId);

        var response = reviewService.approveReview(reviewId);
        return ResponseEntity.ok(response);
    }

    /**
     * Rechaza una reseña (ADMIN)
     * POST /api/reviews/{reviewId}/reject
     */
    @PostMapping("/{reviewId}/reject")
    @PreAuthorize("hasAuthority('PRODUCT_UPDATE')")
    public ResponseEntity<ReviewDTO.ReviewResponse> rejectReview(@PathVariable Long reviewId) {
        log.info("Admin rechazando reseña {}", reviewId);

        var response = reviewService.rejectReview(reviewId);
        return ResponseEntity.ok(response);
    }

    /**
     * Agrega respuesta del vendedor (ADMIN)
     * POST /api/reviews/{reviewId}/seller-response
     */
    @PostMapping("/{reviewId}/seller-response")
    @PreAuthorize("hasAuthority('PRODUCT_UPDATE')")
    public ResponseEntity<ReviewDTO.ReviewResponse> addSellerResponse(
            @PathVariable Long reviewId,
            @Valid @RequestBody ReviewDTO.SellerResponseRequest request) {

        log.info("Admin agregando respuesta del vendedor a reseña {}", reviewId);

        var response = reviewService.addSellerResponse(reviewId, request.getResponse());
        return ResponseEntity.ok(response);
    }

    /**
     * Vota si una reseña es útil
     * POST /api/reviews/{reviewId}/vote
     */
    @PostMapping("/{reviewId}/vote")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> voteHelpful(
            @PathVariable Long reviewId,
            @Valid @RequestBody ReviewDTO.VoteHelpfulRequest request,
            Authentication authentication) {

        log.info("Usuario {} votando en reseña {}: helpful={}",
                authentication.getName(), reviewId, request.getIsHelpful());

        Long userId = getUserIdFromAuth(authentication);
        reviewService.voteHelpful(reviewId, userId, request.getIsHelpful());

        // Obtener contadores actualizados
        var counters = helpfulnessService.getVoteCounters(reviewId);

        return ResponseEntity.ok(Map.of(
                "message", "Voto registrado exitosamente",
                "helpfulCount", counters.helpfulCount(),
                "notHelpfulCount", counters.notHelpfulCount(),
                "totalVotes", counters.getTotalVotes(),
                "helpfulPercentage", counters.getHelpfulPercentage()));
    }

    /**
     * Cambia el voto de una reseña
     * PUT /api/reviews/{reviewId}/vote
     */
    @PutMapping("/{reviewId}/vote")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> changeVote(
            @PathVariable Long reviewId,
            @Valid @RequestBody ReviewDTO.VoteHelpfulRequest request,
            Authentication authentication) {

        log.info("Usuario {} cambiando voto en reseña {}", authentication.getName(), reviewId);

        Long userId = getUserIdFromAuth(authentication);
        helpfulnessService.changeVote(reviewId, userId, request.getIsHelpful());

        var counters = helpfulnessService.getVoteCounters(reviewId);

        return ResponseEntity.ok(Map.of(
                "message", "Voto actualizado exitosamente",
                "helpfulCount", counters.helpfulCount(),
                "notHelpfulCount", counters.notHelpfulCount(),
                "totalVotes", counters.getTotalVotes(),
                "helpfulPercentage", counters.getHelpfulPercentage()));
    }

    /**
     * Elimina el voto de una reseña
     * DELETE /api/reviews/{reviewId}/vote
     */
    @DeleteMapping("/{reviewId}/vote")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, String>> removeVote(
            @PathVariable Long reviewId,
            Authentication authentication) {

        log.info("Usuario {} eliminando voto en reseña {}", authentication.getName(), reviewId);

        Long userId = getUserIdFromAuth(authentication);
        helpfulnessService.removeVote(reviewId, userId);

        return ResponseEntity.ok(Map.of("message", "Voto eliminado exitosamente"));
    }

    /**
     * Verifica si el usuario ha votado en una reseña
     * GET /api/reviews/{reviewId}/my-vote
     */
    @GetMapping("/{reviewId}/my-vote")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> getMyVote(
            @PathVariable Long reviewId,
            Authentication authentication) {

        Long userId = getUserIdFromAuth(authentication);
        boolean hasVoted = helpfulnessService.hasUserVoted(reviewId, userId);

        Map<String, Object> response = Map.of(
                "hasVoted", hasVoted,
                "isHelpful", hasVoted ? helpfulnessService.getUserVote(reviewId, userId) : null);

        return ResponseEntity.ok(response);
    }

    /**
     * Extrae el ID del usuario desde la autenticación
     */
    private Long getUserIdFromAuth(Authentication authentication) {
        try {
            Object principal = authentication.getPrincipal();
            if (principal instanceof org.springframework.security.core.userdetails.UserDetails userDetails) {
                return Long.parseLong(userDetails.getUsername());
            }
            return null;
        } catch (Exception e) {
            log.error("Error al extraer userId de authentication: {}", e.getMessage());
            return null;
        }
    }
}
