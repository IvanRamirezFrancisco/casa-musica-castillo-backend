package com.security.dto.admin.analytics;

import java.util.List;

/**
 * DTO de respuesta paginada para el listado de órdenes de un cluster.
 *
 * <p>
 * Encapsula la información de paginación de forma estable y desacoplada de la
 * implementación interna de {@code org.springframework.data.domain.Page}, evitando
 * que los detalles de la clase Spring se expongan directamente en la API REST.
 * </p>
 *
 * @param content       Lista de órdenes de la página actual.
 * @param page          Número de página actual (0-based).
 * @param size          Tamaño de página solicitado.
 * @param totalElements Total de órdenes en el cluster.
 * @param totalPages    Total de páginas disponibles.
 * @param first         {@code true} si es la primera página.
 * @param last          {@code true} si es la última página.
 * @param empty         {@code true} si la página no contiene elementos.
 */
public record PurchaseClusterOrdersPageDTO(
        List<PurchaseClusterOrderDTO> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last,
        boolean empty
) {
}
