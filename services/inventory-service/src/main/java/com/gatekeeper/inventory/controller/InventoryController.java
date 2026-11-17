package com.gatekeeper.inventory.controller;

import com.gatekeeper.inventory.model.InventoryItem;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
public class InventoryController {

    private static final Logger log = LoggerFactory.getLogger(InventoryController.class);

    private final Map<String, InventoryItem> inventoryStore = new ConcurrentHashMap<>();

    public InventoryController() {
        seedFixtures();
    }

    private void seedFixtures() {
        InventoryItem item1 = new InventoryItem(
            "SKU-LAPTOP-01",
            "Enterprise Developer Laptop 16\"",
            42,
            new BigDecimal("1299.99"),
            "US-EAST-1",
            true
        );
        InventoryItem item2 = new InventoryItem(
            "SKU-MOUSE-02",
            "Ergonomic Wireless Mouse",
            150,
            new BigDecimal("24.99"),
            "US-EAST-1",
            true
        );
        InventoryItem item3 = new InventoryItem(
            "SKU-KEYBOARD-03",
            "Mechanical Gaming Keyboard RGB",
            0,
            new BigDecimal("89.99"),
            "US-WEST-2",
            false
        );
        inventoryStore.put(item1.sku(), item1);
        inventoryStore.put(item2.sku(), item2);
        inventoryStore.put(item3.sku(), item3);
    }

    @GetMapping("/inventory")
    public ResponseEntity<Collection<InventoryItem>> getAllInventory(
            @RequestParam(name = "inStock", required = false) Boolean inStock,
            @RequestParam(name = "warehouse", required = false) String warehouse,
            HttpServletRequest request) {

        String correlationId = request.getHeader("X-Request-Id");
        log.info("GET /inventory received (correlationId={}, inStock={}, warehouse={})",
            correlationId, inStock, warehouse);

        if (inStock != null) {
            return ResponseEntity.ok(inventoryStore.values().stream()
                .filter(i -> i.inStock() == inStock)
                .toList());
        }

        if (warehouse != null && !warehouse.isBlank()) {
            return ResponseEntity.ok(inventoryStore.values().stream()
                .filter(i -> warehouse.trim().equalsIgnoreCase(i.warehouse()))
                .toList());
        }

        return ResponseEntity.ok(inventoryStore.values());
    }

    @GetMapping("/inventory/{sku}")
    public ResponseEntity<InventoryItem> getItemBySku(
            @PathVariable("sku") String sku,
            HttpServletRequest request) {

        String correlationId = request.getHeader("X-Request-Id");
        log.info("GET /inventory/{} received (correlationId={})", sku, correlationId);

        InventoryItem item = inventoryStore.get(sku);
        if (item == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(item);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "inventory-service",
            "timestamp", Instant.now().toString()
        ));
    }
}
