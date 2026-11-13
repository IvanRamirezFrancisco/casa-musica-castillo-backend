package com.security.controller;

import com.security.exception.AuthFlowException;
import com.security.security.CurrentUser;
import com.security.security.UserPrincipal;
import com.security.service.AccountDeletionService;
import com.security.service.AccountDeletionService.Receipt;
import com.security.service.RegistrationLegalPolicy;
import com.security.service.VerificationRequestLimit;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
public class AccountPrivacyController {
    public record DeletionRequest(@NotBlank @Size(max = 255) String currentPassword, boolean confirmed) {}
    public record TrackingRequest(@jakarta.validation.constraints.NotNull UUID requestId) {}

    private final AccountDeletionService deletions;
    private final RegistrationLegalPolicy legal;
    private final VerificationRequestLimit limits;
    private final boolean intakeEnabled;

    public AccountPrivacyController(AccountDeletionService deletions, RegistrationLegalPolicy legal,
            VerificationRequestLimit limits, @Value("${app.legal.deletion-intake-enabled:false}") boolean intakeEnabled) {
        this.deletions = deletions;
        this.legal = legal;
        this.limits = limits;
        this.intakeEnabled = intakeEnabled;
    }

    @GetMapping("/api/public/legal/registration")
    public RegistrationLegalPolicy.Documents documents() { return legal.documents(); }

    @GetMapping("/api/public/legal/deletion")
    public Map<String, Boolean> deletionAvailability() { return Map.of("available", intakeEnabled); }

    @PostMapping("/api/users/me/deletion-requests")
    public ResponseEntity<Receipt> requestDeletion(@CurrentUser UserPrincipal principal,
            @Valid @RequestBody DeletionRequest request, HttpServletRequest client) {
        requireIntake();
        if (!request.confirmed()) throw new AuthFlowException(HttpStatus.BAD_REQUEST, "Confirma la solicitud de eliminación.");
        limits.check("deletion-account", principal.getId().toString(), 5);
        limits.check("deletion-address", client.getRemoteAddr(), 30);
        return ResponseEntity.accepted().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(deletions.request(principal.getId(), request.currentPassword()));
    }

    @GetMapping("/api/users/me/deletion-requests")
    public ResponseEntity<Receipt> ownRequest(@CurrentUser UserPrincipal principal) {
        requireIntake();
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(deletions.ownRequest(principal.getId()));
    }

    @PostMapping("/api/public/legal/deletion/status")
    public ResponseEntity<Receipt> track(@Valid @RequestBody TrackingRequest request, HttpServletRequest client) {
        requireIntake();
        limits.check("deletion-tracking-address", client.getRemoteAddr(), 30);
        // The random receipt is a bearer capability for the outcome only, with no account details.
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(deletions.track(request.requestId()));
    }

    private void requireIntake() {
        if (!intakeEnabled) throw new AuthFlowException(HttpStatus.SERVICE_UNAVAILABLE, "La recepción de solicitudes aún no está disponible.");
    }
}
