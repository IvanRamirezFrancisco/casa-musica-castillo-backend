package com.security.repository;

import com.security.entity.BackupLog;
import com.security.enums.BackupStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Collection;

/**
 * Repositorio JPA para la tabla {@code backup_logs}.
 *
 * <p>
 * Proporciona acceso de lectura/escritura al historial de respaldos
 * y helpers para la política de retención (soft-delete).
 */
@Repository
public interface BackupLogRepository extends JpaRepository<BackupLog, Long> {

  /**
   * Historial completo paginado, ordenado de más reciente a más antiguo.
   * Excluye registros marcados con soft-delete (is_deleted = true)
   * ya que el archivo físico no existe; el Front solo muestra entradas vigentes.
   */
  Page<BackupLog> findByIsDeletedFalseOrderByCreatedAtDesc(Pageable pageable);

  /**
   * Lista completa sin paginado (para el front que muestra las últimas N
   * entradas).
   */
  List<BackupLog> findTop50ByIsDeletedFalseOrderByCreatedAtDesc();

  /**
   * Respaldos exitosos que llevan más de {@code days} días en disco.
   * Usado por el CronJob de política de retención para identificar archivos a
   * purgar.
   */
  @Query("""
      SELECT b FROM BackupLog b
      WHERE b.status       = :status
        AND b.isDeleted    = false
        AND b.createdAt   <= :cutoff
      """)
  List<BackupLog> findOldCompletedBackups(
      @Param("status") BackupStatus status,
      @Param("cutoff") LocalDateTime cutoff);

  /**
   * Marca múltiples registros como eliminados (soft-delete) en una sola query.
   * Se usa cuando el cron borra los archivos físicos.
   *
   * @param ids Lista de IDs a marcar.
   */
  @Modifying
  @Transactional
  @Query("UPDATE BackupLog b SET b.isDeleted = true WHERE b.id IN :ids")
  void softDeleteByIds(@Param("ids") List<Long> ids);

  /**
   * Cuenta los respaldos completados exitosamente en los últimos {@code days}
   * días.
   * Útil para el panel de métricas del dashboard.
   */
  @Query("""
      SELECT COUNT(b) FROM BackupLog b
      WHERE b.status    = com.security.enums.BackupStatus.COMPLETED
        AND b.createdAt >= :since
      """)
  long countCompletedSince(@Param("since") LocalDateTime since);

  /**
   * Busca todos los respaldos en un estado dado.
   * Usado al arrancar la aplicación para detectar backups «zombie» (PENDING).
   */
  List<BackupLog> findByStatus(BackupStatus status);

  /**
   * Actualiza el estado, mensaje de error y log de ejecución de múltiples
   * respaldos en una sola query. Usado para la recuperación masiva en arranque.
   */
  @Modifying
  @Transactional
  @Query("""
      UPDATE BackupLog b
      SET b.status       = :status,
          b.errorMessage = :errorMessage,
          b.executionLog = :executionLog
      WHERE b.id IN :ids
      """)
  void bulkUpdateStatus(
      @Param("ids") Collection<Long> ids,
      @Param("status") BackupStatus status,
      @Param("errorMessage") String errorMessage,
      @Param("executionLog") String executionLog);
}
