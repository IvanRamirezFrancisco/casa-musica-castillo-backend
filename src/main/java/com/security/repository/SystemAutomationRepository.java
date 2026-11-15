package com.security.repository;

import com.security.entity.SystemAutomation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repositorio JPA para {@link SystemAutomation}.
 *
 * <p>
 * Spring Data genera automáticamente Prepared Statements,
 * por lo que todas las consultas están protegidas contra SQL Injection.
 * </p>
 */
@Repository
public interface SystemAutomationRepository extends JpaRepository<SystemAutomation, Long> {

    /** Obtiene todas las automatizaciones habilitadas (para arranque del motor). */
    List<SystemAutomation> findByEnabledTrue();

    /** Busca una automatización por su nombre único de job. */
    Optional<SystemAutomation> findByJobName(String jobName);

    /** Lista automatizaciones por grupo (SECURITY, MAINTENANCE, ALERTS). */
    List<SystemAutomation> findByJobGroupOrderByDisplayNameAsc(String jobGroup);
}
