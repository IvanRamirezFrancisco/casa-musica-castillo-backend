package com.security.controller;

import com.security.dto.help.FaqDto;
import com.security.dto.help.HelpCategoryDto;
import com.security.service.HelpCenterService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/public/help")
@RequiredArgsConstructor
public class PublicHelpController {

    private final HelpCenterService helpCenterService;

    @GetMapping("/categories")
    public ResponseEntity<List<HelpCategoryDto>> getActiveCategories() {
        return ResponseEntity.ok(helpCenterService.getActivePublicCategories());
    }

    @GetMapping("/faqs")
    public ResponseEntity<List<FaqDto>> getActiveFaqs() {
        return ResponseEntity.ok(helpCenterService.getActivePublicFaqs());
    }

    @GetMapping("/faqs/featured")
    public ResponseEntity<List<FaqDto>> getFeaturedFaqs() {
        return ResponseEntity.ok(helpCenterService.getFeaturedPublicFaqs());
    }

    @GetMapping("/search")
    public ResponseEntity<List<FaqDto>> searchFaqs(@RequestParam("q") String query) {
        return ResponseEntity.ok(helpCenterService.searchActivePublicFaqs(query));
    }
}
