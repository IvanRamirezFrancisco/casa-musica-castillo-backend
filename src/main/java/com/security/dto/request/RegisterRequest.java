package com.security.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.security.validation.SecurePassword;
import com.security.validation.SafeInput;
import com.security.validation.ValidName;
import com.security.validation.ValidEmailDomain;

public class RegisterRequest {

    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 30, message = "Username must be between 3 and 30 characters")
    @SafeInput(type = SafeInput.SanitizationType.TEXT, message = "Username contains invalid characters")
    private String username;

    @NotBlank(message = "First name is required")
    @Size(max = 50, message = "First name must be less than 50 characters")
    @ValidName(message = "First name contains invalid characters or malicious content")
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(max = 50, message = "Last name must be less than 50 characters")
    @ValidName(message = "Last name contains invalid characters or malicious content")
    private String lastName;

    @NotBlank(message = "Email is required")
    @Email(message = "Email should be valid", regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")
    @Size(max = 100, message = "Email must be less than 100 characters")
    @ValidEmailDomain(message = "Please use an email from a recognized provider (gmail.com, hotmail.com, outlook.com, yahoo.com)")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 255, message = "Password must be between 8 and 255 characters")
    @SecurePassword(message = "Password must be secure: avoid simple patterns like 123456, qwerty, or abc123")
    @com.security.validation.BcryptPassword
    private String password;

    @Size(max = 20, message = "Phone must be less than 20 characters")
    @SafeInput(type = SafeInput.SanitizationType.PHONE, message = "Phone format is invalid")
    private String phone;

    // Constructors
    public RegisterRequest() {
    }

    // Getters and Setters
    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
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

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }
}
