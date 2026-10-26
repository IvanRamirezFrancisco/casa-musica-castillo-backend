package com.security.controller;

import com.security.config.SecurityConfig;
import com.security.entity.User;
import com.security.repository.UserRepository;
import com.security.security.*;
import com.security.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TwoFactorController.class)
@Import({SecurityConfig.class,JwtAuthenticationEntryPoint.class,AuthExceptionHandler.class,TwoFactorService.class,TotpService.class})
@ActiveProfiles("production")
@TestPropertySource(properties="cors.allowed-origins=http://localhost:4200")
class TotpExposureProductionTest {
    @Autowired MockMvc mvc;
    @Autowired RequestMappingHandlerMapping mappings;
    @MockBean UserRepository users;
    @MockBean UserService userService;
    @MockBean EmailService email;
    @MockBean BackupCodeService backups;
    @MockBean LoginChallengeService challenges;
    @MockBean LoginSecurityService limits;
    @MockBean CustomUserDetailsService details;
    @MockBean JwtTokenProvider jwt;
    @MockBean SessionManagementService sessions;
    @MockBean com.security.util.SecurityUtils securityUtils;
    User user;
    @BeforeEach void setup() {
        user=new User();user.setId(1L);user.setEmail("synthetic@example.invalid");user.setEnabled(true);
        user.setGoogleAuthSecret(new com.warrenstrange.googleauth.GoogleAuthenticator().createCredentials().getKey());
        user.setGoogleAuthEnabled(true);
        when(users.findByIdForAuthUpdate(1L)).thenReturn(Optional.of(user));
        when(jwt.validateToken("synthetic-token")).thenReturn(true);
        when(jwt.getJtiFromJWT("synthetic-token")).thenReturn("synthetic-session");
        when(jwt.getUserIdFromJWT("synthetic-token")).thenReturn(1L);
        when(sessions.isSessionValid("synthetic-session")).thenReturn(true);
        when(sessions.belongsToUser("synthetic-session",1L)).thenReturn(true);
        when(details.loadUserById(1L)).thenReturn(UserPrincipal.create(user));
    }
    @Test void productionHasNoDebugMappingsEvenForAuthenticatedUser() throws Exception {
        assertTrue(mappings.getHandlerMethods().keySet().stream()
                .flatMap(mapping->mapping.getPatternValues().stream()).noneMatch(path->path.startsWith("/api/2fa/debug/")));
        for(String path:new String[]{"validate-totp","generate-test-code"}) {
            var result=mvc.perform(get("/api/2fa/debug/"+path).header("Authorization","Bearer synthetic-token"))
                    .andExpect(status().isForbidden()).andReturn();
            String body=result.getResponse().getContentAsString();
            assertFalse(body.contains(user.getGoogleAuthSecret()));
            assertFalse(body.contains("currentValidCode"));assertFalse(body.contains("currentCode"));
            assertFalse(body.contains("otpAuthUrl"));
        }
    }
    @Test void confirmedEnrollmentCannotRetrieveSecretQrOrManualKey() throws Exception {
        for(String path:new String[]{"qrcode","manual-code"}) {
            var result=mvc.perform(get("/api/2fa/google/"+path).header("Authorization","Bearer synthetic-token"))
                    .andExpect(status().isForbidden()).andReturn();
            String body=result.getResponse().getContentAsString();
            for(String forbidden:new String[]{user.getGoogleAuthSecret(),"googleAuthSecret","manualEntryKey","otpAuthUrl","secret=","currentValidCode","currentCode"})
                assertFalse(body.contains(forbidden));
        }
        // Setup cannot overwrite an already confirmed secret to reopen exposure.
        mvc.perform(post("/api/2fa/google/setup").header("Authorization","Bearer synthetic-token"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data").isEmpty());
        verify(users,never()).save(any());
    }
    @Test void pendingEnrollmentRetainsOnlyRequiredSecretAndQrContract() throws Exception {
        user.setGoogleAuthEnabled(false);
        mvc.perform(get("/api/2fa/google/manual-code").header("Authorization","Bearer synthetic-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.manualEntryKey").exists())
                .andExpect(jsonPath("$.data.currentCode").doesNotExist()).andExpect(jsonPath("$.data.otpAuthUrl").doesNotExist());
        mvc.perform(get("/api/2fa/google/qrcode").header("Authorization","Bearer synthetic-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.qrCode").value(org.hamcrest.Matchers.startsWith("data:image/png;base64,")))
                .andExpect(jsonPath("$.data.manualEntryKey").exists()).andExpect(jsonPath("$.data.currentValidCode").doesNotExist());
    }
}
