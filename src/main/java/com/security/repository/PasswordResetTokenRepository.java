package com.security.repository;

import com.security.entity.PasswordResetToken;
import com.security.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    // Buscar por token
    Optional<PasswordResetToken> findByToken(String token);

    // Buscar por token no usado
    Optional<PasswordResetToken> findByTokenAndUsedFalse(String token);

    // Buscar token válido por usuario
    @Query("SELECT p FROM PasswordResetToken p WHERE p.user = :user AND p.used = false AND p.expiryDate > :now")
    Optional<PasswordResetToken> findValidTokenByUser(@Param("user") User user, @Param("now") LocalDateTime now);

    // Eliminar todos los tokens de un usuario
    @Modifying
    @Transactional
    @Query("DELETE FROM PasswordResetToken p WHERE p.user = :user")
    void deleteAllByUser(@Param("user") User user);

    // Limpiar tokens expirados
    @Modifying
    @Transactional
    @Query("DELETE FROM PasswordResetToken p WHERE p.expiryDate < :now")
    void deleteExpiredTokens(@Param("now") LocalDateTime now);

    // Buscar por usuario
    @Query("SELECT p FROM PasswordResetToken p WHERE p.user = :user")
    java.util.List<PasswordResetToken> findByUser(@Param("user") User user);

    // Buscar tokens expirados para limpieza
    @Query("SELECT p FROM PasswordResetToken p WHERE p.expiryDate < :cutoff OR p.used = true")
    java.util.List<PasswordResetToken> findExpiredTokens(@Param("cutoff") LocalDateTime cutoff);

    // Buscar por usuario y estado
    @Query("SELECT p FROM PasswordResetToken p WHERE p.user = :user AND p.used = :used")
    java.util.List<PasswordResetToken> findByUserAndUsed(@Param("user") User user, @Param("used") boolean used);

    // Eliminar tokens de reset por lista de IDs de usuario (cleanup job)
    @Modifying
    @Transactional
    @Query("DELETE FROM PasswordResetToken p WHERE p.user.id IN :userIds")
    void deleteByUserIdIn(@Param("userIds") List<Long> userIds);
}