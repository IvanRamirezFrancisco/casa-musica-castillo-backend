package com.security.controller.admin;

import com.security.entity.ShippingSettings;
import com.security.service.ShippingSettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/shipping-settings")
public class AdminShippingSettingsController {

    @Autowired
    private ShippingSettingsService service;

    @GetMapping
    @PreAuthorize("hasAuthority('STORE_SETTINGS_READ') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<ShippingSettings> getSettings() {
        return ResponseEntity.ok(service.getSettings());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('STORE_SETTINGS_MANAGE') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<ShippingSettings> updateSettings(@RequestBody ShippingSettings settings) {
        return ResponseEntity.ok(service.updateSettings(settings));
    }
}
