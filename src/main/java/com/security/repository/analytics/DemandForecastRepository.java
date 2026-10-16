package com.security.repository.analytics;

import com.security.dto.admin.analytics.forecast.ProductHistoryRecord;
import com.security.dto.admin.analytics.forecast.RawWeeklyData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.util.*;

@Repository
public class DemandForecastRepository {

    private final JdbcTemplate jdbcTemplate;

    public DemandForecastRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ProductHistoryRecord> findAllProductsWithHistory() {
        String sql = """
            SELECT 
                p.id AS product_id,
                p.sku AS product_sku,
                p.name AS product_name,
                p.category_id,
                c.name AS category_name,
                p.active AS product_active,
                p.stock AS current_stock,
                vw.week_start,
                vw.week_end,
                vw.units_sold,
                vw.orders_count
            FROM catalog.products p
            LEFT JOIN catalog.categories c ON p.category_id = c.id
            LEFT JOIN analytics.vw_recomendacion_producto_semana_historica_v1 vw 
                ON p.id = vw.product_id
            ORDER BY p.id ASC, vw.week_start ASC
            """;

        Map<Long, ProductHistoryRecord> productMap = new LinkedHashMap<>();

        jdbcTemplate.query(sql, rs -> {
            Long productId = rs.getLong("product_id");
            
            ProductHistoryRecord record = productMap.computeIfAbsent(productId, id -> {
                try {
                    return new ProductHistoryRecord(
                            id,
                            rs.getString("product_sku"),
                            rs.getString("product_name"),
                            rs.getLong("category_id") == 0 ? null : rs.getLong("category_id"),
                            rs.getString("category_name"),
                            rs.getBoolean("product_active"),
                            rs.getInt("current_stock"),
                            new ArrayList<>()
                    );
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Date weekStart = rs.getDate("week_start");
            if (weekStart != null) {
                record.history().add(new RawWeeklyData(
                        weekStart.toLocalDate(),
                        rs.getDate("week_end").toLocalDate(),
                        rs.getInt("units_sold"),
                        rs.getInt("orders_count")
                ));
            }
        });

        return new ArrayList<>(productMap.values());
    }
}
