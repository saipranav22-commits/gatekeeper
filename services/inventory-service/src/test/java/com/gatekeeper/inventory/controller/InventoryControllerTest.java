package com.gatekeeper.inventory.controller;

import com.gatekeeper.inventory.model.InventoryItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Collection;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InventoryController Unit Tests")
class InventoryControllerTest {

    private InventoryController controller;

    @BeforeEach
    void setUp() {
        controller = new InventoryController();
    }

    @Test
    @DisplayName("GET /inventory returns seeded items")
    void shouldReturnAllInventoryItems() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        ResponseEntity<Collection<InventoryItem>> response = controller.getAllInventory(null, null, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).hasSize(3);
    }

    @Test
    @DisplayName("GET /inventory/{sku} returns matching item")
    void shouldReturnItemBySku() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        ResponseEntity<InventoryItem> response = controller.getItemBySku("SKU-LAPTOP-01", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().sku()).isEqualTo("SKU-LAPTOP-01");
        assertThat(response.getBody().quantity()).isEqualTo(42);
    }

    @Test
    @DisplayName("GET /inventory/{sku} returns 404 for unknown sku")
    void shouldReturn404ForUnknownSku() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        ResponseEntity<InventoryItem> response = controller.getItemBySku("UNKNOWN-SKU", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /inventory?inStock=true filters items")
    void shouldFilterInStockItems() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        ResponseEntity<Collection<InventoryItem>> response = controller.getAllInventory(true, null, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).allMatch(InventoryItem::inStock);
    }

    @Test
    @DisplayName("GET /health returns UP status")
    void shouldReturnHealthStatus() {
        ResponseEntity<Map<String, Object>> response = controller.health();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
        assertThat(response.getBody()).containsEntry("service", "inventory-service");
    }
}
