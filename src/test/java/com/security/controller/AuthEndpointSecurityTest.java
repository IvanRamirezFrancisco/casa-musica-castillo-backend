package com.security.controller;

import com.security.security.*;
import com.security.config.SecurityConfig;
import com.security.entity.User;
import com.security.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers={AuthController.class,TwoFactorController.class,PasswordResetController.class,SecurePasswordResetController.class})
@Import({SecurityConfig.class,AuthExceptionHandler.class,JwtAuthenticationEntryPoint.class})
@TestPropertySource(properties="cors.allowed-origins=http://localhost:4200")
class AuthEndpointSecurityTest {
    @Autowired MockMvc mvc;
    @MockBean AuthService auth;
    @MockBean VerificationService verification;
    @MockBean UserService users;
    @MockBean JwtTokenProvider jwt;
    @MockBean PasswordResetService passwords;
    @MockBean SecurePasswordResetService securePasswords;
    @MockBean LoginSecurityService limits;
    @MockBean SessionManagementService sessions;
    @MockBean LoginChallengeService challenges;
    @MockBean CustomUserDetailsService details;
    @MockBean TwoFactorService factors;
    @MockBean BackupCodeService backups;
    @MockBean com.security.util.SecurityUtils securityUtils;
    @MockBean com.security.util.PasswordValidator passwordValidator;

    @BeforeEach void setup() {
        User user=new User();user.setId(1L);user.setEmail("synthetic@example.invalid");user.setEnabled(true);
        when(jwt.validateToken("synthetic-valid-token")).thenReturn(true);
        when(jwt.getJtiFromJWT("synthetic-valid-token")).thenReturn("synthetic-session");
        when(jwt.getUserIdFromJWT("synthetic-valid-token")).thenReturn(1L);
        when(sessions.isSessionValid("synthetic-session")).thenReturn(true);
        when(sessions.belongsToUser("synthetic-session",1L)).thenReturn(true);
        when(details.loadUserById(1L)).thenReturn(UserPrincipal.create(user));
        when(users.getUserById(1L)).thenReturn(user);
        var response=new com.security.dto.response.UserResponse();response.setEmail(user.getEmail());
        when(users.getUserResponseById(1L)).thenReturn(response);
    }
    @Test void meRequiresValidJwtAndActiveSession() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer synthetic-valid-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("synthetic@example.invalid"))
                .andExpect(jsonPath("$.password").doesNotExist());
        when(sessions.isSessionValid("synthetic-session")).thenReturn(false);
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer synthetic-valid-token")).andExpect(status().isUnauthorized());
    }
    @Test void protectedAuthRoutesRequireAuthentication() throws Exception {
        mvc.perform(post("/api/auth/logout")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/logout-all")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/sessions")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/auth/sessions/foreign")).andExpect(status().isUnauthorized());
    }
    @Test void authenticatedSessionOwnershipIsEnforced() throws Exception {
        mvc.perform(delete("/api/auth/sessions/foreign").header("Authorization","Bearer synthetic-valid-token"))
                .andExpect(status().isNotFound());
        verify(sessions).revokeOwnedSession("foreign","synthetic@example.invalid");
        verify(sessions,never()).invalidateSession("foreign");
    }
    @Test void anonymousPublicEndpointsReachValidationWithoutExposingInput() throws Exception {
        String sensitive="SyntheticRejectedPassword!";
        mvc.perform(post("/api/auth/login").contentType("application/json")
                .content("{\"email\":\"bad\",\"password\":\""+sensitive+"\"}"))
                .andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(sensitive))));
        mvc.perform(post("/api/auth/register").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/2fa/verify").contentType("application/json")
                .content("{\"email\":\"synthetic@example.invalid\",\"code\":\"123456\",\"method\":\"EMAIL\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/2fa/send-login-code").contentType("application/json")
                .content("{\"email\":\"synthetic@example.invalid\",\"method\":\"EMAIL\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(challenges);
    }
    @Test void logoutUsesAuthenticatedJtiAndAllUsesAuthenticatedUser() throws Exception {
        mvc.perform(post("/api/auth/logout").header("Authorization","Bearer synthetic-valid-token")).andExpect(status().isOk());
        verify(sessions).invalidateSession("synthetic-session");
        mvc.perform(post("/api/auth/logout-all").header("Authorization","Bearer synthetic-valid-token")).andExpect(status().isOk());
        verify(sessions).invalidateAllUserSessions("synthetic@example.invalid");
    }
    @Test void retiredEndpointsHaveNoHandler() throws Exception {
        for(String path:new String[]{"refresh","refresh-token","secure-login","secure-logout","session-status","csrf-token"})
            mvc.perform(post("/api/auth/"+path).header("Authorization","Bearer synthetic-valid-token")).andExpect(status().isNotFound());
    }
    @Test void recoveryContractsRemainPublicAndReachTheirControllers() throws Exception {
        mvc.perform(post("/api/auth/forgot-password").param("email","synthetic@example.invalid")).andExpect(status().isOk());
        mvc.perform(post("/api/auth/forgot-password")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/auth/validate-reset-token").param("token","synthetic-reset-token")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/reset-password").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/password-reset/request").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/password-reset/confirm").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/auth/password-reset/verify/synthetic-reset-token")).andExpect(status().isBadRequest());
    }
    @Test void successfulFactorClearsAttemptsForChallengeUserOnly() throws Exception {
        var user=new com.security.dto.response.UserResponse();user.setEmail("synthetic@example.invalid");
        when(challenges.verify(any(),any())).thenReturn(new com.security.dto.response.JwtAuthResponse("synthetic-issued-token",900L,user));
        mvc.perform(post("/api/2fa/verify").contentType("application/json")
                .content("{\"preAuthChallenge\":\""+"A".repeat(43)+"\",\"method\":\"EMAIL\",\"code\":\"123456\",\"email\":\"other@example.invalid\"}"))
                .andExpect(status().isOk());
        verify(limits).clearAfterSuccessfulLogin("synthetic@example.invalid");
        verify(limits,never()).clearAfterSuccessfulLogin("other@example.invalid");
    }
}
