package com.security.repository;

import com.security.entity.MediaMigrationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repositorio para los logs de migración de medios (Fase 6D).
 */
@Repository
public interface MediaMigrationLogRepository extends JpaRepository<MediaMigrationLog, Long> {

    /** Buscar logs por entidad */
    List<MediaMigrationLog> findByEntityTypeAndEntityId(String entityType, Long entityId);

    /** Buscar logs por status */
    List<MediaMigrationLog> findByStatus(String status);

    /** Logs del último execute */
    List<MediaMigrationLog> findByActionOrderByMigratedAtDesc(String action);

    /** Conteo por status */
    @Query("SELECT m.status, COUNT(m) FROM MediaMigrationLog m WHERE m.action = :action GROUP BY m.status")
    List<Object[]> countByStatusForAction(@Param("action") String action);
}
