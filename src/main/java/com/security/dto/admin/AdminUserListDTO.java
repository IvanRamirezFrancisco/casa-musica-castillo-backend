package com.security.dto.admin;

import java.time.LocalDateTime;

/**
 * DTO simplificado para listados paginados de usuarios Staff
 * Contiene solo información esencial para mejorar performance
 */
public class AdminUserListDTO {

    private Long id;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private Boolean enabled;
    private Boolean accountNonLocked;
    private String roles; // Roles concatenados (ej: "ROLE_ADMIN, ROLE_MODERATOR")
    private LocalDateTime createdAt;
    private LocalDateTime lastLogin; // Fecha del último login exitoso

    // --- Security Flags ---
    private Boolean protectedOwner;
    private Boolean currentUser;
    private Integer highestRoleLevel;
    private Boolean technicalUser;
    private Boolean operationalUser;
    private Boolean storeManager;

    private Boolean canManage;
    private Boolean canEdit;
    private Boolean canDelete;
    private Boolean canDisable;
    private Boolean canChangeRoles;
    private Boolean canResetTwoFactor;
    private Boolean canChangePasswordAdmin;
    private Boolean canViewSensitiveFields;

    private String displayEmail;
    private String maskedEmail;
    // ==================== Constructors ====================

    public AdminUserListDTO() {
    }

    public AdminUserListDTO(Long id, String firstName, String lastName, String email,
            Boolean enabled, Boolean accountNonLocked, String roles,
            LocalDateTime createdAt) {
        this.id = id;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.enabled = enabled;
        this.accountNonLocked = accountNonLocked;
        this.roles = roles;
        this.createdAt = createdAt;
    }

    // ==================== Getters and Setters ====================

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

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Boolean getAccountNonLocked() {
        return accountNonLocked;
    }

    public void setAccountNonLocked(Boolean accountNonLocked) {
        this.accountNonLocked = accountNonLocked;
    }

    public String getRoles() {
        return roles;
    }

    public void setRoles(String roles) {
        this.roles = roles;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getLastLogin() {
        return lastLogin;
    }

    public void setLastLogin(LocalDateTime lastLogin) {
        this.lastLogin = lastLogin;
    }

    // ==================== Helper Methods ====================

    /**
     * Retorna el nombre completo del usuario
     */
    public String getFullName() {
        return firstName + " " + lastName;
    }

    // ==================== Security Flags Getters and Setters ====================

    public Boolean getProtectedOwner() { return protectedOwner; }
    public void setProtectedOwner(Boolean protectedOwner) { this.protectedOwner = protectedOwner; }

    public Boolean getCurrentUser() { return currentUser; }
    public void setCurrentUser(Boolean currentUser) { this.currentUser = currentUser; }

    public Integer getHighestRoleLevel() { return highestRoleLevel; }
    public void setHighestRoleLevel(Integer highestRoleLevel) { this.highestRoleLevel = highestRoleLevel; }

    public Boolean getTechnicalUser() { return technicalUser; }
    public void setTechnicalUser(Boolean technicalUser) { this.technicalUser = technicalUser; }

    public Boolean getOperationalUser() { return operationalUser; }
    public void setOperationalUser(Boolean operationalUser) { this.operationalUser = operationalUser; }

    public Boolean getStoreManager() { return storeManager; }
    public void setStoreManager(Boolean storeManager) { this.storeManager = storeManager; }

    public Boolean getCanManage() { return canManage; }
    public void setCanManage(Boolean canManage) { this.canManage = canManage; }

    public Boolean getCanEdit() { return canEdit; }
    public void setCanEdit(Boolean canEdit) { this.canEdit = canEdit; }

    public Boolean getCanDelete() { return canDelete; }
    public void setCanDelete(Boolean canDelete) { this.canDelete = canDelete; }

    public Boolean getCanDisable() { return canDisable; }
    public void setCanDisable(Boolean canDisable) { this.canDisable = canDisable; }

    public Boolean getCanChangeRoles() { return canChangeRoles; }
    public void setCanChangeRoles(Boolean canChangeRoles) { this.canChangeRoles = canChangeRoles; }

    public Boolean getCanResetTwoFactor() { return canResetTwoFactor; }
    public void setCanResetTwoFactor(Boolean canResetTwoFactor) { this.canResetTwoFactor = canResetTwoFactor; }

    public Boolean getCanChangePasswordAdmin() { return canChangePasswordAdmin; }
    public void setCanChangePasswordAdmin(Boolean canChangePasswordAdmin) { this.canChangePasswordAdmin = canChangePasswordAdmin; }

    public Boolean getCanViewSensitiveFields() { return canViewSensitiveFields; }
    public void setCanViewSensitiveFields(Boolean canViewSensitiveFields) { this.canViewSensitiveFields = canViewSensitiveFields; }

    public String getDisplayEmail() { return displayEmail; }
    public void setDisplayEmail(String displayEmail) { this.displayEmail = displayEmail; }

    public String getMaskedEmail() { return maskedEmail; }
    public void setMaskedEmail(String maskedEmail) { this.maskedEmail = maskedEmail; }
}
