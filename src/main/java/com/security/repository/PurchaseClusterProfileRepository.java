package com.security.repository;

import com.security.entity.PurchaseClusterProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Repositorio JPA para {@link PurchaseClusterProfile}.
 *
 * <p>
 * Proporciona métodos de lectura para perfiles de cluster ordenados y conteos
 * agregados necesarios para la verificación de integridad del módulo de analytics.
 * </p>
 */
public interface PurchaseClusterProfileRepository extends JpaRepository<PurchaseClusterProfile, Long> {

    /**
     * Obtiene todos los perfiles de una ejecución ordenados por número de cluster.
     *
     * @param runId identificador de la ejecución
     * @return lista de perfiles ordenada ascendentemente por clusterNumber
     */
    List<PurchaseClusterProfile> findByRunIdOrderByClusterNumberAsc(Long runId);

    /**
     * Busca un perfil concreto por ejecución y número de cluster.
     *
     * @param runId         identificador de la ejecución
     * @param clusterNumber número del cluster (0-based)
     * @return el perfil si existe, o vacío si no hay coincidencia
     */
    Optional<PurchaseClusterProfile> findByRunIdAndClusterNumber(Long runId, Short clusterNumber);

    /**
     * Cuenta cuántos perfiles existen para una ejecución.
     * Permite verificar que {@code selectedK == actualProfiles}.
     *
     * @param runId identificador de la ejecución
     * @return número de perfiles registrados
     */
    long countByRunId(Long runId);

    /**
     * Suma los {@code order_count} de todos los perfiles de una ejecución.
     * Permite verificar que la suma coincida con {@code orders_analyzed}.
     *
     * @param runId identificador de la ejecución
     * @return suma total de órdenes en todos los perfiles
     */
    @Query("SELECT COALESCE(SUM(p.orderCount), 0) FROM PurchaseClusterProfile p WHERE p.run.id = :runId")
    long sumOrderCountByRunId(@Param("runId") Long runId);

    /**
     * Suma los porcentajes de todos los perfiles de una ejecución.
     * Se usa para validar que la suma sea cercana a 100.
     *
     * @param runId identificador de la ejecución
     * @return suma total de porcentajes
     */
    @Query("SELECT COALESCE(SUM(p.percentage), 0) FROM PurchaseClusterProfile p WHERE p.run.id = :runId")
    BigDecimal sumPercentageByRunId(@Param("runId") Long runId);
}
