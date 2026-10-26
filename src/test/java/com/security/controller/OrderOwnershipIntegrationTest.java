package com.security.controller;

import com.security.entity.Order;
import com.security.entity.User;
import com.security.enums.OrderStatus;
import com.security.enums.PaymentStatus;
import com.security.repository.OrderRepository;
import com.security.repository.UserRepository;
import com.security.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collections;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
public class OrderOwnershipIntegrationTest extends com.security.support.LocalPostgresIntegrationTest {

    @MockBean
    private com.security.config.MercadoPagoConfiguration mercadoPagoConfiguration;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrderRepository orderRepository;

    private User userA;
    private User userB;
    private Order orderA;
    private Order orderB;

    @BeforeEach
    void setUp() {
        // Crear usuarios reales en base de datos de test
        userA = new User();
        userA.setEmail("user_a@test.com");
        userA.setUsername("user_a");
        userA.setPassword("password");
        userA.setFirstName("User");
        userA.setLastName("A");
        userA = userRepository.save(userA);

        userB = new User();
        userB.setEmail("user_b@test.com");
        userB.setUsername("user_b");
        userB.setPassword("password");
        userB.setFirstName("User");
        userB.setLastName("B");
        userB = userRepository.save(userB);

        // Crear órdenes reales pertenecientes a esos usuarios
        orderA = new Order();
        orderA.setUser(userA);
        orderA.setOrderNumber("ORD-A");
        orderA.setStatus(OrderStatus.PENDING);
        orderA.setPaymentStatus(PaymentStatus.PENDING);
        orderA.setTotal(new BigDecimal("100.00"));
        orderA = orderRepository.save(orderA);

        orderB = new Order();
        orderB.setUser(userB);
        orderB.setOrderNumber("ORD-B");
        orderB.setStatus(OrderStatus.PENDING);
        orderB.setPaymentStatus(PaymentStatus.PENDING);
        orderB.setTotal(new BigDecimal("200.00"));
        orderB = orderRepository.save(orderB);
    }

    private UserPrincipal getPrincipal(User user) {
        return UserPrincipal.create(user);
    }

    @Test
    void userATryingToReadOrderB_ReturnsForbiddenOrNotFound() throws Exception {
        // En nuestro diseño, OrderController lanza 404 EntityNotFoundException si la orden no es del usuario (findByIdAndUserId)
        // o 403 (Forbidden) si se controla en nivel de servicio.
        mockMvc.perform(get("/api/orders/my/{id}", orderB.getId())
                .with(user(getPrincipal(userA))))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assert (status == 403 || status == 404);
                });
    }

    @Test
    void userATryingToReadOrderA_ReturnsOk() throws Exception {
        mockMvc.perform(get("/api/orders/my/{id}", orderA.getId())
                .with(user(getPrincipal(userA))))
                .andExpect(status().isOk());
    }

    @Test
    void userATryingToReadOrderB_Timeline_ReturnsForbiddenOrNotFound() throws Exception {
        mockMvc.perform(get("/api/orders/my/{id}/timeline", orderB.getId())
                .with(user(getPrincipal(userA))))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assert (status == 403 || status == 404);
                });
    }

    @Test
    void userATryingToReadOrderB_Payment_ReturnsForbiddenOrNotFound() throws Exception {
        mockMvc.perform(get("/api/orders/my/{id}/payment", orderB.getId())
                .with(user(getPrincipal(userA))))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assert (status == 403 || status == 404);
                });
    }

    @Test
    void userATryingToReadOrderB_Pickup_ReturnsForbiddenOrNotFound() throws Exception {
        mockMvc.perform(get("/api/orders/my/{id}/pickup-code", orderB.getId())
                .with(user(getPrincipal(userA))))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assert (status == 403 || status == 404);
                });
    }

    @Test
    @WithAnonymousUser
    void anonymousTryingToReadOrderA_ReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/orders/my/{id}", orderA.getId()))
                .andExpect(status().isUnauthorized()); // Security config debe devolver 401
    }
}
