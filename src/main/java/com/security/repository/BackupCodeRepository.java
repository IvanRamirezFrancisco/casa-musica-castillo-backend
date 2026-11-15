package com.security.repository;

import com.security.entity.BackupCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BackupCodeRepository extends JpaRepository<BackupCode, Long> {

    /**
     * Encuentra todos los códigos de backup no utilizados para un usuario
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    List<BackupCode> findByUserIdAndUsedFalse(Long userId);

    /**
     * Encuentra un código específico por su hash que no haya sido utilizado
     */
    Optional<BackupCode> findByCodeHashAndUsedFalse(String codeHash);

    /**
     * Cuenta cuántos códigos de backup tiene un usuario (usados y no usados)
     */
    long countByUserId(Long userId);

    /**
     * Cuenta cuántos códigos de backup no utilizados tiene un usuario
     */
    long countByUserIdAndUsedFalse(Long userId);

    /**
     * Encuentra todos los códigos de backup de un usuario (usados y no usados)
     */
    List<BackupCode> findByUserId(Long userId);

    /**
     * Elimina todos los códigos de backup de un usuario
     */
    void deleteByUserId(Long userId);

    /**
     * Verifica si un usuario tiene códigos de backup activos (no utilizados)
     */
    @Query("SELECT CASE WHEN COUNT(bc) > 0 THEN true ELSE false END FROM BackupCode bc WHERE bc.user.id = :userId AND bc.used = false")
    boolean hasActiveBackupCodes(@Param("userId") Long userId);

    /**
     * Obtiene estadísticas de códigos de backup para un usuario
     */
    @Query("SELECT " +
            "COUNT(bc) as total, " +
            "SUM(CASE WHEN bc.used = false THEN 1 ELSE 0 END) as unused, " +
            "SUM(CASE WHEN bc.used = true THEN 1 ELSE 0 END) as used " +
            "FROM BackupCode bc WHERE bc.user.id = :userId")
    Object[] getBackupCodeStats(@Param("userId") Long userId);
}