package com.security.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "active_sessions", schema = "auth")
public class ActiveSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "jwt_token_id", nullable = false)
    private String jwtTokenId;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "last_activity", nullable = false)
    private LocalDateTime lastActivity;

    @Column(nullable = false)
    private Boolean revoked = false;

    // Constructors
    public ActiveSession() {
    }

    public ActiveSession(User user, String jwtTokenId, String ipAddress, String userAgent, LocalDateTime expiresAt) {
        this.user = user;
        this.jwtTokenId = jwtTokenId;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.expiresAt = expiresAt;
        this.lastActivity = LocalDateTime.now(); // Inicializar última actividad
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getJwtTokenId() {
        return jwtTokenId;
    }

    public void setJwtTokenId(String jwtTokenId) {
        this.jwtTokenId = jwtTokenId;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
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

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getLastActivity() {
        return lastActivity;
    }

    public void setLastActivity(LocalDateTime lastActivity) {
        this.lastActivity = lastActivity;
    }

    public Boolean getRevoked() {
        return revoked;
    }

    public void setRevoked(Boolean revoked) {
        this.revoked = revoked;
    }

    // Helper methods
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    public boolean isInactive(int inactivityTimeoutMinutes) {
        return LocalDateTime.now().isAfter(lastActivity.plusMinutes(inactivityTimeoutMinutes));
    }

    public void updateLastActivity() {
        this.lastActivity = LocalDateTime.now();
    }

    public boolean isValid() {
        return !revoked && !isExpired();
    }

    public boolean isValidWithInactivity(int inactivityTimeoutMinutes) {
        return !revoked && !isExpired() && !isInactive(inactivityTimeoutMinutes);
    }
}
