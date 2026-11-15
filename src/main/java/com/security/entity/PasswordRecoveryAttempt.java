package com.security.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Entidad para controlar intentos de recuperación de contraseña por IP/Email
 * Implementa rate limiting y retrasos progresivos
 */
@Entity
@Table(name = "password_recovery_attempts", schema = "security")
public class PasswordRecoveryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "ip_address", nullable = false, length = 45)
    private String ipAddress;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "last_attempt", nullable = false)
    private LocalDateTime lastAttempt;

    @Column(name = "blocked_until")
    private LocalDateTime blockedUntil;

    @Column(name = "is_blocked", nullable = false)
    private boolean blocked = false;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Constructor principal
     */
    public PasswordRecoveryAttempt(String email, String ipAddress, String userAgent) {
        this.email = email;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.attemptCount = 1;
        this.lastAttempt = LocalDateTime.now();
        this.createdAt = LocalDateTime.now();
        this.blocked = false;
    }

    /**
     * Incrementa el contador de intentos
     */
    public void incrementAttempt() {
        this.attemptCount++;
        this.lastAttempt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Aplica bloqueo progresivo ACUMULATIVO basado en el total histórico de
     * intentos.
     * El contador NUNCA se resetea — cada intento suma permanentemente.
     *
     * Solo se invoca cuando totalAttempts % 3 == 0 (múltiplos de 3).
     * Cada ciclo permite exactamente 3 intentos antes de bloquear:
     *
     * Ciclo 1 → intento 3 → 3 minutos
     * Ciclo 2 → intento 6 → 30 minutos
     * Ciclo 3 → intento 9 → 2 horas
     * Ciclo 4 → intento 12 → 24 horas
     * Ciclo 5 → intento 15+ → 1 año (bloqueo permanente)
     *
     * @param totalAttempts Número total acumulado de intentos (nunca se resetea)
     */
    public void applyProgressiveBlockCumulative(int totalAttempts) {
        // Solo bloquear en múltiplos de 3
        if (totalAttempts % 3 != 0) {
            return;
        }

        this.blocked = true;
        this.updatedAt = LocalDateTime.now();

        if (totalAttempts >= 15) {
            // Bloqueo permanente: 1 año
            this.blockedUntil = LocalDateTime.now().plusYears(1);
        } else if (totalAttempts >= 12) {
            this.blockedUntil = LocalDateTime.now().plusHours(24);
        } else if (totalAttempts >= 9) {
            this.blockedUntil = LocalDateTime.now().plusHours(2);
        } else if (totalAttempts >= 6) {
            this.blockedUntil = LocalDateTime.now().plusMinutes(30);
        } else {
            // totalAttempts == 3 (o cualquier múltiplo de 3 menor a 6 — solo puede ser 3)
            this.blockedUntil = LocalDateTime.now().plusMinutes(3);
        }
    }

    /**
     * Verifica si el bloqueo ha expirado
     */
    public boolean isBlockExpired() {
        if (blockedUntil == null)
            return true;
        return LocalDateTime.now().isAfter(blockedUntil);
    }

    /**
     * Verifica si está actualmente bloqueado (bloqueo activo y no expirado)
     */
    public boolean isCurrentlyBlocked() {
        return blocked && !isBlockExpired();
    }

    /**
     * Levanta el bloqueo si el tiempo ya expiró, SIN resetear attemptCount.
     * El contador histórico se mantiene para que los bloqueos futuros
     * sigan escalando progresivamente.
     */
    public void liftBlockIfExpired() {
        if (blocked && isBlockExpired()) {
            this.blocked = false;
            // NO se resetea attemptCount — es acumulativo permanente
            this.updatedAt = LocalDateTime.now();
        }
    }

    /**
     * Verifica si necesita ser limpiado (más de 24 horas sin actividad)
     */
    public boolean shouldBeCleanedUp() {
        return lastAttempt.isBefore(LocalDateTime.now().minusHours(24));
    }

    /**
     * Resetea completamente el bloqueo (uso exclusivo de administradores).
     * Deja el attemptCount en 0 para reiniciar el ciclo de progresión.
     */
    public void resetByAdmin() {
        this.attemptCount = 0;
        this.blocked = false;
        this.blockedUntil = null;
        this.lastAttempt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // Constructors
    public PasswordRecoveryAttempt() {
        this.createdAt = LocalDateTime.now();
        this.lastAttempt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public LocalDateTime getLastAttempt() {
        return lastAttempt;
    }

    public void setLastAttempt(LocalDateTime lastAttempt) {
        this.lastAttempt = lastAttempt;
    }

    public LocalDateTime getBlockedUntil() {
        return blockedUntil;
    }

    public void setBlockedUntil(LocalDateTime blockedUntil) {
        this.blockedUntil = blockedUntil;
    }

    public boolean isBlocked() {
        return blocked;
    }

    public void setBlocked(boolean blocked) {
        this.blocked = blocked;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}