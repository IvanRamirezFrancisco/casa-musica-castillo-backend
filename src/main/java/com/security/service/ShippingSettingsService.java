package com.security.service;

import com.security.entity.ShippingSettings;
import com.security.repository.ShippingSettingsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ShippingSettingsService {

    @Autowired
    private ShippingSettingsRepository repository;

    public ShippingSettings getSettings() {
        return repository.findById(1L).orElse(new ShippingSettings());
    }

    public ShippingSettings updateSettings(ShippingSettings newSettings) {
        ShippingSettings current = getSettings();
        
        current.setPickupEnabled(newSettings.getPickupEnabled());
        current.setPickupLabel(newSettings.getPickupLabel());
        current.setPickupAddress(newSettings.getPickupAddress());
        current.setPickupInstructions(newSettings.getPickupInstructions());
        current.setPickupHours(newSettings.getPickupHours());
        
        current.setLocalDeliveryEnabled(newSettings.getLocalDeliveryEnabled());
        current.setLocalDeliveryCost(newSettings.getLocalDeliveryCost());
        current.setLocalDeliveryState(newSettings.getLocalDeliveryState());
        current.setLocalDeliveryCity(newSettings.getLocalDeliveryCity());
        current.setLocalDeliveryPostalCodes(newSettings.getLocalDeliveryPostalCodes());
        
        current.setExternalShippingEnabled(newSettings.getExternalShippingEnabled());
        current.setExternalShippingMode(newSettings.getExternalShippingMode());
        current.setExternalShippingFixedCost(newSettings.getExternalShippingFixedCost());
        
        if (current.getId() == null) {
            current.setId(1L);
        }
        
        return repository.save(current);
    }
}
