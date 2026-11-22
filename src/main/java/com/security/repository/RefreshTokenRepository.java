package com.security.repository;

import com.security.entity.RefreshToken;
import com.security.entity.User;
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
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByToken(String token);

    @Query("SELECT rt.user.id FROM RefreshToken rt WHERE rt.token = :token")
    Optional<Long> findUserIdByToken(@Param("token") String token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rt FROM RefreshToken rt WHERE rt.token = :token")
    Optional<RefreshToken> findByTokenForUpdate(@Param("token") String token);

    List<RefreshToken> findByUser(User user);

    Optional<RefreshToken> findByUserId(Long userId);

    List<RefreshToken> findByFamilyId(String familyId);

    boolean existsByToken(String token);

    boolean existsByUserId(Long userId);

    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.user = :user")
    void deleteByUser(@Param("user") User user);

    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.expiryDate < :currentTime")
    void deleteExpiredTokens(@Param("currentTime") LocalDateTime currentTime);

    @Modifying
    @Query("""
            UPDATE RefreshToken rt
            SET rt.revoked = true, rt.revokedAt = :now, rt.revokeReason = :reason
            WHERE rt.familyId = :familyId AND rt.revoked = false
            """)
    int revokeFamily(@Param("familyId") String familyId,
                     @Param("now") LocalDateTime now,
                     @Param("reason") String reason);

    @Modifying
    @Query("""
            UPDATE RefreshToken rt
            SET rt.revoked = true, rt.revokedAt = :now, rt.revokeReason = :reason
            WHERE rt.sessionJti = :sessionJti AND rt.revoked = false
            """)
    int revokeBySessionJti(@Param("sessionJti") String sessionJti,
                           @Param("now") LocalDateTime now,
                           @Param("reason") String reason);

    @Modifying
    @Query("""
            UPDATE RefreshToken rt
            SET rt.revoked = true, rt.revokedAt = :now, rt.revokeReason = :reason
            WHERE rt.user.id = :userId AND rt.revoked = false
            """)
    int revokeAllByUserId(@Param("userId") Long userId,
                          @Param("now") LocalDateTime now,
                          @Param("reason") String reason);

    List<RefreshToken> findByExpiryDateBefore(LocalDateTime currentTime);

    @Query("SELECT rt FROM RefreshToken rt WHERE rt.expiryDate > :currentTime")
    List<RefreshToken> findValidTokens(@Param("currentTime") LocalDateTime currentTime);

    long countByUserId(Long userId);

    @Query("SELECT rt FROM RefreshToken rt ORDER BY rt.createdAt DESC")
    List<RefreshToken> findRecentTokens();

    @Query("SELECT rt FROM RefreshToken rt WHERE rt.createdAt BETWEEN :startDate AND :endDate")
    List<RefreshToken> findByCreatedAtBetween(@Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);
}
