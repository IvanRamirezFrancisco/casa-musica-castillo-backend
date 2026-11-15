package com.security.repository;

import com.security.entity.BankTransferSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BankTransferSettingsRepository extends JpaRepository<BankTransferSettings, Long> {

    Optional<BankTransferSettings> findByActiveTrue();

}
