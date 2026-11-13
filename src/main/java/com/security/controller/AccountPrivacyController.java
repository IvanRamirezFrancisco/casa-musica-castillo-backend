package com.security.controller;

import com.security.exception.AuthFlowException;
import com.security.security.CurrentUser;
import com.security.security.UserPrincipal;
import com.security.service.RegistrationLegalPolicy;
import com.security.service.UserService;
import com.security.service.VerificationRequestLimit;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@RestController
public class AccountPrivacyController {
    public record DeletionRequest(@NotBlank @Size(max = 255) String currentPassword, boolean confirmed) {}
    public record Receipt(UUID requestId, String status, OffsetDateTime receivedAt) {}

    private final JdbcTemplate jdbc;
    private final UserService users;
    private final RegistrationLegalPolicy legal;
    private final VerificationRequestLimit limits;
    private final boolean intakeEnabled;

    public AccountPrivacyController(JdbcTemplate jdbc, UserService users, RegistrationLegalPolicy legal,
            VerificationRequestLimit limits, @Value("${app.legal.deletion-intake-enabled:false}") boolean intakeEnabled) {
        this.jdbc = jdbc;
        this.users = users;
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
        users.requireStepUp(principal.getId(), Map.of("currentPassword", request.currentPassword()));
        // Identity comes from the authenticated session, which already required configured MFA at sign-in.
        jdbc.update("""
                INSERT INTO auth.account_deletion_requests (request_id, user_id) VALUES (?, ?)
                ON CONFLICT (user_id) DO NOTHING
                """, UUID.randomUUID(), principal.getId());
        return ResponseEntity.accepted().body(receiptFor(principal.getId()));
    }

    @GetMapping("/api/users/me/deletion-requests")
    public Receipt ownRequest(@CurrentUser UserPrincipal principal) {
        requireIntake();
        return receiptFor(principal.getId());
    }

    private Receipt receiptFor(long userId) {
        return jdbc.query("""
                SELECT request_id, received_at FROM auth.account_deletion_requests WHERE user_id = ?
                """, (row, index) -> new Receipt(row.getObject("request_id", UUID.class), "RECEIVED",
                row.getObject("received_at", OffsetDateTime.class)), userId)
                .stream().findFirst().orElseThrow(() -> new AuthFlowException(HttpStatus.NOT_FOUND, "No hay una solicitud registrada."));
    }

    private void requireIntake() {
        if (!intakeEnabled) throw new AuthFlowException(HttpStatus.SERVICE_UNAVAILABLE, "La recepción de solicitudes aún no está disponible.");
    }
}
