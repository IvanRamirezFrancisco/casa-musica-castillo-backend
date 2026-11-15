package com.security.controller.admin;

import com.security.dto.admin.RecentOrderDTO;
import com.security.dto.admin.TopProductDTO;
import com.security.repository.OrderRepository;
import com.security.repository.ProductRepository;
import com.security.repository.UserRepository;
import com.security.enums.OrderStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Controller para el dashboard administrativo
 * CORS se maneja globalmente en SecurityConfig
 */
@RestController
@RequestMapping("/api/admin/dashboard")
@PreAuthorize("hasAuthority('DASHBOARD_VIEW')")
public class AdminDashboardController {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getDashboardStats() {
        Map<String, Object> stats = new HashMap<>();

        // Fechas para el mes actual
        LocalDateTime startOfMonth = LocalDateTime.now().with(TemporalAdjusters.firstDayOfMonth()).withHour(0)
                .withMinute(0).withSecond(0);
        LocalDateTime endOfMonth = LocalDateTime.now().with(TemporalAdjusters.lastDayOfMonth()).withHour(23)
                .withMinute(59).withSecond(59);

        // Ventas del mes
        BigDecimal monthSales = orderRepository.calculateTotalSales(startOfMonth, endOfMonth);
        stats.put("monthSales", monthSales != null ? monthSales : BigDecimal.ZERO);

        // Total de órdenes
        Long totalOrders = orderRepository.count();
        stats.put("totalOrders", totalOrders);

        // Total de productos activos
        Long totalProducts = productRepository.countByActiveTrue();
        stats.put("totalProducts", totalProducts);

        // Total de clientes activos (usuarios marcados como clientes)
        Long totalCustomers = userRepository.countByIsCustomerTrueAndEnabledTrue();
        stats.put("totalCustomers", totalCustomers);

        // Órdenes pendientes
        Long pendingOrders = orderRepository.countByStatus(OrderStatus.PENDING);
        stats.put("pendingOrders", pendingOrders);

        // Órdenes completadas
        Long completedOrders = orderRepository.countByStatus(OrderStatus.COMPLETED);
        stats.put("completedOrders", completedOrders);

        return ResponseEntity.ok(stats);
    }

    @GetMapping("/recent-orders")
    public ResponseEntity<List<RecentOrderDTO>> getRecentOrders(
            @RequestParam(defaultValue = "5") int limit) {
        List<RecentOrderDTO> recentOrders = orderRepository.findRecentOrderDtos(
                org.springframework.data.domain.PageRequest.of(0, limit));
        return ResponseEntity.ok(recentOrders);
    }

    @GetMapping("/top-products")
    public ResponseEntity<List<TopProductDTO>> getTopProducts(
            @RequestParam(defaultValue = "5") int limit) {
        List<TopProductDTO> topProducts = productRepository.findTopSellingProductDtos(
                org.springframework.data.domain.PageRequest.of(0, limit));
        return ResponseEntity.ok(topProducts);
    }

    @GetMapping("/low-stock")
    public ResponseEntity<?> getLowStockProducts(@RequestParam(defaultValue = "10") int threshold) {
        var lowStockProducts = productRepository.findLowStockProducts(threshold);
        return ResponseEntity.ok(lowStockProducts);
    }
}
