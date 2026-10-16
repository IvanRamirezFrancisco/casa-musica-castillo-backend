package com.security.service;

import com.security.dto.ReviewDTO;
import com.security.entity.*;
import com.security.exception.*;
import com.security.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.ParameterMode;
import jakarta.persistence.StoredProcedureQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service para gestión de reseñas de productos
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProductReviewService {

    private final ProductReviewRepository reviewRepository;
    private final ReviewHelpfulnessRepository helpfulnessRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final EntityManager entityManager;
    private final ReviewContentPolicyService policyService;

    @Transactional
    public ReviewDTO.ReviewResponse createReview(Long userId, ReviewDTO.CreateReviewRequest request) {
        log.info("Usuario {} creando reseña para producto {}", userId, request.getProductId());

        // Validar estricta elegibilidad de compra
        boolean hasPurchased = orderRepository.hasUserPurchasedProduct(userId, request.getProductId());
        if (!hasPurchased) {
            throw new UnauthorizedException("Debes haber comprado este producto en una orden válida para poder reseñarlo");
        }

        // Validar que el usuario no haya reseñado ya este producto
        if (reviewRepository.existsByProductIdAndUserId(request.getProductId(), userId)) {
            throw new DuplicateActionException("Ya has reseñado este producto");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));

        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Producto no encontrado"));

        // Aplicar políticas de moderación
        var policyResult = policyService.evaluate(request.getTitle(), request.getComment());
        if (policyResult.getResult() == ReviewContentPolicyService.PolicyResult.REJECT) {
            throw new BadRequestException("El contenido de la reseña infringe nuestras políticas de la comunidad.");
        }

        // Crear la reseña
        ProductReview review = new ProductReview();
        review.setProduct(product);
        review.setUser(user);
        review.setRating(request.getRating());
        review.setTitle(request.getTitle() != null ? request.getTitle().trim() : null);
        review.setComment(request.getComment().trim());
        
        // Mantener política normal (PENDING siempre) o forzar si la política lo exige
        review.setStatus("PENDING"); 
        
        // El backend toma la autoridad de la orden válida, ignorando lo que envíe el cliente
        List<Order> validOrders = orderRepository.findValidOrdersForProduct(userId, request.getProductId());
        if (!validOrders.isEmpty()) {
            review.setOrder(validOrders.get(0));
        }
        review.setVerifiedPurchase(true);

        ProductReview savedReview = reviewRepository.save(review);

        log.info("Reseña creada exitosamente: ID {}", savedReview.getId());
        return buildReviewResponse(savedReview, userId);
    }

    @Transactional
    public ReviewDTO.ReviewResponse updateReview(Long reviewId, Long userId, ReviewDTO.UpdateReviewRequest request) {
        log.info("Actualizando reseña {}", reviewId);

        ProductReview review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Reseña no encontrada"));

        // Verificar que el usuario sea el dueño
        if (!review.getUser().getId().equals(userId)) {
            throw new UnauthorizedException("No tienes permiso para editar esta reseña");
        }

        // Aplicar políticas de moderación al actualizar
        var policyResult = policyService.evaluate(request.getTitle(), request.getComment());
        if (policyResult.getResult() == ReviewContentPolicyService.PolicyResult.REJECT) {
            throw new BadRequestException("El contenido de la reseña infringe nuestras políticas de la comunidad.");
        }

        String oldStatus = review.getStatus();
        review.setRating(request.getRating());
        review.setTitle(request.getTitle() != null ? request.getTitle().trim() : null);
        review.setComment(request.getComment().trim());
        review.setStatus("PENDING"); // Volver a revisar después de editar

        ProductReview updatedReview = reviewRepository.save(review);
        
        // Recalcular rating si estaba previamente aprobada, pues acaba de pasar a PENDING
        if ("APPROVED".equals(oldStatus)) {
            recalculateProductRating(review.getProduct().getId());
        }

        log.info("Reseña {} actualizada", reviewId);
        return buildReviewResponse(updatedReview, userId);
    }

    /**
     * Elimina una reseña
     */
    @Transactional
    public void deleteReview(Long reviewId, Long userId) {
        log.info("Eliminando reseña {}", reviewId);

        ProductReview review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Reseña no encontrada"));

        if (!review.getUser().getId().equals(userId)) {
            throw new UnauthorizedException("No tienes permiso para eliminar esta reseña");
        }

        Long productId = review.getProduct().getId();

        // Eliminar votos de utilidad asociados
        helpfulnessRepository.deleteByReviewId(reviewId);

        // Eliminar reseña
        reviewRepository.delete(review);

        // Recalcular rating
        recalculateProductRating(productId);

        log.info("Reseña {} eliminada", reviewId);
    }

    /**
     * Aprueba una reseña (ADMIN)
     */
    @Transactional
    public ReviewDTO.ReviewResponse approveReview(Long reviewId) {
        log.info("Aprobando reseña {}", reviewId);

        ProductReview review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Reseña no encontrada"));

        review.setStatus("APPROVED");
        ProductReview approvedReview = reviewRepository.save(review);

        // Recalcular rating del producto
        recalculateProductRating(review.getProduct().getId());

        log.info("Reseña {} aprobada", reviewId);
        return buildReviewResponse(approvedReview, null);
    }

    @Transactional
    public ReviewDTO.ReviewResponse rejectReview(Long reviewId) {
        log.info("Rechazando reseña {}", reviewId);

        ProductReview review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Reseña no encontrada"));

        String oldStatus = review.getStatus();
        review.setStatus("REJECTED");
        ProductReview rejectedReview = reviewRepository.save(review);
        
        if ("APPROVED".equals(oldStatus)) {
            recalculateProductRating(review.getProduct().getId());
        }

        log.info("Reseña {} rechazada", reviewId);
        return buildReviewResponse(rejectedReview, null);
    }

    /**
     * Agrega respuesta del vendedor
     */
    @Transactional
    public ReviewDTO.ReviewResponse addSellerResponse(Long reviewId, String response) {
        log.info("Agregando respuesta del vendedor a reseña {}", reviewId);

        ProductReview review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Reseña no encontrada"));

        review.setSellerResponse(response);
        review.setSellerResponseAt(LocalDateTime.now());

        ProductReview updatedReview = reviewRepository.save(review);

        log.info("Respuesta agregada a reseña {}", reviewId);
        return buildReviewResponse(updatedReview, null);
    }

    /**
     * Obtiene reseñas de un producto con filtros
     */
    @Transactional(readOnly = true)
    public ReviewDTO.ReviewListResponse getProductReviews(Long productId, ReviewDTO.ReviewFilters filters) {
        log.info("Obteniendo reseñas del producto {}", productId);

        // Verificar que el producto existe
        productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", "id", productId));

        int page = filters.getPage() != null ? filters.getPage() : 0;
        int size = filters.getSize() != null ? filters.getSize() : 10;
        Pageable pageable = PageRequest.of(page, size);

        Page<ProductReview> reviewPage;

        // Aplicar filtros
        if (filters.getRating() != null) {
            reviewPage = reviewRepository.findByProductIdAndRating(productId, filters.getRating(), pageable);
        } else if (Boolean.TRUE.equals(filters.getVerifiedOnly())) {
            reviewPage = reviewRepository.findVerifiedByProductId(productId, pageable);
        } else if ("HELPFUL".equals(filters.getSortBy())) {
            reviewPage = reviewRepository.findMostHelpfulByProductId(productId, pageable);
        } else {
            reviewPage = reviewRepository.findApprovedByProductId(productId, pageable);
        }

        List<ReviewDTO.ReviewResponse> reviewResponses = reviewPage.getContent().stream()
                .map(review -> buildReviewResponse(review, filters.getUserId()))
                .collect(Collectors.toList());

        ReviewDTO.RatingStatistics stats = getReviewStatistics(productId);

        return ReviewDTO.ReviewListResponse.builder()
                .reviews(reviewResponses)
                .totalReviews((int) reviewPage.getTotalElements())
                .currentPage(page)
                .totalPages(reviewPage.getTotalPages())
                .statistics(stats)
                .build();
    }

    /**
     * Obtiene estadísticas de calificaciones de un producto
     */
    @Transactional(readOnly = true)
    public ReviewDTO.RatingStatistics getReviewStatistics(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Producto no encontrado"));

        Integer totalReviews = product.getReviewCount();

        if (totalReviews == 0) {
            return ReviewDTO.RatingStatistics.builder()
                    .averageRating(0.0)
                    .totalReviews(0)
                    .fiveStarCount(0)
                    .fourStarCount(0)
                    .threeStarCount(0)
                    .twoStarCount(0)
                    .oneStarCount(0)
                    .fiveStarPercentage(0.0)
                    .fourStarPercentage(0.0)
                    .threeStarPercentage(0.0)
                    .twoStarPercentage(0.0)
                    .oneStarPercentage(0.0)
                    .build();
        }

        double total = totalReviews.doubleValue();

        return ReviewDTO.RatingStatistics.builder()
                .averageRating(product.getAverageRating().doubleValue())
                .totalReviews(totalReviews)
                .fiveStarCount(product.getFiveStarCount())
                .fourStarCount(product.getFourStarCount())
                .threeStarCount(product.getThreeStarCount())
                .twoStarCount(product.getTwoStarCount())
                .oneStarCount(product.getOneStarCount())
                .fiveStarPercentage((product.getFiveStarCount() / total) * 100)
                .fourStarPercentage((product.getFourStarCount() / total) * 100)
                .threeStarPercentage((product.getThreeStarCount() / total) * 100)
                .twoStarPercentage((product.getTwoStarCount() / total) * 100)
                .oneStarPercentage((product.getOneStarCount() / total) * 100)
                .build();
    }

    /**
     * Obtiene reseñas de un usuario
     */
    @Transactional(readOnly = true)
    public List<ReviewDTO.ReviewResponse> getUserReviews(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<ProductReview> reviewPage = reviewRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);

        return reviewPage.getContent().stream()
                .map(review -> buildReviewResponse(review, userId))
                .collect(Collectors.toList());
    }

    /**
     * Obtiene reseñas pendientes de aprobación (ADMIN)
     */
    @Transactional(readOnly = true)
    public List<ReviewDTO.ReviewResponse> getPendingReviews(int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<ProductReview> reviewPage = reviewRepository.findPendingReviews(pageable);

        return reviewPage.getContent().stream()
                .map(review -> buildReviewResponse(review, null))
                .collect(Collectors.toList());
    }

    /**
     * Vota por utilidad de una reseña
     */
    @Transactional
    public ReviewDTO.ReviewResponse voteHelpful(Long reviewId, Long userId, Boolean isHelpful) {
        log.info("Usuario {} votando {} en reseña {}", userId, isHelpful ? "útil" : "no útil", reviewId);

        ProductReview review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Reseña no encontrada"));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));

        // Verificar si ya votó
        if (helpfulnessRepository.existsByReviewIdAndUserId(reviewId, userId)) {
            throw new DuplicateActionException("Ya has votado por esta reseña");
        }

        // Crear voto
        ReviewHelpfulness vote = new ReviewHelpfulness();
        vote.setReview(review);
        vote.setUser(user);
        vote.setIsHelpful(isHelpful);

        helpfulnessRepository.save(vote);

        // Actualizar contadores en la reseña
        if (isHelpful) {
            review.incrementHelpfulCount();
        } else {
            review.incrementNotHelpfulCount();
        }

        reviewRepository.save(review);

        log.info("Voto registrado para reseña {}", reviewId);
        return buildReviewResponse(review, userId);
    }

    /**
     * Recalcula el rating de un producto usando stored procedure
     */
    @Transactional
    public void recalculateProductRating(Long productId) {
        log.info("Recalculando rating del producto {}", productId);

        try {
            StoredProcedureQuery query = entityManager
                    .createStoredProcedureQuery("catalog.sp_recalculate_product_rating");
            query.registerStoredProcedureParameter("p_product_id", Long.class, ParameterMode.IN);
            query.setParameter("p_product_id", productId);

            query.execute();

            log.info("Rating recalculado para producto {}", productId);
        } catch (Exception e) {
            log.error("Error al recalcular rating: {}", e.getMessage());
        }
    }

    /**
     * Construye response de una reseña
     */
    private ReviewDTO.ReviewResponse buildReviewResponse(ProductReview review, Long currentUserId) {
        ReviewDTO.ReviewResponse.ReviewResponseBuilder builder = ReviewDTO.ReviewResponse.builder()
                .reviewId(review.getId())
                .productId(review.getProduct().getId())
                .productName(review.getProduct().getName())
                .userId(review.getUser().getId())
                .userName(review.getUser().getFirstName() + " " + review.getUser().getLastName())
                .rating(review.getRating())
                .title(review.getTitle())
                .comment(review.getComment())
                .verifiedPurchase(review.getVerifiedPurchase())
                .status(review.getStatus())
                .helpfulCount(review.getHelpfulCount())
                .notHelpfulCount(review.getNotHelpfulCount())
                .sellerResponse(review.getSellerResponse())
                .sellerResponseAt(review.getSellerResponseAt())
                .createdAt(review.getCreatedAt());

        // Si hay usuario actual, verificar si ya votó
        if (currentUserId != null) {
            boolean voted = helpfulnessRepository.existsByReviewIdAndUserId(review.getId(), currentUserId);
            builder.currentUserVoted(voted);

            if (voted) {
                ReviewHelpfulness vote = helpfulnessRepository
                        .findByReviewIdAndUserId(review.getId(), currentUserId)
                        .orElse(null);
                builder.currentUserVoteHelpful(vote != null ? vote.getIsHelpful() : null);
            }
        }

        return builder.build();
    }
    
    /**
     * Verifica la elegibilidad de un usuario para reseñar un producto
     */
    @Transactional(readOnly = true)
    public ReviewDTO.EligibilityResponse checkEligibility(Long productId, Long userId) {
        log.info("Verificando elegibilidad de usuario {} para producto {}", userId, productId);
        
        productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Producto no encontrado"));
                
        boolean hasPurchased = orderRepository.hasUserPurchasedProduct(userId, productId);
        ProductReview existingReview = reviewRepository.findByProductIdAndUserId(productId, userId).orElse(null);
        
        boolean alreadyReviewed = existingReview != null;
        boolean canReview = hasPurchased && !alreadyReviewed;
        
        return ReviewDTO.EligibilityResponse.builder()
                .canReview(canReview)
                .hasPurchased(hasPurchased)
                .alreadyReviewed(alreadyReviewed)
                .reviewId(alreadyReviewed ? existingReview.getId() : null)
                .reviewStatus(alreadyReviewed ? existingReview.getStatus() : null)
                .build();
    }
}
