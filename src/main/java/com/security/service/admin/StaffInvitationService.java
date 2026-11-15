package com.security.service.admin;

import com.security.dto.admin.*;
import com.security.entity.Role;
import com.security.entity.StaffInvitation;
import com.security.entity.User;
import com.security.enums.InvitationStatus;
import com.security.exception.ResourceNotFoundException;
import com.security.repository.RoleRepository;
import com.security.repository.StaffInvitationRepository;
import com.security.repository.UserRepository;
import com.security.service.AuditLogService;
import com.security.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Servicio para gestión de invitaciones de empleados.
 * Implementa el flujo seguro: Admin invita → Empleado activa cuenta.
 */
@Service
public class StaffInvitationService {

    private static final Logger logger = LoggerFactory.getLogger(StaffInvitationService.class);
    private static final int TOKEN_BYTES = 32; // 256 bits de entropía
    private static final int INVITATION_HOURS = 48;

    @Autowired
    private StaffInvitationRepository invitationRepo;

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private RoleRepository roleRepo;

    @Autowired
    private EmailService emailService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private com.security.service.RolePolicyService rolePolicyService;

    // ══════════════════════════════════════════════════════════════
    // CREAR INVITACIÓN
    // ══════════════════════════════════════════════════════════════

    @Transactional
    public StaffInvitationDto createInvitation(
            CreateStaffInvitationRequest req, Authentication auth) {

        String email = req.email().toLowerCase().trim();

        // 1. Verificar que el email no existe ya en el sistema
        if (userRepo.existsByEmail(email)) {
            throw new IllegalArgumentException(
                    "Ya existe un usuario con el correo: " + email +
                            ". Si es un empleado inactivo, reactívalo desde la lista.");
        }

        // 2. Verificar que no hay una invitación pendiente para ese email
        if (invitationRepo.existsByEmailAndStatus(email, InvitationStatus.PENDING)) {
            throw new IllegalArgumentException(
                    "Ya existe una invitación pendiente para: " + email +
                            ". Cancélala antes de enviar una nueva.");
        }

        // 3. Validar roles — verificar que existen en BD
        List<Long> roleIdList = req.roleIds();
        List<Role> roles = roleRepo.findAllById(roleIdList);
        if (roles.size() != roleIdList.size()) {
            throw new IllegalArgumentException("Uno o más roles seleccionados no existen.");
        }

        // 4. Anti-escalada: solo SUPER_ADMIN puede invitar con ROLE_SUPER_ADMIN
        boolean assigningSuperAdmin = roles.stream()
                .anyMatch(r -> "ROLE_SUPER_ADMIN".equals(r.getName()));
        boolean currentUserIsSuperAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
        if (assigningSuperAdmin && !currentUserIsSuperAdmin) {
            throw new AccessDeniedException(
                    "Solo un Super Administrador puede invitar usuarios con el rol SUPER_ADMIN.");
        }

        // Obtener usuario invitador
        User invitedBy = userRepo.findByEmail(auth.getName())
                .orElseThrow(() -> new ResourceNotFoundException("Usuario invitador no encontrado"));

        // Validar que el STORE_MANAGER no asigne roles técnicos
        if (rolePolicyService.isStoreManager(invitedBy) && !rolePolicyService.isTechnicalUser(invitedBy)) {
            boolean assignsTechnical = roles.stream()
                    .anyMatch(r -> rolePolicyService.isTechnicalRole(r.getName()) || "ROLE_STORE_MANAGER".equals(r.getName()) || "ROLE_PROJECT_ADMIN".equals(r.getName()));
            if (assignsTechnical) {
                throw new com.security.exception.SecurityHierarchyException("No tienes permiso para invitar a usuarios con roles técnicos o gerenciales.");
            }
        }

        // 5. Generar token seguro (256 bits) y hash para almacenamiento
        String token = generateSecureToken();
        String tokenHash = hashToken(token);

        // 6. Generar token seguro (256 bits) y hash para almacenamiento

        // 7. Crear y guardar invitación (solo se persiste el HASH, nunca el token
        // plano)
        StaffInvitation invitation = new StaffInvitation();
        invitation.setEmail(email);
        invitation.setTokenHash(tokenHash);
        invitation.setInvitedBy(invitedBy);
        invitation.setRoleIds(roleIdList.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",")));
        invitation.setFirstName(req.firstName().trim());
        invitation.setLastName(req.lastName().trim());
        invitationRepo.save(invitation);

        // 8. Enviar email de invitación
        try {
            emailService.sendStaffInvitation(
                    email,
                    req.firstName().trim(),
                    invitedBy.getFirstName() + " " + invitedBy.getLastName(),
                    token,
                    INVITATION_HOURS);
        } catch (Exception e) {
            logger.error("Error al enviar email de invitación a {}: {}", maskEmail(email), e.getMessage());
            // No revertimos la invitación; el admin puede reenviar
        }

        // 9. Auditoría
        try {
            auditLogService.logAction(
                    "STAFF_INVITATION_SENT",
                    "STAFF_INVITATION",
                    invitation.getId(),
                    "Invitación enviada a: " + maskEmail(email) + " por " + auth.getName());
        } catch (Exception e) {
            logger.warn("No se pudo registrar audit log para invitación: {}", e.getMessage());
        }

        logger.info("✅ Invitación enviada a {} por {}", maskEmail(email), auth.getName());

        // Resolver nombres de roles para el DTO
        List<String> roleNames = roles.stream().map(Role::getName).collect(Collectors.toList());
        return StaffInvitationDto.fromEntity(invitation, roleNames);
    }

    // ══════════════════════════════════════════════════════════════
    // VALIDAR TOKEN (endpoint público)
    // ══════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public InvitationInfoDto validateToken(String token) {
        String tokenHash = hashToken(token);
        StaffInvitation invitation = invitationRepo.findByTokenHash(tokenHash)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Invitación no encontrada o ya utilizada."));

        if (!invitation.isValid()) {
            if (invitation.isExpired()) {
                throw new IllegalStateException(
                        "La invitación ha expirado. Pide al administrador que te envíe una nueva.");
            }
            throw new IllegalStateException(
                    "Esta invitación ya fue utilizada o cancelada.");
        }

        // Resolver nombres de roles
        List<String> roleNames = resolveRoleNames(invitation.getRoleIds());

        return new InvitationInfoDto(
                invitation.getFirstName(),
                invitation.getLastName(),
                invitation.getEmail(),
                roleNames);
    }

    // ══════════════════════════════════════════════════════════════
    // ACEPTAR INVITACIÓN (endpoint público, sin auth)
    // ══════════════════════════════════════════════════════════════

    @Transactional
    public void acceptInvitation(String token, AcceptInvitationRequest req) {
        // Validar que las contraseñas coincidan
        if (!req.password().equals(req.confirmPassword())) {
            throw new IllegalArgumentException("Las contraseñas no coinciden.");
        }

        String tokenHash = hashToken(token);
        StaffInvitation invitation = invitationRepo.findByTokenHash(tokenHash)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Invitación no encontrada o ya utilizada."));

        if (!invitation.isValid()) {
            if (invitation.isExpired()) {
                invitation.setStatus(InvitationStatus.EXPIRED);
                invitationRepo.save(invitation);
                throw new IllegalStateException(
                        "La invitación ha expirado. Pide al administrador que te envíe una nueva.");
            }
            throw new IllegalStateException(
                    "Esta invitación ya fue utilizada o cancelada.");
        }

        // Validar que el email no fue tomado mientras tanto
        if (userRepo.existsByEmail(invitation.getEmail())) {
            throw new IllegalArgumentException(
                    "El correo ya está registrado en el sistema.");
        }

        // Generar username único
        String username = generateUniqueUsername(invitation.getFirstName(), invitation.getLastName());

        // Crear el usuario
        User newUser = new User();
        newUser.setEmail(invitation.getEmail());
        newUser.setFirstName(invitation.getFirstName());
        newUser.setLastName(invitation.getLastName());
        newUser.setUsername(username);
        newUser.setPassword(passwordEncoder.encode(req.password()));
        newUser.setEnabled(true);
        newUser.setAccountNonExpired(true);
        newUser.setAccountNonLocked(true);
        newUser.setCredentialsNonExpired(true);
        newUser.setIsCustomer(false);

        // Asignar roles de la invitación
        List<Long> roleIds = Arrays.stream(invitation.getRoleIds().split(","))
                .map(String::trim)
                .map(Long::parseLong)
                .collect(Collectors.toList());
        List<Role> roles = roleRepo.findAllById(roleIds);
        newUser.setRoles(new HashSet<>(roles));

        try {
            userRepo.save(newUser);
        } catch (DataIntegrityViolationException ex) {
            String msg = ex.getMostSpecificCause().getMessage();
            logger.error("Error de integridad al crear usuario desde invitación: {}", msg);
            if (msg != null && msg.contains("email")) {
                throw new IllegalArgumentException("El correo ya está registrado en el sistema.");
            }
            if (msg != null && msg.contains("username")) {
                throw new IllegalArgumentException("Error al generar el nombre de usuario. Intenta de nuevo.");
            }
            throw new IllegalArgumentException("Error al crear la cuenta. Intenta de nuevo.");
        }

        // Marcar invitación como aceptada
        invitation.setStatus(InvitationStatus.ACCEPTED);
        invitation.setAcceptedAt(LocalDateTime.now());
        invitationRepo.save(invitation);

        // Auditoría
        try {
            auditLogService.logUserCreation(
                    newUser.getId(),
                    newUser.getEmail(),
                    "INVITATION:" + invitation.getId());
        } catch (Exception e) {
            logger.warn("No se pudo registrar audit log para aceptación de invitación: {}", e.getMessage());
        }

        logger.info("✅ Invitación aceptada: {} → usuario {} creado", maskEmail(invitation.getEmail()), newUser.getId());
    }

    // ══════════════════════════════════════════════════════════════
    // LISTAR INVITACIONES
    // ══════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<StaffInvitationDto> listAllInvitations(Authentication auth) {
        User actor = userRepo.findByEmail(auth.getName()).orElse(null);
        boolean isStoreManager = rolePolicyService.isStoreManager(actor) && !rolePolicyService.isTechnicalUser(actor);

        List<StaffInvitation> invitations = invitationRepo.findAllByOrderByCreatedAtDesc();
        return filterAndMapInvitations(invitations, isStoreManager, actor);
    }

    @Transactional(readOnly = true)
    public List<StaffInvitationDto> listPendingInvitations(Authentication auth) {
        User actor = userRepo.findByEmail(auth.getName()).orElse(null);
        boolean isStoreManager = rolePolicyService.isStoreManager(actor) && !rolePolicyService.isTechnicalUser(actor);

        List<StaffInvitation> invitations = invitationRepo
                .findByStatusOrderByCreatedAtDesc(InvitationStatus.PENDING);
        return filterAndMapInvitations(invitations, isStoreManager, actor);
    }

    private List<StaffInvitationDto> filterAndMapInvitations(List<StaffInvitation> invitations, boolean isStoreManager, User actor) {
        return invitations.stream()
                .filter(inv -> {
                    if (isStoreManager) {
                        List<String> roleNames = resolveRoleNames(inv.getRoleIds());
                        boolean hasTechnical = roleNames.stream().anyMatch(r -> rolePolicyService.isTechnicalRole(r) || "ROLE_STORE_MANAGER".equals(r) || "ROLE_PROJECT_ADMIN".equals(r) || "ROLE_USER".equals(r));
                        return !hasTechnical;
                    }
                    return true;
                })
                .map(inv -> {
                    if (inv.isExpired() && inv.getStatus() == InvitationStatus.PENDING) {
                        inv.setStatus(InvitationStatus.EXPIRED);
                    }
                    List<String> roleNames = resolveRoleNames(inv.getRoleIds());
                    String maskedInvitedBy = null;
                    if (isStoreManager) {
                        if (inv.getInvitedBy() != null && inv.getInvitedBy().getId().equals(actor.getId())) {
                            maskedInvitedBy = "Tú";
                        } else {
                            maskedInvitedBy = "Sistema";
                        }
                    }
                    return StaffInvitationDto.fromEntity(inv, roleNames, maskedInvitedBy);
                })
                .collect(Collectors.toList());
    }

    // ══════════════════════════════════════════════════════════════
    // CANCELAR INVITACIÓN
    // ══════════════════════════════════════════════════════════════

    @Transactional
    public void cancelInvitation(Long invitationId, Authentication auth) {
        StaffInvitation inv = invitationRepo.findById(invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("Invitación no encontrada"));

        User actor = userRepo.findByEmail(auth.getName()).orElse(null);
        if (rolePolicyService.isStoreManager(actor) && !rolePolicyService.isTechnicalUser(actor)) {
            List<String> roleNames = resolveRoleNames(inv.getRoleIds());
            boolean hasTechnical = roleNames.stream().anyMatch(r -> rolePolicyService.isTechnicalRole(r) || "ROLE_STORE_MANAGER".equals(r) || "ROLE_PROJECT_ADMIN".equals(r));
            if (hasTechnical) {
                throw new com.security.exception.SecurityHierarchyException("No tienes permiso para modificar esta invitación técnica.");
            }
        }

        if (inv.getStatus() != InvitationStatus.PENDING) {
            throw new IllegalStateException("Solo se pueden cancelar invitaciones pendientes.");
        }

        inv.setStatus(InvitationStatus.CANCELLED);
        invitationRepo.save(inv);

        try {
            auditLogService.logAction(
                    "STAFF_INVITATION_CANCELLED",
                    "STAFF_INVITATION",
                    invitationId,
                    "Invitación a " + maskEmail(inv.getEmail()) + " cancelada por " + auth.getName());
        } catch (Exception e) {
            logger.warn("No se pudo registrar audit log para cancelación: {}", e.getMessage());
        }

        logger.info("❌ Invitación {} cancelada por {}", invitationId, auth.getName());
    }

    // ══════════════════════════════════════════════════════════════
    // REENVIAR INVITACIÓN
    // ══════════════════════════════════════════════════════════════

    @Transactional
    public void resendInvitation(Long invitationId, Authentication auth) {
        StaffInvitation inv = invitationRepo.findById(invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("Invitación no encontrada"));

        User actor = userRepo.findByEmail(auth.getName()).orElse(null);
        if (rolePolicyService.isStoreManager(actor) && !rolePolicyService.isTechnicalUser(actor)) {
            List<String> roleNames = resolveRoleNames(inv.getRoleIds());
            boolean hasTechnical = roleNames.stream().anyMatch(r -> rolePolicyService.isTechnicalRole(r) || "ROLE_STORE_MANAGER".equals(r) || "ROLE_PROJECT_ADMIN".equals(r));
            if (hasTechnical) {
                throw new com.security.exception.SecurityHierarchyException("No tienes permiso para modificar esta invitación técnica.");
            }
        }

        if (inv.getStatus() == InvitationStatus.ACCEPTED) {
            throw new IllegalStateException("Esta invitación ya fue aceptada.");
        }

        // Generar nuevo token y extender expiración
        String newToken = generateSecureToken();
        inv.setTokenHash(hashToken(newToken));
        inv.setExpiresAt(LocalDateTime.now().plusHours(INVITATION_HOURS));
        inv.setStatus(InvitationStatus.PENDING);
        invitationRepo.save(inv);

        User invitedBy = userRepo.findByEmail(auth.getName())
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));

        try {
            emailService.sendStaffInvitation(
                    inv.getEmail(),
                    inv.getFirstName(),
                    invitedBy.getFirstName() + " " + invitedBy.getLastName(),
                    newToken,
                    INVITATION_HOURS);
        } catch (Exception e) {
            logger.error("Error al reenviar invitación a {}: {}", maskEmail(inv.getEmail()), e.getMessage());
        }

        try {
            auditLogService.logAction(
                    "STAFF_INVITATION_RESENT",
                    "STAFF_INVITATION",
                    invitationId,
                    "Invitación a " + maskEmail(inv.getEmail()) + " reenviada por " + auth.getName());
        } catch (Exception e) {
            logger.warn("No se pudo registrar audit log para reenvío: {}", e.getMessage());
        }

        logger.info("🔄 Invitación {} reenviada por {}", invitationId, auth.getName());
    }

    // ══════════════════════════════════════════════════════════════
    // JOB: EXPIRAR INVITACIONES VENCIDAS (2 AM diario)
    // ══════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional
    public void expireOldInvitations() {
        List<StaffInvitation> expired = invitationRepo
                .findByStatusOrderByCreatedAtDesc(InvitationStatus.PENDING)
                .stream()
                .filter(StaffInvitation::isExpired)
                .collect(Collectors.toList());

        if (!expired.isEmpty()) {
            expired.forEach(inv -> inv.setStatus(InvitationStatus.EXPIRED));
            invitationRepo.saveAll(expired);
            logger.info("🕐 {} invitaciones marcadas como expiradas", expired.size());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // UTILIDADES PRIVADAS
    // ══════════════════════════════════════════════════════════════

    /**
     * Genera un token seguro de 256 bits usando SecureRandom.
     * NUNCA usa Math.random() ni UUID.
     */
    private String generateSecureToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Calcula el hash SHA-256 de un token y lo devuelve como hex string (64 chars).
     * Se usa para almacenar y comparar tokens sin persistir el valor en texto
     * plano.
     */
    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(64);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1)
                    hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 no disponible", e);
        }
    }

    /**
     * Genera un username único basado en nombre y apellido.
     */
    private String generateUniqueUsername(String firstName, String lastName) {
        String base = (firstName.toLowerCase().charAt(0) + lastName.toLowerCase())
                .replaceAll("[^a-z0-9]", "");
        if (base.length() > 25) {
            base = base.substring(0, 25);
        }

        String username = base;
        int suffix = 1;
        while (userRepo.existsByUsername(username)) {
            username = base + suffix;
            suffix++;
        }
        return username;
    }

    /**
     * Resuelve los nombres de roles desde el CSV de IDs.
     */
    private List<String> resolveRoleNames(String roleIdsCsv) {
        if (roleIdsCsv == null || roleIdsCsv.isBlank())
            return List.of();
        try {
            List<Long> ids = Arrays.stream(roleIdsCsv.split(","))
                    .map(String::trim)
                    .map(Long::parseLong)
                    .collect(Collectors.toList());
            return roleRepo.findAllById(ids).stream()
                    .map(Role::getName)
                    .collect(Collectors.toList());
        } catch (NumberFormatException e) {
            logger.warn("Error al parsear role IDs: {}", roleIdsCsv);
            return List.of();
        }
    }

    /**
     * Enmascara un email para logs: j***@gmail.com
     */
    private String maskEmail(String email) {
        if (email == null)
            return "***";
        int at = email.indexOf('@');
        if (at <= 2)
            return "***" + email.substring(at);
        return email.charAt(0) + "***" + email.substring(at - 1);
    }
}
