package com.security.repository;

import com.security.entity.PurchaseClusteringRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repositorio JPA para {@link PurchaseClusteringRun}.
 *
 * <p>
 * Todas las consultas son de solo lectura; la tabla es gestionada exclusivamente
 * por el notebook de Python/Jupyter.
 * </p>
 */
public interface PurchaseClusteringRunRepository extends JpaRepository<PurchaseClusteringRun, Long> {

    /**
     * Busca la ejecución activa más reciente con estado COMPLETED.
     *
     * <p>
     * Aunque la base de datos solo permite una ejecución activa en cada momento,
     * el {@code ORDER BY executed_at DESC} con {@code findFirst} garantiza
     * determinismo incluso si esa restricción cambiara en el futuro.
     * </p>
     *
     * @param status el estado requerido, debe ser {@code "COMPLETED"}
     * @return la ejecución activa y completada más reciente, o vacío si no existe
     */
    Optional<PurchaseClusteringRun> findFirstByActiveTrueAndStatusOrderByExecutedAtDesc(String status);
}
