package com.security.controller;

import com.security.dto.request.AuthenticatedGoogleChallengeRequest;
import com.security.dto.request.CancelLinkGoogleRequest;
import com.security.dto.request.CompleteGoogleRegistrationRequest;
import com.security.dto.request.ConfirmLinkGoogleRequest;
import com.security.dto.request.GoogleAuthenticateRequest;
import com.security.dto.request.GoogleChallengeRequest;
import com.security.dto.request.GoogleReauthRequest;
import com.security.dto.request.LinkGoogleRequest;
import com.security.dto.request.PrepareLinkGoogleRequest;
import com.security.dto.request.UnlinkGoogleRequest;
import com.security.dto.response.ApiResponse;
import com.security.security.CurrentUser;
import com.security.security.UserPrincipal;
import com.security.service.GoogleAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/google")
public class GoogleAuthController {
    private final GoogleAuthService googleAuthService;

    @Autowired
    public GoogleAuthController(GoogleAuthService googleAuthService) {
        this.googleAuthService = googleAuthService;
    }

    @GetMapping("/config")
    public ResponseEntity<ApiResponse> getConfig() {
        return ResponseEntity.ok(new ApiResponse(true, "Configuración pública de Google", googleAuthService.getPublicConfig()));
    }

    @PostMapping("/challenge")
    public ResponseEntity<ApiResponse> createChallenge(
            @Valid @RequestBody(required = false) GoogleChallengeRequest body,
            HttpServletRequest request) {
        String purpose = body != null && body.purpose() != null ? body.purpose() : "AUTH";
        return ResponseEntity.ok(new ApiResponse(true, "Desafío generado", googleAuthService.issueChallenge(purpose, request)));
    }

    @PostMapping("/authenticated-challenge")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse> createAuthenticatedChallenge(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody AuthenticatedGoogleChallengeRequest body,
            HttpServletRequest request) {
        String sessionJti = (String) request.getAttribute("authenticatedJti");
        return ResponseEntity.ok(new ApiResponse(true, "Desafío autenticado generado",
                googleAuthService.issueAuthenticatedChallenge(principal.getId(), sessionJti, body.purpose())));
    }

    @PostMapping("/authenticate")
    public ResponseEntity<ApiResponse> authenticate(
            @Valid @RequestBody GoogleAuthenticateRequest body,
            HttpServletRequest request) {
        GoogleAuthService.GoogleAuthResult result = googleAuthService.authenticate(body, request);
        return ResponseEntity.ok()
                .headers(result.headers())
                .body(new ApiResponse(true, "Solicitud procesada", result.body()));
    }

    @PostMapping("/complete-registration")
    public ResponseEntity<ApiResponse> completeRegistration(
            @Valid @RequestBody CompleteGoogleRegistrationRequest body,
            HttpServletRequest request) {
        GoogleAuthService.GoogleAuthResult result = googleAuthService.completeRegistration(body, request);
        return ResponseEntity.ok()
                .headers(result.headers())
                .body(new ApiResponse(true, "Cuenta creada exitosamente", result.body()));
    }

    @PostMapping("/reauthenticate")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse> reauthenticate(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody GoogleReauthRequest body,
            HttpServletRequest request) {
        String sessionJti = (String) request.getAttribute("authenticatedJti");
        return ResponseEntity.ok(new ApiResponse(true, "Identidad verificada",
                googleAuthService.reauthenticate(principal.getId(), sessionJti, body)));
    }

    @PostMapping("/link/prepare")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse> prepareLinkGoogle(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody PrepareLinkGoogleRequest body,
            HttpServletRequest request) {
        String sessionJti = (String) request.getAttribute("authenticatedJti");
        return ResponseEntity.ok(new ApiResponse(true, "Identidad Google verificada para confirmar vinculación",
                googleAuthService.prepareLinkGoogle(principal.getId(), sessionJti, body)));
    }

    @PostMapping("/link/confirm")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse> confirmLinkGoogle(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody ConfirmLinkGoogleRequest body,
            HttpServletRequest request) {
        String sessionJti = (String) request.getAttribute("authenticatedJti");
        return ResponseEntity.ok(new ApiResponse(true, "Cuenta de Google vinculada",
                googleAuthService.confirmLinkGoogle(principal.getId(), sessionJti, body)));
    }

    @PostMapping("/link/cancel")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse> cancelLinkGoogle(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody CancelLinkGoogleRequest body,
            HttpServletRequest request) {
        String sessionJti = (String) request.getAttribute("authenticatedJti");
        return ResponseEntity.ok(new ApiResponse(true, "Vinculación de Google cancelada",
                googleAuthService.cancelLinkGoogle(principal.getId(), sessionJti, body)));
    }

    @PostMapping("/link")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse> linkGoogle(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody LinkGoogleRequest body,
            HttpServletRequest request) {
        String sessionJti = (String) request.getAttribute("authenticatedJti");
        return ResponseEntity.ok(new ApiResponse(true, "Cuenta de Google vinculada",
                googleAuthService.linkGoogle(principal.getId(), sessionJti, body)));
    }

    @PostMapping("/unlink")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse> unlinkGoogle(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody UnlinkGoogleRequest body,
            HttpServletRequest request) {
        String sessionJti = (String) request.getAttribute("authenticatedJti");
        return ResponseEntity.ok(new ApiResponse(true, "Cuenta de Google desvinculada",
                googleAuthService.unlinkGoogle(principal.getId(), sessionJti, body)));
    }
}
