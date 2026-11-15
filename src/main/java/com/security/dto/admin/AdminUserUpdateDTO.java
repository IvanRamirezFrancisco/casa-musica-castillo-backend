package com.security.dto.admin;

import com.security.validation.BcryptPassword;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * DTO para actualizar un usuario Staff existente
 * No incluye password (se actualiza por endpoint separado)
 */
public class AdminUserUpdateDTO {

    @Size(min = 2, max = 50, message = "First name must be between 2 and 50 characters")
    @Pattern(regexp = "^[a-zA-ZáéíóúÁÉÍÓÚñÑ0-9\\s]+$", message = "First name can only contain letters, numbers and spaces")
    private String firstName;

    @Size(min = 2, max = 50, message = "Last name must be between 2 and 50 characters")
    @Pattern(regexp = "^[a-zA-ZáéíóúÁÉÍÓÚñÑ0-9\\s]+$", message = "Last name can only contain letters, numbers and spaces")
    private String lastName;

    @Email(regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$", message = "Invalid email format")
    @Size(max = 100, message = "Email cannot exceed 100 characters")
    private String email;

    @Size(max = 30, message = "Username cannot exceed 30 characters")
    @Pattern(regexp = "^[a-zA-Z0-9_-]*$", message = "Username can only contain letters, numbers, underscores and hyphens")
    private String username;

    @Size(max = 20, message = "Phone cannot exceed 20 characters")
    @Pattern(regexp = "^[0-9+\\s()-]*$", message = "Phone can only contain numbers, +, spaces, parentheses and hyphens")
    private String phone;

    /**
     * IDs de los roles a asignar al usuario
     * Si es null, no se modifican los roles
     */
    private Set<Long> roleIds;

    /**
     * Nueva contraseña (opcional). Si es null o vacío, no se cambia.
     */
    @Size(min = 8, max = 255, message = "Password must be between 8 and 255 characters")
    @BcryptPassword
    private String password;

    /**
     * Estado habilitado/deshabilitado del usuario
     */
    private Boolean enabled;

    /**
     * Estado bloqueado/desbloqueado de la cuenta
     */
    private Boolean accountNonLocked;

    // ==================== Constructors ====================

    public AdminUserUpdateDTO() {
    }

    // ==================== Getters and Setters ====================

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

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Set<Long> getRoleIds() {
        return roleIds;
    }

    public void setRoleIds(Set<Long> roleIds) {
        this.roleIds = roleIds;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
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
}
