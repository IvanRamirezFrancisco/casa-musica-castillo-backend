package com.security.repository;

import com.security.entity.ShippingSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ShippingSettingsRepository extends JpaRepository<ShippingSettings, Long> {
}
