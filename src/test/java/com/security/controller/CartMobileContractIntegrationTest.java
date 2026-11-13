package com.security.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.entity.Product;
import com.security.entity.User;
import com.security.repository.OrderRepository;
import com.security.repository.ProductRepository;
import com.security.repository.ShoppingCartRepository;
import com.security.repository.UserRepository;
import com.security.security.UserPrincipal;
import com.security.support.LocalPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
class CartMobileContractIntegrationTest extends LocalPostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired ProductRepository products;
    @Autowired ShoppingCartRepository carts;
    @Autowired OrderRepository orders;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean com.security.config.MercadoPagoConfiguration payments;

    @Test
    void mobileCartPersistsAndEnforcesOwnerStockAndQuotation() throws Exception {
        User owner = createUser("cart_owner");
        User other = createUser("cart_other");
        var ownerRequest = user(UserPrincipal.create(owner));
        var otherRequest = user(UserPrincipal.create(other));

        mvc.perform(get("/api/cart")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/cart/summary").with(ownerRequest))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.cartId").doesNotExist()).andExpect(jsonPath("$.canCheckout").value(false));
        mvc.perform(get("/api/cart/validate").with(ownerRequest))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false));
        assertTrue(carts.findByUserIdOrderByCreatedAtDesc(owner.getId()).isEmpty());

        Product product = new Product();
        product.setName("Synthetic cart product");
        product.setSku("SYNTHETIC-CART-TEST");
        product.setStock(4);
        product.setPrice(new BigDecimal("7500.00"));
        product = products.saveAndFlush(product);
        var created = mvc.perform(post("/api/cart/items").with(ownerRequest).contentType("application/json")
                .content(json.writeValueAsString(Map.of("productId", product.getId(), "quantity", 1))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.items[0].productId").value(product.getId()))
                .andExpect(jsonPath("$.subtotal").value(7500))
                .andExpect(jsonPath("$.tax").value(1200))
                .andExpect(jsonPath("$.total").value(8700))
                .andReturn();
        long itemId = json.readTree(created.getResponse().getContentAsString()).at("/items/0/itemId").asLong();
        long cartId = json.readTree(created.getResponse().getContentAsString()).at("/cartId").asLong();
        jdbc.update("UPDATE sales.shopping_carts SET subtotal = 0, tax = 0, total = 0 WHERE id = ?", cartId);
        mvc.perform(get("/api/cart").with(ownerRequest)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].subtotal").value(7500))
                .andExpect(jsonPath("$.total").value(8700));
        mvc.perform(get("/api/cart/summary").with(ownerRequest)).andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value(7500)).andExpect(jsonPath("$.total").value(8700));

        mvc.perform(get("/api/cart").with(otherRequest)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));
        var quantity = json.writeValueAsString(Map.of("itemId", itemId, "quantity", 2));
        mvc.perform(put("/api/cart/items/{id}", itemId).with(otherRequest).contentType("application/json").content(quantity))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/cart/items/{id}", itemId).with(otherRequest)).andExpect(status().isNotFound());
        mvc.perform(put("/api/cart/items/{id}", itemId).with(ownerRequest).contentType("application/json").content(quantity))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.total").value(17400));
        mvc.perform(get("/api/cart/summary").with(ownerRequest))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(2));
        mvc.perform(get("/api/cart/validate").with(ownerRequest))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canCheckout").value(true));

        long ordersBefore = orders.count();
        mvc.perform(post("/api/checkout/price-preview").with(ownerRequest).contentType("application/json")
                .content("{\"deliveryOption\":\"PICKUP_STORE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deliveryMethod").value("PICKUP_STORE"))
                .andExpect(jsonPath("$.shipping").value(0));
        assertEquals(ordersBefore, orders.count());

        mvc.perform(delete("/api/cart/items/{id}", itemId).with(ownerRequest))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(delete("/api/cart").with(ownerRequest)).andExpect(status().isOk());
    }

    @Test
    void rejectsQuantityAboveStock() throws Exception {
        User owner = createUser("cart_stock");
        Product product = new Product();
        product.setName("Synthetic stock test");
        product.setSku("SYNTHETIC-STOCK-TEST");
        product.setStock(1);
        product.setPrice(BigDecimal.TEN);
        product = products.saveAndFlush(product);
        mvc.perform(post("/api/cart/items").with(user(UserPrincipal.create(owner))).contentType("application/json")
                .content(json.writeValueAsString(Map.of("productId", product.getId(), "quantity", 2))))
                .andExpect(status().isConflict());
    }

    private User createUser(String name) {
        User account = new User();
        account.setUsername(name);
        account.setEmail(name + "@example.invalid");
        account.setFirstName("Synthetic");
        account.setLastName("Cart");
        account.setPassword("synthetic-unused");
        account.setEnabled(true);
        return users.saveAndFlush(account);
    }
}
