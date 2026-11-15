package com.security.dto.response;

import java.time.LocalDateTime;
import java.util.Set;

public class UserResponse {

    private Long id;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private boolean enabled;
    private boolean emailVerified;
    private boolean twoFactorEnabled;
    private Set<String> roles;
    private Set<String> permissions;
    private Boolean isCustomer;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Boolean googleAuthEnabled;
    private Boolean emailEnabled;
    private Boolean backupCodesEnabled;
    private Boolean protectedOwner;

    // nuevos para ocultar boton de google authS
    public Boolean getGoogleAuthEnabled() {
        return googleAuthEnabled;
    }

    public void setGoogleAuthEnabled(Boolean googleAuthEnabled) {
        this.googleAuthEnabled = googleAuthEnabled;
    }

    public Boolean getEmailEnabled() {
        return emailEnabled;
    }

    public void setEmailEnabled(Boolean emailEnabled) {
        this.emailEnabled = emailEnabled;
    }

    public Boolean getBackupCodesEnabled() {
        return backupCodesEnabled;
    }

    public void setBackupCodesEnabled(Boolean backupCodesEnabled) {
        this.backupCodesEnabled = backupCodesEnabled;
    }

    public Boolean getProtectedOwner() {
        return protectedOwner;
    }

    public void setProtectedOwner(Boolean protectedOwner) {
        this.protectedOwner = protectedOwner;
    }

    // Constructors
    public UserResponse() {
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public void setEmailVerified(boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    public boolean isTwoFactorEnabled() {
        return twoFactorEnabled;
    }

    public void setTwoFactorEnabled(boolean twoFactorEnabled) {
        this.twoFactorEnabled = twoFactorEnabled;
    }

    public Set<String> getRoles() {
        return roles;
    }

    public void setRoles(Set<String> roles) {
        this.roles = roles;
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

    public Set<String> getPermissions() {
        return permissions;
    }

    public void setPermissions(Set<String> permissions) {
        this.permissions = permissions;
    }

    public Boolean getIsCustomer() {
        return isCustomer;
    }

    public void setIsCustomer(Boolean isCustomer) {
        this.isCustomer = isCustomer;
    }
}
