package com.security.controller;

import com.security.exception.AuthFlowException;
import com.security.security.CurrentUser;
import com.security.security.UserPrincipal;
import com.security.service.AccountDeletionService;
import com.security.service.AccountDeletionService.AdminRequestSummary;
import com.security.service.AccountDeletionService.PublicInitiationResponse;
import com.security.service.AccountDeletionService.Receipt;
import com.security.service.RegistrationLegalPolicy;
import com.security.service.VerificationRequestLimit;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
public class AccountPrivacyController {
    public record DeletionRequest(@NotBlank @Size(max = 255) String currentPassword, boolean confirmed) {}
    public record PublicDeletionEmailRequest(@NotBlank @Email @Size(max = 180) String email) {}
    public record PublicDeletionConfirmRequest(@NotBlank @Size(min = 20, max = 255) String token, boolean confirmed) {}
    public record AdminApproveRequest(@Size(max = 500) String resolutionNotes) {}
    public record TrackingRequest(@jakarta.validation.constraints.NotNull UUID requestId) {}

    private final AccountDeletionService deletions;
    private final RegistrationLegalPolicy legal;
    private final VerificationRequestLimit limits;
    private final boolean intakeEnabled;
    private final boolean automaticDeletionEnabled;

    public AccountPrivacyController(
            AccountDeletionService deletions,
            RegistrationLegalPolicy legal,
            VerificationRequestLimit limits,
            @Value("${app.legal.deletion-intake-enabled:false}") boolean intakeEnabled,
            @Value("${app.legal.automatic-deletion-enabled:false}") boolean automaticDeletionEnabled) {
        this.deletions = deletions;
        this.legal = legal;
        this.limits = limits;
        this.intakeEnabled = intakeEnabled;
        this.automaticDeletionEnabled = automaticDeletionEnabled;
    }

    @GetMapping("/api/public/legal/registration")
    public RegistrationLegalPolicy.Documents documents() { return legal.documents(); }

    @GetMapping("/api/public/legal/deletion")
    public Map<String, Object> deletionAvailability() {
        return Map.of(
                "available", intakeEnabled,
                "publicEmailRequestEnabled", intakeEnabled,
                "automaticDeletionEnabled", automaticDeletionEnabled,
                "supportEmail", "soporte@casamusicacastillo.com",
                "tokenValidityMinutes", 30,
                "retentionPolicyVersion", "2026-04");
    }

    @PostMapping("/api/public/legal/deletion/request")
    public ResponseEntity<PublicInitiationResponse> requestPublicDeletion(
            @Valid @RequestBody PublicDeletionEmailRequest request,
            HttpServletRequest client) {
        requireIntake();
        String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);
        limits.check("deletion-public-ip", client.getRemoteAddr(), 10);
        limits.check("deletion-public-email", normalizedEmail, 3);
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(deletions.requestPublicByEmail(normalizedEmail, client.getRemoteAddr()));
    }

    @PostMapping("/api/public/legal/deletion/confirm")
    public ResponseEntity<Receipt> confirmPublicDeletion(
            @Valid @RequestBody PublicDeletionConfirmRequest request,
            HttpServletRequest client) {
        requireIntake();
        if (!request.confirmed()) {
            throw new AuthFlowException(HttpStatus.BAD_REQUEST, "Confirma de forma explícita la eliminación de tu cuenta.");
        }
        limits.check("deletion-public-confirm", client.getRemoteAddr(), 15);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(deletions.confirmPublicToken(request.token()));
    }

    @PostMapping("/api/users/me/deletion-requests")
    public ResponseEntity<Receipt> requestDeletion(@CurrentUser UserPrincipal principal,
            @Valid @RequestBody DeletionRequest request, HttpServletRequest client) {
        requireIntake();
        if (!request.confirmed()) throw new AuthFlowException(HttpStatus.BAD_REQUEST, "Confirma la solicitud de eliminación.");
        limits.check("deletion-account", principal.getId().toString(), 5);
        limits.check("deletion-address", client.getRemoteAddr(), 30);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(deletions.request(principal.getId(), request.currentPassword()));
    }

    @GetMapping("/api/users/me/deletion-requests")
    public ResponseEntity<Receipt> ownRequest(@CurrentUser UserPrincipal principal) {
        requireIntake();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(deletions.ownRequest(principal.getId()));
    }

    @PostMapping("/api/public/legal/deletion/status")
    public ResponseEntity<Receipt> track(@Valid @RequestBody TrackingRequest request, HttpServletRequest client) {
        requireIntake();
        limits.check("deletion-tracking-address", client.getRemoteAddr(), 30);
        // The random receipt is a bearer capability for the outcome only, with no account details.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(deletions.track(request.requestId()));
    }

    @GetMapping("/api/admin/account-deletion-requests")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<AdminRequestSummary>> listAdminRequests(
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(deletions.listRequestsForAdmin(status));
    }

    @PostMapping("/api/admin/account-deletion-requests/{requestId}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Receipt> approveAdminRequest(
            @PathVariable UUID requestId,
            @CurrentUser UserPrincipal adminPrincipal,
            @Valid @RequestBody(required = false) AdminApproveRequest body) {
        String notes = body != null ? body.resolutionNotes() : null;
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(deletions.resolveRequestAsAdmin(requestId, adminPrincipal.getId(), notes));
    }

    private void requireIntake() {
        if (!intakeEnabled) throw new AuthFlowException(HttpStatus.SERVICE_UNAVAILABLE, "La recepción de solicitudes aún no está disponible.");
    }
}
