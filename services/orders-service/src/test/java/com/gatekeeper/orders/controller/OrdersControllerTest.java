package com.gatekeeper.orders.controller;

import com.gatekeeper.orders.dto.CreateOrderRequest;
import com.gatekeeper.orders.model.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OrdersController Unit Tests")
class OrdersControllerTest {

    private OrdersController controller;

    @BeforeEach
    void setUp() {
        controller = new OrdersController();
    }

    @Test
    @DisplayName("GET /orders returns seeded fixtures")
    void shouldReturnAllOrders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        ResponseEntity<Collection<Order>> response = controller.getAllOrders(null, null, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).hasSize(3);
    }

    @Test
    @DisplayName("GET /orders/{id} returns specific order")
    void shouldReturnOrderById() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        ResponseEntity<Order> response = controller.getOrderById("ord-101", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().itemSku()).isEqualTo("SKU-LAPTOP-01");
    }

    @Test
    @DisplayName("GET /orders/{id} returns 404 for unknown order")
    void shouldReturn404ForUnknownOrder() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        ResponseEntity<Order> response = controller.getOrderById("unknown-id", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("POST /orders creates a new order")
    void shouldCreateOrder() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        CreateOrderRequest req = new CreateOrderRequest(
            "consumer-123",
            "SKU-MONITOR-05",
            2,
            new BigDecimal("599.98")
        );

        ResponseEntity<Order> response = controller.createOrder(req, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().itemSku()).isEqualTo("SKU-MONITOR-05");
        assertThat(response.getBody().quantity()).isEqualTo(2);
        assertThat(response.getBody().status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("GET /health returns UP status")
    void shouldReturnHealthStatus() {
        ResponseEntity<Map<String, Object>> response = controller.health();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
        assertThat(response.getBody()).containsEntry("service", "orders-service");
    }
}
