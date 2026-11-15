package com.security.controller;

import com.security.dto.help.FaqDto;
import com.security.dto.help.FaqRequest;
import com.security.dto.help.HelpCategoryDto;
import com.security.dto.help.HelpCategoryRequest;
import com.security.service.HelpCenterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/admin/help")
@RequiredArgsConstructor
public class AdminHelpController {

    private final HelpCenterService helpCenterService;

    // --- Categorías ---

    @GetMapping("/categories")
    @PreAuthorize("hasAuthority('HELP_MANAGE')")
    public ResponseEntity<List<HelpCategoryDto>> getAllCategories() {
        return ResponseEntity.ok(helpCenterService.getAllAdminCategories());
    }

    @PostMapping("/categories")
    @PreAuthorize("hasAuthority('HELP_MANAGE')")
    public ResponseEntity<HelpCategoryDto> createCategory(
            @Valid @RequestBody HelpCategoryRequest request,
            Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(helpCenterService.createCategory(request, principal != null ? principal.getName() : null));
    }

    @PutMapping("/categories/{id}")
    @PreAuthorize("hasAuthority('HELP_MANAGE')")
    public ResponseEntity<HelpCategoryDto> updateCategory(
            @PathVariable Long id,
            @Valid @RequestBody HelpCategoryRequest request,
            Principal principal) {
        return ResponseEntity.ok(helpCenterService.updateCategory(id, request, principal != null ? principal.getName() : null));
    }

    @DeleteMapping("/categories/{id}")
    @PreAuthorize("hasAuthority('HELP_MANAGE')")
    public ResponseEntity<Void> deleteCategory(
            @PathVariable Long id,
            Principal principal) {
        // En caso de conflicto de llaves foráneas, el servicio tira IllegalStateException que
        // deberá ser mapeada a 409 Conflict por un GlobalExceptionHandler
        helpCenterService.deleteCategory(id, principal != null ? principal.getName() : null);
        return ResponseEntity.noContent().build();
    }

    // --- FAQs ---

    @GetMapping("/faqs")
    @PreAuthorize("hasAuthority('HELP_MANAGE')")
    public ResponseEntity<List<FaqDto>> getAllFaqs() {
        return ResponseEntity.ok(helpCenterService.getAllAdminFaqs());
    }

    @PostMapping("/faqs")
    @PreAuthorize("hasAuthority('HELP_MANAGE')")
    public ResponseEntity<FaqDto> createFaq(
            @Valid @RequestBody FaqRequest request,
            Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(helpCenterService.createFaq(request, principal != null ? principal.getName() : null));
    }

    @PutMapping("/faqs/{id}")
    @PreAuthorize("hasAuthority('HELP_MANAGE')")
    public ResponseEntity<FaqDto> updateFaq(
            @PathVariable Long id,
            @Valid @RequestBody FaqRequest request,
            Principal principal) {
        return ResponseEntity.ok(helpCenterService.updateFaq(id, request, principal != null ? principal.getName() : null));
    }

    @DeleteMapping("/faqs/{id}")
    @PreAuthorize("hasAuthority('HELP_MANAGE')")
    public ResponseEntity<Void> deleteFaq(
            @PathVariable Long id,
            Principal principal) {
        helpCenterService.deleteFaq(id, principal != null ? principal.getName() : null);
        return ResponseEntity.noContent().build();
    }
}
