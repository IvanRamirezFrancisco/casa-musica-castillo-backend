package com.security.service.admin;

import com.security.dto.admin.CustomerListDTO;
import com.security.entity.PasswordRecoveryAttempt;
import com.security.entity.User;
import com.security.exception.ResourceNotFoundException;
import com.security.exception.SecurityViolationException;
import com.security.repository.PasswordRecoveryAttemptRepository;
import com.security.repository.UserRepository;
import com.security.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Servicio para gestión de Clientes (is_customer = true).
 * Implementa lógica de negocio con validaciones de seguridad Enterprise.
 *
 * INVARIANTES DE SEGURIDAD:
 * - Solo opera sobre usuarios donde is_customer = true
 * - No permite modificar directamente datos de compra (totalOrders, totalSpent)
 * - Registra auditoría en todas las operaciones sensibles
 * - No expone información sensible (password, tokens 2FA, etc.)
 */
@Service
public class AdminCustomerService {

    private static final Logger logger = LoggerFactory.getLogger(AdminCustomerService.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private PasswordRecoveryAttemptRepository passwordRecoveryAttemptRepository;

    // ==================== Consultas de Listado ====================

    /**
     * Obtener todos los clientes con paginación y ordenamiento.
     *
     * @param page    Número de página (0-based)
     * @param size    Elementos por página (máximo 100 por seguridad)
     * @param sortBy  Campo por el que ordenar
     * @param sortDir Dirección del ordenamiento ("asc" o "desc")
     * @return Página de CustomerListDTO
     */
    @Transactional(readOnly = true)
    public Page<CustomerListDTO> getAllCustomers(int page, int size, String sortBy, String sortDir) {
        // Hardcap de seguridad: máximo 100 registros por página
        int safeSize = Math.min(size, 100);

        // Validar el campo de ordenamiento para evitar SQL injection
        String safeSortBy = validateSortField(sortBy);

        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(safeSortBy).ascending()
                : Sort.by(safeSortBy).descending();

        Pageable pageable = PageRequest.of(page, safeSize, sort);
        Page<User> customersPage = userRepository.findAllCustomers(pageable);

        return customersPage.map(this::convertToListDTO);
    }

    /**
     * Buscar clientes con filtros combinados.
     *
     * @param searchTerm       Término de búsqueda (nombre, apellido, email,
     *                         teléfono)
     * @param enabled          Filtro por estado habilitado (null = todos)
     * @param accountNonLocked Filtro por estado de bloqueo (null = todos)
     * @param page             Número de página
     * @param size             Elementos por página
     * @return Página filtrada de CustomerListDTO
     */
    @Transactional(readOnly = true)
    public Page<CustomerListDTO> searchCustomers(String searchTerm, Boolean enabled,
            Boolean accountNonLocked, int page, int size) {
        int safeSize = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, safeSize, Sort.by("createdAt").descending());

        // Sanitizar el término de búsqueda
        String safeSearch = (searchTerm != null && !searchTerm.trim().isEmpty())
                ? searchTerm.trim()
                : "";

        Page<User> customersPage = userRepository.findCustomersWithFilters(
                safeSearch, enabled, accountNonLocked, pageable);

        return customersPage.map(this::convertToListDTO);
    }

    // ==================== Operaciones de Estado ====================

    /**
     * Obtener un cliente por ID con sus datos completos.
     *
     * @param customerId ID del cliente
     * @return CustomerListDTO con los datos del cliente
     * @throws ResourceNotFoundException  si el cliente no existe
     * @throws SecurityViolationException si el usuario no es un cliente
     */
    @Transactional(readOnly = true)
    public CustomerListDTO getCustomerById(Long customerId) {
        User customer = findAndValidateCustomer(customerId);
        return convertToListDTO(customer);
    }

    /**
     * Activar o desactivar la cuenta de un cliente (toggle).
     * CRÍTICO: Solo opera sobre clientes (is_customer = true).
     *
     * @param customerId ID del cliente a modificar
     * @return DTO actualizado del cliente
     * @throws ResourceNotFoundException  si el cliente no existe
     * @throws SecurityViolationException si el usuario no es un cliente
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CustomerListDTO toggleEnabledStatus(Long customerId) {
        User customer = findAndValidateCustomer(customerId);

        String currentAdmin = getCurrentUsername();
        boolean newState = !customer.getEnabled();

        customer.setEnabled(newState);
        User savedCustomer = userRepository.save(customer);

        // Auditoría
        if (newState) {
            auditLogService.logUserActivation(savedCustomer.getId(), savedCustomer.getEmail(), currentAdmin);
            logger.info("✅ Cliente activado: {} (ID: {}) por admin: {}",
                    savedCustomer.getEmail(), savedCustomer.getId(), currentAdmin);
        } else {
            auditLogService.logUserDeactivation(savedCustomer.getId(), savedCustomer.getEmail(), currentAdmin);
            logger.info("🔴 Cliente desactivado: {} (ID: {}) por admin: {}",
                    savedCustomer.getEmail(), savedCustomer.getId(), currentAdmin);
        }

        return convertToListDTO(savedCustomer);
    }

    /**
     * Bloquear o desbloquear la cuenta de un cliente (toggle).
     * CRÍTICO: Solo opera sobre clientes (is_customer = true).
     *
     * @param customerId ID del cliente a modificar
     * @return DTO actualizado del cliente
     * @throws ResourceNotFoundException  si el cliente no existe
     * @throws SecurityViolationException si el usuario no es un cliente
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CustomerListDTO toggleLockedStatus(Long customerId) {
        User customer = findAndValidateCustomer(customerId);

        String currentAdmin = getCurrentUsername();
        // accountNonLocked=true → cuenta desbloqueada → el toggle la BLOQUEA (→ false)
        // accountNonLocked=false → cuenta bloqueada → el toggle la DESBLOQUEA (→ true)
        boolean newAccountNonLocked = !Boolean.TRUE.equals(customer.getAccountNonLocked());

        customer.setAccountNonLocked(newAccountNonLocked);
        User savedCustomer = userRepository.save(customer);

        // Auditoría
        if (!newAccountNonLocked) {
            // newAccountNonLocked=false → cuenta bloqueada
            auditLogService.logAccountLock(savedCustomer.getId(), savedCustomer.getEmail(), currentAdmin);
            logger.info("🔒 Cuenta de cliente bloqueada: {} (ID: {}) por admin: {}",
                    savedCustomer.getEmail(), savedCustomer.getId(), currentAdmin);
        } else {
            // newAccountNonLocked=true → cuenta desbloqueada
            auditLogService.logAccountUnlock(savedCustomer.getId(), savedCustomer.getEmail(), currentAdmin);
            logger.info("🔓 Cuenta de cliente desbloqueada: {} (ID: {}) por admin: {}",
                    savedCustomer.getEmail(), savedCustomer.getId(), currentAdmin);
        }

        return convertToListDTO(savedCustomer);
    }

    /**
     * Resetear intentos fallidos de login de un cliente y desbloquear la cuenta.
     *
     * @param customerId ID del cliente
     * @return DTO actualizado del cliente
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CustomerListDTO resetFailedLoginAttempts(Long customerId) {
        User customer = findAndValidateCustomer(customerId);

        String currentAdmin = getCurrentUsername();

        customer.setAccountNonLocked(true);
        if (customer.getEnabled() == null || !customer.getEnabled()) {
            customer.setEnabled(true);
        }
        User savedCustomer = userRepository.save(customer);

        auditLogService.logAccountUnlock(savedCustomer.getId(), savedCustomer.getEmail(), currentAdmin);
        logger.info("🔄 Intentos fallidos reseteados para cliente: {} (ID: {}) por admin: {}",
                savedCustomer.getEmail(), savedCustomer.getId(), currentAdmin);

        return convertToListDTO(savedCustomer);
    }

    /**
     * Resetear el bloqueo de recuperación de contraseña de un cliente.
     * Elimina todos los registros de {@code password_recovery_attempts} asociados
     * al email del cliente, permitiéndole volver a solicitar recuperación de
     * contraseña desde cero.
     *
     * <p>
     * Solo debe ser llamado por un administrador autenticado.
     * </p>
     *
     * @param customerId ID del cliente cuyo bloqueo se desea eliminar
     * @return DTO actualizado del cliente
     * @throws ResourceNotFoundException  si el cliente no existe
     * @throws SecurityViolationException si el usuario no es un cliente
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CustomerListDTO resetPasswordRecoveryBlock(Long customerId) {
        User customer = findAndValidateCustomer(customerId);

        String currentAdmin = getCurrentUsername();

        List<PasswordRecoveryAttempt> attempts = passwordRecoveryAttemptRepository.findAllByEmail(customer.getEmail());

        if (!attempts.isEmpty()) {
            for (PasswordRecoveryAttempt attempt : attempts) {
                attempt.resetByAdmin();
            }
            passwordRecoveryAttemptRepository.saveAll(attempts);
            logger.info("🔓 Bloqueo de recuperación de contraseña reseteado para cliente: {} (ID: {}) "
                    + "por admin: {} — {} registros afectados",
                    customer.getEmail(), customer.getId(), currentAdmin, attempts.size());
        } else {
            logger.info("ℹ️ No se encontraron registros de bloqueo de recuperación para cliente: {} (ID: {})",
                    customer.getEmail(), customer.getId());
        }

        auditLogService.log(
                "RESET_RECOVERY_BLOCK",
                "SECURITY_EVENT",
                "USER",
                customer.getId(),
                null,
                null,
                "WARNING",
                true);

        return convertToListDTO(customer);
    }

    // ==================== Export ====================

    /**
     * Exportar lista de clientes a formato CSV con BOM UTF-8.
     *
     * @param searchTerm Filtro de búsqueda opcional
     * @return String con el contenido CSV
     */
    @Transactional(readOnly = true)
    public String exportCustomersToCsv(String searchTerm) {
        // Obtener hasta 10.000 registros para el export
        Pageable pageable = PageRequest.of(0, 10000, Sort.by("createdAt").descending());

        String safeSearch = (searchTerm != null && !searchTerm.trim().isEmpty())
                ? searchTerm.trim()
                : "";

        Page<User> customersPage = userRepository.findCustomersWithFilters(safeSearch, null, null, pageable);

        StringBuilder csv = new StringBuilder();
        // BOM para correcta visualización en Excel
        csv.append('\uFEFF');
        csv.append("ID,Nombre,Apellido,Email,Teléfono,Pedidos,Total Gastado,Estado,Cuenta,Registro\n");

        for (User customer : customersPage.getContent()) {
            csv.append(customer.getId()).append(",");
            csv.append(escapeCsv(customer.getFirstName())).append(",");
            csv.append(escapeCsv(customer.getLastName())).append(",");
            csv.append(escapeCsv(customer.getEmail())).append(",");
            csv.append(escapeCsv(customer.getPhone() != null ? customer.getPhone() : "")).append(",");
            csv.append(customer.getTotalOrders() != null ? customer.getTotalOrders() : 0).append(",");
            csv.append(customer.getTotalSpent() != null ? customer.getTotalSpent().toPlainString() : "0.00")
                    .append(",");
            csv.append(Boolean.TRUE.equals(customer.getEnabled()) ? "Activo" : "Inactivo").append(",");
            csv.append(Boolean.TRUE.equals(customer.getAccountNonLocked()) ? "Desbloqueada" : "Bloqueada")
                    .append(",");
            csv.append(customer.getCreatedAt() != null ? customer.getCreatedAt().toString() : "").append("\n");
        }

        String currentAdmin = getCurrentUsername();
        logger.info("📊 CSV de clientes exportado por admin: {} ({} registros)", currentAdmin,
                customersPage.getTotalElements());

        return csv.toString();
    }

    // ==================== Helpers privados ====================

    /**
     * Buscar un usuario y validar que sea un cliente real (is_customer = true).
     *
     * @throws ResourceNotFoundException  si no existe
     * @throws SecurityViolationException si no es un cliente
     */
    private User findAndValidateCustomer(Long customerId) {
        User user = userRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Cliente no encontrado con ID: " + customerId));

        if (!Boolean.TRUE.equals(user.getIsCustomer())) {
            throw new SecurityViolationException(
                    "El usuario ID " + customerId + " no es un cliente registrado");
        }
        return user;
    }

    /**
     * Validar y sanear el campo de ordenamiento para prevenir SQL injection.
     * Solo permite campos definidos en la whitelist.
     */
    private String validateSortField(String sortBy) {
        return switch (sortBy) {
            case "firstName", "lastName", "email", "totalOrders", "totalSpent",
                    "enabled", "accountNonLocked", "createdAt", "updatedAt" ->
                sortBy;
            default -> "createdAt";
        };
    }

    /**
     * Convertir entidad User a CustomerListDTO.
     * CRÍTICO: No expone password, tokens ni secretos 2FA.
     */
    private CustomerListDTO convertToListDTO(User user) {
        CustomerListDTO dto = new CustomerListDTO();
        dto.setId(user.getId());
        dto.setFirstName(user.getFirstName());
        dto.setLastName(user.getLastName());
        dto.setEmail(user.getEmail());
        dto.setPhone(user.getPhone());
        dto.setTotalOrders(user.getTotalOrders() != null ? user.getTotalOrders() : 0);
        dto.setTotalSpent(user.getTotalSpent() != null
                ? user.getTotalSpent()
                : java.math.BigDecimal.ZERO);
        dto.setEnabled(user.getEnabled());
        dto.setAccountNonLocked(user.getAccountNonLocked());
        dto.setCreatedAt(user.getCreatedAt());
        // lastLogin no está en la entidad actual — se puede agregar en el futuro
        return dto;
    }

    /**
     * Escapar un campo para CSV (rodea con comillas si contiene coma, comilla o
     * salto de línea).
     */
    private String escapeCsv(String value) {
        if (value == null)
            return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * Obtener el username del administrador autenticado actualmente.
     */
    private String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "SYSTEM";
    }
}
