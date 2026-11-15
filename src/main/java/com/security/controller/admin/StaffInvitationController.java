package com.security.controller.admin;

import com.security.dto.admin.CreateStaffInvitationRequest;
import com.security.dto.admin.StaffInvitationDto;
import com.security.service.admin.StaffInvitationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Controller para gestión de invitaciones de empleados (requiere JWT +
 * permisos).
 */
@RestController
@RequestMapping("/api/admin/staff/invitations")
public class StaffInvitationController {

    @Autowired
    private StaffInvitationService invitationService;

    /**
     * GET /api/admin/staff/invitations
     * Listar todas las invitaciones (pendientes, expiradas, aceptadas, canceladas).
     */
    @GetMapping
    @PreAuthorize("hasAuthority('USER_READ')")
    public ResponseEntity<List<StaffInvitationDto>> listAll(Authentication auth) {
        List<StaffInvitationDto> invitations = invitationService.listAllInvitations(auth);
        return ResponseEntity.ok(invitations);
    }

    /**
     * GET /api/admin/staff/invitations/pending
     * Listar solo las invitaciones pendientes.
     */
    @GetMapping("/pending")
    @PreAuthorize("hasAuthority('USER_READ')")
    public ResponseEntity<List<StaffInvitationDto>> listPending(Authentication auth) {
        List<StaffInvitationDto> invitations = invitationService.listPendingInvitations(auth);
        return ResponseEntity.ok(invitations);
    }

    /**
     * POST /api/admin/staff/invitations
     * Crear nueva invitación y enviar email.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('USER_CREATE')")
    public ResponseEntity<StaffInvitationDto> create(
            @RequestBody @Valid CreateStaffInvitationRequest req,
            Authentication auth) {
        StaffInvitationDto dto = invitationService.createInvitation(req, auth);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    /**
     * DELETE /api/admin/staff/invitations/{id}
     * Cancelar una invitación pendiente.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('USER_DELETE') or hasAuthority('USER_CREATE')")
    public ResponseEntity<Map<String, String>> cancel(
            @PathVariable Long id, Authentication auth) {
        invitationService.cancelInvitation(id, auth);
        return ResponseEntity.ok(Map.of("message", "Invitación cancelada exitosamente"));
    }

    /**
     * POST /api/admin/staff/invitations/{id}/resend
     * Reenviar invitación con nuevo token.
     */
    @PostMapping("/{id}/resend")
    @PreAuthorize("hasAuthority('USER_CREATE')")
    public ResponseEntity<Map<String, String>> resend(
            @PathVariable Long id, Authentication auth) {
        invitationService.resendInvitation(id, auth);
        return ResponseEntity.ok(Map.of("message", "Invitación reenviada exitosamente"));
    }
}
