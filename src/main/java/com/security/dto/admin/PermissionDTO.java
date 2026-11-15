package com.security.dto.admin;

import java.time.LocalDateTime;

/**
 * DTO para permisos del sistema
 * Se usa tanto para respuestas individuales como para listar todos los permisos
 * disponibles
 */
public class PermissionDTO {

    private Long id;
    private String name;
    private String description;
    private String category;
    private LocalDateTime createdAt;
    
    // UI Metadata
    private Boolean assignable;
    private Boolean critical;
    private Boolean ownerOnly;

    // ==================== Constructors ====================

    public PermissionDTO() {
    }

    public PermissionDTO(Long id, String name, String description) {
        this.id = id;
        this.name = name;
        this.description = description;
    }

    public PermissionDTO(Long id, String name, String description, String category) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.category = category;
    }

    // ==================== Getters and Setters ====================

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Boolean getAssignable() {
        return assignable;
    }

    public void setAssignable(Boolean assignable) {
        this.assignable = assignable;
    }

    public Boolean getCritical() {
        return critical;
    }

    public void setCritical(Boolean critical) {
        this.critical = critical;
    }

    public Boolean getOwnerOnly() {
        return ownerOnly;
    }

    public void setOwnerOnly(Boolean ownerOnly) {
        this.ownerOnly = ownerOnly;
    }
}
