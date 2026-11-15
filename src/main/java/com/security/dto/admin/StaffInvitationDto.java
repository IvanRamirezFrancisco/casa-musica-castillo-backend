package com.security.dto.admin;

import com.security.entity.StaffInvitation;
import com.security.enums.InvitationStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO de respuesta para invitaciones (lista en panel admin).
 */
public class StaffInvitationDto {

    private Long id;
    private String email;
    private String firstName;
    private String lastName;
    private InvitationStatus status;
    private List<String> roleNames;
    private String invitedByName;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private LocalDateTime acceptedAt;

    public StaffInvitationDto() {
    }

    /**
     * Factory method para convertir entidad a DTO.
     */
    public static StaffInvitationDto fromEntity(StaffInvitation entity, List<String> roleNames, String invitedByNameOverride) {
        StaffInvitationDto dto = new StaffInvitationDto();
        dto.setId(entity.getId());
        dto.setEmail(entity.getEmail());
        dto.setFirstName(entity.getFirstName());
        dto.setLastName(entity.getLastName());
        dto.setStatus(entity.getStatus());
        dto.setRoleNames(roleNames);
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setExpiresAt(entity.getExpiresAt());
        dto.setAcceptedAt(entity.getAcceptedAt());

        if (invitedByNameOverride != null) {
            dto.setInvitedByName(invitedByNameOverride);
        } else if (entity.getInvitedBy() != null) {
            dto.setInvitedByName(
                    entity.getInvitedBy().getFirstName() + " " + entity.getInvitedBy().getLastName());
        }
        return dto;
    }

    public static StaffInvitationDto fromEntity(StaffInvitation entity, List<String> roleNames) {
        return fromEntity(entity, roleNames, null);
    }

    // ── Getters y Setters ───────────────────────────────────────

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

    public InvitationStatus getStatus() {
        return status;
    }

    public void setStatus(InvitationStatus status) {
        this.status = status;
    }

    public List<String> getRoleNames() {
        return roleNames;
    }

    public void setRoleNames(List<String> roleNames) {
        this.roleNames = roleNames;
    }

    public String getInvitedByName() {
        return invitedByName;
    }

    public void setInvitedByName(String invitedByName) {
        this.invitedByName = invitedByName;
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

    public LocalDateTime getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(LocalDateTime acceptedAt) {
        this.acceptedAt = acceptedAt;
    }
}
