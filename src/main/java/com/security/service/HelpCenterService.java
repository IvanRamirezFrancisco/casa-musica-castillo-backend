package com.security.service;

import com.security.dto.help.FaqDto;
import com.security.dto.help.FaqRequest;
import com.security.dto.help.HelpCategoryDto;
import com.security.dto.help.HelpCategoryRequest;
import com.security.entity.Faq;
import com.security.entity.HelpCategory;
import com.security.exception.ResourceNotFoundException;
import com.security.repository.FaqRepository;
import com.security.repository.HelpCategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class HelpCenterService {

    private final HelpCategoryRepository categoryRepository;
    private final FaqRepository faqRepository;
    private final AuditLogService auditLogService;

    // =========================================================================
    // MÉTODOS PÚBLICOS
    // =========================================================================

    @Transactional(readOnly = true)
    public List<HelpCategoryDto> getActivePublicCategories() {
        return categoryRepository.findByActiveTrueOrderByDisplayOrderAsc().stream()
                .map(this::mapCategoryToDtoPublic)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<FaqDto> getActivePublicFaqs() {
        return faqRepository.findAllActivePublic().stream()
                .map(this::mapFaqToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<FaqDto> getFeaturedPublicFaqs() {
        return faqRepository.findFeaturedPublic().stream()
                .map(this::mapFaqToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<FaqDto> searchActivePublicFaqs(String query) {
        if (query == null || query.trim().length() < 2) {
            return List.of(); // Evitar búsquedas costosas con query muy corto
        }
        
        // Limpiar un poco el query antes de pasarlo, máximo 150 caracteres para evitar payload enorme
        String safeQuery = query.trim();
        if (safeQuery.length() > 150) {
            safeQuery = safeQuery.substring(0, 150);
        }
        
        return faqRepository.searchActivePublic(safeQuery).stream()
                .limit(20) // Limitar a 20 resultados en vista pública
                .map(this::mapFaqToDto)
                .collect(Collectors.toList());
    }

    // =========================================================================
    // MÉTODOS ADMINISTRATIVOS
    // =========================================================================

    // --- Categories ---

    @Transactional(readOnly = true)
    public List<HelpCategoryDto> getAllAdminCategories() {
        return categoryRepository.findAll().stream()
                .map(this::mapCategoryToDtoAdmin)
                .collect(Collectors.toList());
    }

    @Transactional
    public HelpCategoryDto createCategory(HelpCategoryRequest request, String adminEmail) {
        HelpCategory category = new HelpCategory();
        mapCategoryRequestToEntity(request, category);
        category.setSlug(generateUniqueSlug(request.getName(), null));
        
        HelpCategory saved = categoryRepository.save(category);
        logAdminAction("HELP_CATEGORY_CREATE", adminEmail, saved.getId(), saved.getName());
        return mapCategoryToDtoAdmin(saved);
    }

    @Transactional
    public HelpCategoryDto updateCategory(Long id, HelpCategoryRequest request, String adminEmail) {
        HelpCategory category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Categoría no encontrada con ID: " + id));
        
        String oldName = category.getName();
        mapCategoryRequestToEntity(request, category);
        
        // Si cambió el nombre significativamente, quizá se deba regenerar el slug
        if (!oldName.equalsIgnoreCase(category.getName())) {
             category.setSlug(generateUniqueSlug(category.getName(), category.getId()));
        }
        
        HelpCategory saved = categoryRepository.save(category);
        logAdminAction("HELP_CATEGORY_UPDATE", adminEmail, saved.getId(), saved.getName());
        return mapCategoryToDtoAdmin(saved);
    }

    @Transactional
    public void deleteCategory(Long id, String adminEmail) {
        HelpCategory category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Categoría no encontrada con ID: " + id));
        
        if (faqRepository.existsByCategoryId(id)) {
            // Regla de integridad requerida (Punto 17)
            throw new com.security.exception.DuplicateActionException("No puedes eliminar esta categoría porque contiene preguntas. Desactívala o reasigna sus preguntas.");
        }
        
        String name = category.getName();
        categoryRepository.delete(category);
        logAdminAction("HELP_CATEGORY_DELETE", adminEmail, id, name);
    }

    // --- FAQs ---

    @Transactional(readOnly = true)
    public List<FaqDto> getAllAdminFaqs() {
        return faqRepository.findAll().stream()
                .map(this::mapFaqToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public FaqDto createFaq(FaqRequest request, String adminEmail) {
        HelpCategory category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Categoría no encontrada"));

        Faq faq = new Faq();
        mapFaqRequestToEntity(request, faq, category);
        
        Faq saved = faqRepository.save(faq);
        logAdminAction("FAQ_CREATE", adminEmail, saved.getId(), saved.getQuestion());
        return mapFaqToDto(saved);
    }

    @Transactional
    public FaqDto updateFaq(Long id, FaqRequest request, String adminEmail) {
        Faq faq = faqRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FAQ no encontrada con ID: " + id));
        
        HelpCategory category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Categoría no encontrada"));

        mapFaqRequestToEntity(request, faq, category);
        
        Faq saved = faqRepository.save(faq);
        logAdminAction("FAQ_UPDATE", adminEmail, saved.getId(), saved.getQuestion());
        return mapFaqToDto(saved);
    }

    @Transactional
    public void deleteFaq(Long id, String adminEmail) {
        Faq faq = faqRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FAQ no encontrada con ID: " + id));
        
        String question = faq.getQuestion();
        faqRepository.delete(faq);
        logAdminAction("FAQ_DELETE", adminEmail, id, question);
    }

    // =========================================================================
    // MAPPERS Y HELPERS
    // =========================================================================

    private HelpCategoryDto mapCategoryToDtoPublic(HelpCategory category) {
        return HelpCategoryDto.builder()
                .id(category.getId())
                .name(category.getName())
                .slug(category.getSlug())
                .description(category.getDescription())
                .iconKey(category.getIconKey())
                .displayOrder(category.getDisplayOrder())
                .activeFaqCount(faqRepository.countActiveFaqsByCategoryId(category.getId()))
                .build();
    }

    private HelpCategoryDto mapCategoryToDtoAdmin(HelpCategory category) {
        HelpCategoryDto dto = mapCategoryToDtoPublic(category);
        dto.setActive(category.isActive());
        dto.setCreatedAt(category.getCreatedAt());
        dto.setUpdatedAt(category.getUpdatedAt());
        return dto;
    }

    private void mapCategoryRequestToEntity(HelpCategoryRequest request, HelpCategory category) {
        category.setName(request.getName().trim());
        if (request.getDescription() != null) category.setDescription(request.getDescription().trim());
        if (request.getIconKey() != null) category.setIconKey(request.getIconKey().trim());
        if (request.getDisplayOrder() != null) category.setDisplayOrder(request.getDisplayOrder());
        if (request.getActive() != null) category.setActive(request.getActive());
    }

    private FaqDto mapFaqToDto(Faq faq) {
        return FaqDto.builder()
                .id(faq.getId())
                .categoryId(faq.getCategory().getId())
                .categoryName(faq.getCategory().getName())
                .question(faq.getQuestion())
                .answer(faq.getAnswer())
                .displayOrder(faq.getDisplayOrder())
                .active(faq.isActive())
                .featured(faq.isFeatured())
                .createdAt(faq.getCreatedAt())
                .updatedAt(faq.getUpdatedAt())
                .build();
    }

    private void mapFaqRequestToEntity(FaqRequest request, Faq faq, HelpCategory category) {
        faq.setCategory(category);
        faq.setQuestion(request.getQuestion().trim());
        faq.setAnswer(request.getAnswer().trim()); // TEXTO PLANO
        if (request.getDisplayOrder() != null) faq.setDisplayOrder(request.getDisplayOrder());
        if (request.getActive() != null) faq.setActive(request.getActive());
        if (request.getFeatured() != null) faq.setFeatured(request.getFeatured());
    }

    private String generateUniqueSlug(String name, Long excludeId) {
        String baseSlug = Normalizer.normalize(name.toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        
        String slug = baseSlug;
        int counter = 1;
        while (excludeId == null ? categoryRepository.existsBySlug(slug) : categoryRepository.existsBySlugAndIdNot(slug, excludeId)) {
            slug = baseSlug + "-" + counter;
            counter++;
        }
        return slug;
    }

    private void logAdminAction(String action, String email, Long entityId, String detail) {
        try {
            auditLogService.logAction(
                    action,
                    "HELP_CENTER",
                    entityId,
                    detail
            );
        } catch (Exception e) {
            log.error("Error al registrar auditoría de Help Center: {}", e.getMessage());
        }
    }
}
