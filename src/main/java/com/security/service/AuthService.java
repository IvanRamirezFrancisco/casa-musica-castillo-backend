package com.security.service;

import com.security.dto.request.RegisterRequest;
import com.security.dto.response.UserResponse;
import com.security.entity.Role;
import com.security.entity.User;
import com.security.exception.BadRequestException;
import com.security.repository.UserRepository;
import com.security.security.JwtTokenProvider;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class AuthService {

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private UserService userService;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private TwoFactorService twoFactorService;

    @Autowired
    private com.security.service.AdminHierarchyService adminHierarchyService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VerificationService verificationService;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UserResponse registerUser(RegisterRequest registerRequest) {
        User user = userService.createUser(registerRequest);
        return convertToUserResponse(user);
    }

    public void verifyEmail(String token) {
        boolean verified = verificationService.verifyToken(token);
        if (!verified) {
            throw new BadRequestException("Invalid or expired verification token");
        }
    }

    public void resetPassword(String email) {
        // Delegado a PasswordResetService
    }

    public void confirmPasswordReset(String token, String newPassword) {
        // Delegado a PasswordResetService
    }

    public UserResponse convertToUserResponse(User user) {
        UserResponse userResponse = new UserResponse();
        userResponse.setId(user.getId());
        userResponse.setProtectedOwner(adminHierarchyService.isProtectedOwner(user));
        userResponse.setFirstName(user.getFirstName());
        userResponse.setLastName(user.getLastName());
        userResponse.setEmail(user.getEmail());
        userResponse.setPhone(user.getPhone());
        userResponse.setEnabled(user.getEnabled());
        userResponse.setTwoFactorEnabled(user.getTwoFactorEnabled());
        userResponse.setCreatedAt(user.getCreatedAt());
        userResponse.setUpdatedAt(user.getUpdatedAt());

        Set<String> roleNames = user.getRoles() != null
                ? user.getRoles().stream().map(Role::getName).collect(Collectors.toSet())
                : Collections.emptySet();
        userResponse.setRoles(roleNames);

        Set<String> permissionNames = user.getRoles() != null
                ? user.getRoles().stream()
                        .filter(role -> role.getPermissions() != null)
                        .flatMap(role -> role.getPermissions().stream())
                        .map(permission -> permission.getName())
                        .collect(Collectors.toSet())
                : Collections.emptySet();
        userResponse.setPermissions(permissionNames);

        userResponse.setIsCustomer(user.getIsCustomer());
        userResponse.setGoogleAuthEnabled(user.getGoogleAuthEnabled());
        userResponse.setEmailEnabled(user.getEmailEnabled());

        return userResponse;
    }
}
