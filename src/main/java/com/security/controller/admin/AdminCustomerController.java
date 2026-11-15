package com.security.controller.admin;

import com.security.dto.admin.CustomerListDTO;
import com.security.service.admin.AdminCustomerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Controlador REST para gestión de Clientes (is_customer = true).
 *
 * Todos los endpoints exigen autenticación y al menos ROLE_ADMIN.
 * Las operaciones destructivas (delete) requieren ROLE_SUPER_ADMIN.
 *
 * Base URL: /api/admin/customers
 */
@RestController
@RequestMapping("/api/admin/customers")
public class AdminCustomerController {

    @Autowired
    private AdminCustomerService adminCustomerService;

    // ==================== Listado & Búsqueda ====================

    /**
     * GET /api/admin/customers
     * Listar todos los clientes paginados, ordenados por fecha de registro desc.
     *
     * Query params:
     * page (default 0)
     * size (default 10, máx 100)
     * sortBy (default "createdAt")
     * sortDir (default "desc")
     */
    @GetMapping
    @PreAuthorize("hasAuthority('CUSTOMER_READ')")
    public ResponseEntity<Map<String, Object>> getAllCustomers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {

        Page<CustomerListDTO> customersPage = adminCustomerService.getAllCustomers(page, size, sortBy, sortDir);

        Map<String, Object> response = new HashMap<>();
        response.put("customers", customersPage.getContent());
        response.put("currentPage", customersPage.getNumber());
        response.put("totalItems", customersPage.getTotalElements());
        response.put("totalPages", customersPage.getTotalPages());

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/admin/customers/search
     * Buscar clientes con filtros combinados.
     *
     * Query params:
     * searchTerm — texto libre (nombre, apellido, email, teléfono)
     * enabled — true | false
     * accountNonLocked — true | false
     * page / size
     */
    @GetMapping("/search")
    @PreAuthorize("hasAuthority('CUSTOMER_READ')")
    public ResponseEntity<Map<String, Object>> searchCustomers(
            @RequestParam(required = false) String searchTerm,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Boolean accountNonLocked,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Page<CustomerListDTO> customersPage = adminCustomerService.searchCustomers(searchTerm, enabled,
                accountNonLocked, page, size);

        Map<String, Object> response = new HashMap<>();
        response.put("customers", customersPage.getContent());
        response.put("currentPage", customersPage.getNumber());
        response.put("totalItems", customersPage.getTotalElements());
        response.put("totalPages", customersPage.getTotalPages());

        return ResponseEntity.ok(response);
    }

    // ==================== Detalle ====================

    /**
     * GET /api/admin/customers/{id}
     * Obtener los datos de un cliente por su ID.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('CUSTOMER_READ')")
    public ResponseEntity<Map<String, Object>> getCustomerById(@PathVariable Long id) {
        CustomerListDTO customer = adminCustomerService.getCustomerById(id);

        Map<String, Object> response = new HashMap<>();
        response.put("customer", customer);

        return ResponseEntity.ok(response);
    }

    // ==================== Cambios de Estado ====================

    /**
     * PATCH /api/admin/customers/{id}/toggle-enabled
     * Activar o desactivar la cuenta de un cliente (toggle).
     * CRÍTICO: No permite que un admin se desactive a sí mismo si fuera cliente.
     */
    @PatchMapping("/{id}/toggle-enabled")
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    public ResponseEntity<Map<String, Object>> toggleEnabledStatus(@PathVariable Long id) {
        CustomerListDTO updatedCustomer = adminCustomerService.toggleEnabledStatus(id);

        Map<String, Object> response = new HashMap<>();
        response.put("message", Boolean.TRUE.equals(updatedCustomer.getEnabled())
                ? "Cliente activado exitosamente"
                : "Cliente desactivado exitosamente");
        response.put("customer", updatedCustomer);

        return ResponseEntity.ok(response);
    }

    /**
     * PATCH /api/admin/customers/{id}/toggle-locked
     * Bloquear o desbloquear la cuenta de un cliente (toggle).
     */
    @PatchMapping("/{id}/toggle-locked")
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    public ResponseEntity<Map<String, Object>> toggleLockedStatus(@PathVariable Long id) {
        CustomerListDTO updatedCustomer = adminCustomerService.toggleLockedStatus(id);

        Map<String, Object> response = new HashMap<>();
        response.put("message", Boolean.TRUE.equals(updatedCustomer.getAccountNonLocked())
                ? "Cuenta de cliente desbloqueada exitosamente"
                : "Cuenta de cliente bloqueada exitosamente");
        response.put("customer", updatedCustomer);

        return ResponseEntity.ok(response);
    }

    /**
     * PATCH /api/admin/customers/{id}/reset-failed-attempts
     * Resetear intentos fallidos de login y desbloquear la cuenta.
     */
    @PatchMapping("/{id}/reset-failed-attempts")
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    public ResponseEntity<Map<String, Object>> resetFailedAttempts(@PathVariable Long id) {
        CustomerListDTO updatedCustomer = adminCustomerService.resetFailedLoginAttempts(id);

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Intentos fallidos reseteados y cuenta desbloqueada");
        response.put("customer", updatedCustomer);

        return ResponseEntity.ok(response);
    }

    /**
     * PATCH /api/admin/customers/{id}/reset-recovery-block
     * Resetear el bloqueo progresivo de recuperación de contraseña de un cliente.
     * Elimina todos los registros de password_recovery_attempts para su email,
     * permitiéndole solicitar recuperación de contraseña desde cero.
     */
    @PatchMapping("/{id}/reset-recovery-block")
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    public ResponseEntity<Map<String, Object>> resetRecoveryBlock(@PathVariable Long id) {
        CustomerListDTO updatedCustomer = adminCustomerService.resetPasswordRecoveryBlock(id);

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Bloqueo de recuperación de contraseña reseteado exitosamente");
        response.put("customer", updatedCustomer);

        return ResponseEntity.ok(response);
    }

    // ==================== Exportación ====================

    /**
     * GET /api/admin/customers/export/csv
     * Exportar la lista de clientes a un archivo CSV con BOM UTF-8.
     *
     * Query params:
     * search — término de búsqueda opcional para filtrar el export
     */
    @GetMapping("/export/csv")
    @PreAuthorize("hasAuthority('REPORT_EXPORT')")
    public ResponseEntity<byte[]> exportCustomersToCsv(
            @RequestParam(required = false) String search) {

        String csvContent = adminCustomerService.exportCustomersToCsv(search);
        byte[] csvBytes = csvContent.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.setContentDispositionFormData("attachment", "clientes_export.csv");
        headers.setContentLength(csvBytes.length);

        return new ResponseEntity<>(csvBytes, headers, HttpStatus.OK);
    }
}
