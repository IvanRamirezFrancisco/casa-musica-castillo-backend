package com.security.controller.admin;

import com.security.dto.admin.StaffRecipientDto;
import com.security.entity.SystemAutomation;
import com.security.entity.User;
import com.security.repository.SystemAutomationRepository;
import com.security.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Controller unificado para la configuración de alertas de stock bajo.
 *
 * <p>
 * La configuración se almacena exclusivamente en
 * {@code system_automations.parameters}
 * (JSONB) del job {@code INVENTORY_AUDIT_JOB}. Ya no existe una tabla separada.
 * </p>
 *
 * <h3>Estructura de parameters esperada:</h3>
 * 
 * <pre>
 * {
 *   "stock_threshold": 10,
 *   "notify_emails": ["admin@empresa.com", "supervisor@empresa.com"],
 *   "super_admin_emails": ["super@empresa.com"],
 *   "alert_on_failure": true,
 *   "failure_notify_emails": ["admin@empresa.com"]
 * }
 * </pre>
 *
 * <h3>Control de acceso:</h3>
 * <ul>
 * <li>SUPER_ADMIN: acceso total (puede activar/desactivar, cambiar umbral,
 * agregar/quitar todos los emails)</li>
 * <li>ADMIN: puede agregar/quitar emails regulares, NO puede quitar
 * super_admin_emails, NO puede cambiar enabled ni threshold</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/stock-alerts")
@PreAuthorize("hasAuthority('PRODUCT_READ')")
public class StockAlertsController {

    private static final Logger log = LoggerFactory.getLogger(StockAlertsController.class);
    private static final String INVENTORY_JOB = "INVENTORY_AUDIT_JOB";

    private final SystemAutomationRepository automationRepo;
    private final UserRepository userRepository;

    public StockAlertsController(SystemAutomationRepository automationRepo,
            UserRepository userRepository) {
        this.automationRepo = automationRepo;
        this.userRepository = userRepository;
    }

    // =========================================================================
    // GET /api/admin/stock-alerts/config
    // =========================================================================

    /**
     * Obtiene la configuración actual de alertas de stock desde
     * system_automations.parameters del INVENTORY_AUDIT_JOB.
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        SystemAutomation auto = automationRepo.findByJobName(INVENTORY_JOB)
                .orElse(null);

        Map<String, Object> response = new LinkedHashMap<>();

        if (auto == null) {
            response.put("enabled", false);
            response.put("stockThreshold", 10);
            response.put("notifyEmails", Collections.emptyList());
            response.put("superAdminEmails", Collections.emptyList());
            return ResponseEntity.ok(response);
        }

        Map<String, Object> params = auto.getParameters();
        if (params == null)
            params = Collections.emptyMap();

        response.put("enabled", auto.isEnabled());
        response.put("stockThreshold",
                params.containsKey("stock_threshold")
                        ? ((Number) params.get("stock_threshold")).intValue()
                        : 10);

        // notify_emails (regulares)
        response.put("notifyEmails", extractEmailList(params, "notify_emails"));
        // super_admin_emails (protegidos)
        response.put("superAdminEmails", extractEmailList(params, "super_admin_emails"));

        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // PUT /api/admin/stock-alerts/config
    // =========================================================================

    /**
     * Actualiza la configuración de alertas de stock.
     *
     * <p>
     * <strong>Payload esperado:</strong>
     * </p>
     * 
     * <pre>
     * {
     *   "enabled": true,
     *   "stockThreshold": 10,
     *   "notifyEmails": ["email1@emp.com", "email2@emp.com"]
     * }
     * </pre>
     *
     * <p>
     * Si el usuario es ADMIN, no puede modificar enabled, stockThreshold ni
     * quitar emails de super_admin_emails.
     * </p>
     */
    @PutMapping("/config")
    public ResponseEntity<?> updateConfig(@RequestBody Map<String, Object> body,
            Authentication auth) {
        boolean isSuperAdmin = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a));

        SystemAutomation auto = automationRepo.findByJobName(INVENTORY_JOB)
                .orElseThrow(() -> new IllegalStateException(
                        "Automatización INVENTORY_AUDIT_JOB no encontrada en la BD."));

        Map<String, Object> params = auto.getParameters();
        if (params == null)
            params = new HashMap<>();
        else
            params = new HashMap<>(params); // copia mutable

        if (isSuperAdmin) {
            // SUPER_ADMIN puede cambiar todo
            if (body.containsKey("enabled")) {
                auto.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
            }
            if (body.containsKey("stockThreshold")) {
                params.put("stock_threshold", ((Number) body.get("stockThreshold")).intValue());
            }
        }
        // ADMIN NO puede cambiar enabled ni threshold

        // Procesar notifyEmails
        if (body.containsKey("notifyEmails")) {
            List<String> incoming = toStringList(body.get("notifyEmails"));

            // Validar que todos los emails pertenezcan a empleados activos
            Set<String> activeEmails = userRepository.findActiveStaffMembers().stream()
                    .map(User::getEmail)
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet());

            List<String> valid = incoming.stream()
                    .map(String::toLowerCase)
                    .map(String::trim)
                    .filter(e -> !e.isEmpty())
                    .filter(activeEmails::contains)
                    .distinct()
                    .toList();

            // Determinar super_admin_emails actuales
            List<String> currentSuperAdminEmails = extractEmailList(params, "super_admin_emails");

            if (!isSuperAdmin) {
                // ADMIN no puede quitar super_admin_emails → forzar que sigan incluidos
                LinkedHashSet<String> merged = new LinkedHashSet<>(valid);
                for (String sae : currentSuperAdminEmails) {
                    merged.add(sae.toLowerCase());
                }
                valid = new ArrayList<>(merged);
            }

            params.put("notify_emails", valid);
        }

        // Procesar superAdminEmails (solo SUPER_ADMIN puede configurar)
        if (isSuperAdmin && body.containsKey("superAdminEmails")) {
            List<String> superEmails = toStringList(body.get("superAdminEmails"));
            params.put("super_admin_emails", superEmails.stream()
                    .map(String::toLowerCase)
                    .map(String::trim)
                    .filter(e -> !e.isEmpty())
                    .distinct()
                    .toList());
        }

        auto.setParameters(params);
        automationRepo.save(auto);

        log.info("📋 [StockAlerts] Configuración actualizada por {} (superAdmin={})",
                auth.getName(), isSuperAdmin);

        // Retornar la config actualizada
        return getConfig();
    }

    // =========================================================================
    // GET /api/admin/stock-alerts/staff-recipients
    // =========================================================================

    /**
     * Lista empleados activos con flag isSuperAdmin para el selector de
     * destinatarios.
     */
    @GetMapping("/staff-recipients")
    public ResponseEntity<List<StaffRecipientDto>> listStaffRecipients() {
        List<User> activeStaff = userRepository.findActiveStaffMembers();

        List<StaffRecipientDto> recipients = activeStaff.stream()
                .map(u -> {
                    String role = u.getRoles().stream()
                            .map(r -> r.getName())
                            .findFirst()
                            .orElse("Sin rol");
                    boolean superAdmin = u.getRoles().stream()
                            .anyMatch(r -> "ROLE_SUPER_ADMIN".equals(r.getName()));
                    return new StaffRecipientDto(
                            u.getId(),
                            u.getFirstName() + " " + u.getLastName(),
                            u.getEmail(),
                            role,
                            superAdmin);
                })
                .toList();

        return ResponseEntity.ok(recipients);
    }

    // =========================================================================
    // Utilidades privadas
    // =========================================================================

    @SuppressWarnings("unchecked")
    private List<String> extractEmailList(Map<String, Object> params, String key) {
        if (params == null || !params.containsKey(key))
            return Collections.emptyList();
        Object raw = params.get(key);
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .map(String::trim)
                    .filter(e -> !e.isEmpty())
                    .toList();
        }
        if (raw instanceof String str && !str.isBlank()) {
            return Arrays.stream(str.split(","))
                    .map(String::trim)
                    .filter(e -> !e.isEmpty())
                    .toList();
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .collect(Collectors.toList());
        }
        if (raw instanceof String str) {
            return Arrays.stream(str.split(","))
                    .map(String::trim)
                    .filter(e -> !e.isEmpty())
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
}
