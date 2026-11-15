package com.security.repository;

import com.security.entity.AutomationExecutionLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repositorio JPA para {@link AutomationExecutionLog}.
 *
 * <p>
 * Spring Data genera automáticamente Prepared Statements,
 * protegiendo contra SQL Injection.
 * </p>
 */
@Repository
public interface AutomationExecutionLogRepository
        extends JpaRepository<AutomationExecutionLog, Long> {

    /**
     * Obtiene el historial de ejecuciones de una automatización,
     * ordenado por fecha de inicio descendente (más reciente primero).
     */
    Page<AutomationExecutionLog> findByAutomationIdOrderByStartedAtDesc(
            Long automationId, Pageable pageable);
}
