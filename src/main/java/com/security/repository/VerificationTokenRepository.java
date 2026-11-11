package com.security.repository;

import com.security.entity.VerificationToken;
import com.security.entity.User;
import com.security.enums.TokenType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface VerificationTokenRepository extends JpaRepository<VerificationToken, Long> {

    // Búsqueda por token
    Optional<VerificationToken> findByToken(String token);

    // Búsqueda por usuario y tipo
    Optional<VerificationToken> findByUserAndTokenType(User user, TokenType tokenType);

    List<VerificationToken> findByUserId(Long userId);

    // Tokens válidos
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT vt FROM VerificationToken vt WHERE vt.token = :token AND vt.tokenType = :type AND vt.expiryDate > :currentTime AND vt.used = false")
    Optional<VerificationToken> findValidToken(@Param("token") String token, @Param("type") TokenType type,
            @Param("currentTime") LocalDateTime currentTime);

    // Verificar existencia
    boolean existsByToken(String token);

    boolean existsByUserAndTokenType(User user, TokenType tokenType);

    // Marcar como usado
    @Modifying
    @Query("UPDATE VerificationToken vt SET vt.used = true WHERE vt.id = :tokenId")
    void markTokenAsUsed(@Param("tokenId") Long tokenId);

    // Eliminar tokens expirados
    @Modifying
    @Query("DELETE FROM VerificationToken vt WHERE vt.expiryDate < :currentTime")
    void deleteExpiredTokens(@Param("currentTime") LocalDateTime currentTime);

    // Eliminar por usuario
    @Modifying
    @Query("DELETE FROM VerificationToken vt WHERE vt.user = :user")
    void deleteByUser(@Param("user") User user);

    // Buscar por tipo de token
    List<VerificationToken> findByTokenType(TokenType tokenType);

    // Contar tokens activos
    @Query("SELECT COUNT(vt) FROM VerificationToken vt WHERE vt.user.id = :userId AND vt.expiryDate > :currentTime AND vt.used = false")
    long countActiveTokensByUserId(@Param("userId") Long userId, @Param("currentTime") LocalDateTime currentTime);

    // Eliminar tokens de verificación por lista de IDs de usuario (cleanup job)
    @Modifying
    @Query("DELETE FROM VerificationToken vt WHERE vt.user.id IN :userIds")
    void deleteByUserIdIn(@Param("userIds") List<Long> userIds);
}
