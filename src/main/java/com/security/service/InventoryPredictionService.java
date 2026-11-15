package com.security.service;

import com.security.dto.admin.InventoryPredictionDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class InventoryPredictionService {

    private static final Logger log = LoggerFactory.getLogger(InventoryPredictionService.class);

    private static final double K_MIN = 0.001;
    private static final double DAYS_CAP = 365.0;

    private final JdbcTemplate jdbc;

    public InventoryPredictionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<InventoryPredictionDTO> getPredictions() {

        String sql = """
                SELECT
                    c.section_name,
                    c.section_key,
                    c.stock_reference,
                    c.reference_date,
                    c.critical_level,
                    c.alert_threshold_days,
                    COALESCE(SUM(p.stock), 0) AS i_current
                FROM ops.inventory_prediction_config c
                LEFT JOIN catalog.products p
                    ON p.category_id = c.category_id
                    AND p.active = true
                GROUP BY
                    c.id,
                    c.section_name,
                    c.section_key,
                    c.stock_reference,
                    c.reference_date,
                    c.critical_level,
                    c.alert_threshold_days
                ORDER BY c.id
                """;

        List<Map<String, Object>> rows = jdbc.queryForList(sql);
        List<InventoryPredictionDTO> result = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            try {
                String sectionName = (String) row.get("section_name");
                String sectionKey = (String) row.get("section_key");
                double i0 = ((Number) row.get("stock_reference")).doubleValue();
                double iCrit = ((Number) row.get("critical_level")).doubleValue();
                int alertDays = ((Number) row.get("alert_threshold_days")).intValue();
                double iCurrent = ((Number) row.get("i_current")).doubleValue();

                // Días transcurridos desde reference_date hasta hoy
                Object refDateObj = row.get("reference_date");
                long t = 30; // default
                if (refDateObj instanceof java.sql.Date sd) {
                    t = java.time.temporal.ChronoUnit.DAYS
                            .between(sd.toLocalDate(), java.time.LocalDate.now());
                }
                if (t <= 0)
                    t = 1;

                // Sin stock → crítico inmediato
                if (iCurrent <= 0) {
                    log.debug("[Inventory] Sección='{}' I0={} Iactual={} → SIN STOCK, CRÍTICO",
                            sectionName, i0, iCurrent);
                    result.add(new InventoryPredictionDTO(
                            sectionName, sectionKey, i0, iCurrent,
                            iCrit, K_MIN, 0.0, 0, "CRITICAL"));
                    continue;
                }

                // Si el stock actual supera el stock_reference, el stock creció
                // (reabastecimiento). Usamos iCurrent como la nueva referencia i0.
                if (iCurrent > i0) {
                    i0 = iCurrent;
                }

                // Calcular k
                double k;
                if (iCurrent >= i0) {
                    // iCurrent == i0 exactamente: recién reabastecido, k mínima
                    k = K_MIN;
                } else {
                    k = -Math.log(iCurrent / i0) / t;
                    if (!Double.isFinite(k) || k <= 0)
                        k = K_MIN;
                }

                // Calcular días al nivel crítico
                double daysToAlert;
                if (i0 <= iCrit) {
                    daysToAlert = 0.0;
                } else if (k <= K_MIN) {
                    daysToAlert = DAYS_CAP;
                } else {
                    daysToAlert = -Math.log(iCrit / i0) / k;
                    if (!Double.isFinite(daysToAlert) || daysToAlert < 0)
                        daysToAlert = 0.0;
                    daysToAlert = Math.min(daysToAlert, DAYS_CAP);
                }

                // Status
                String status;
                if (daysToAlert < alertDays)
                    status = "CRITICAL";
                else if (daysToAlert < 60)
                    status = "WARNING";
                else
                    status = "STABLE";

                log.debug("[Inventory] Sección='{}' I0={} Iactual={} k={} t_crit={}d → {}",
                        sectionName, i0, iCurrent,
                        String.format("%.6f", k),
                        String.format("%.1f", daysToAlert),
                        status);

                result.add(new InventoryPredictionDTO(
                        sectionName, sectionKey, i0, iCurrent,
                        iCrit, k, daysToAlert, (int) iCurrent, status));

            } catch (Exception ex) {
                log.error("[Inventory] Error en sección '{}': {}",
                        row.get("section_name"), ex.getMessage(), ex);
            }
        }

        return result;
    }
}