package com.gatekeeper.orders.controller;

import com.gatekeeper.orders.dto.CreateOrderRequest;
import com.gatekeeper.orders.model.Order;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@RestController
public class OrdersController {

    private static final Logger log = LoggerFactory.getLogger(OrdersController.class);

    private final Map<String, Order> ordersStore = new ConcurrentHashMap<>();
    private final AtomicLong orderSeq = new AtomicLong(1000);

    public OrdersController() {
        seedFixtures();
    }

    private void seedFixtures() {
        Order ord1 = new Order(
            "ord-101",
            "0db6ef81-db02-429c-9574-b8496538ec06",
            "SKU-LAPTOP-01",
            1,
            new BigDecimal("1299.99"),
            "CONFIRMED",
            Instant.parse("2026-11-17T08:00:00Z")
        );
        Order ord2 = new Order(
            "ord-102",
            "d31833d0-8515-4b05-aef1-84afb3804af1",
            "SKU-MOUSE-02",
            2,
            new BigDecimal("49.98"),
            "SHIPPED",
            Instant.parse("2026-11-17T09:30:00Z")
        );
        Order ord3 = new Order(
            "ord-103",
            "0db6ef81-db02-429c-9574-b8496538ec06",
            "SKU-KEYBOARD-03",
            1,
            new BigDecimal("89.99"),
            "PENDING",
            Instant.parse("2026-11-17T10:15:00Z")
        );
        ordersStore.put(ord1.id(), ord1);
        ordersStore.put(ord2.id(), ord2);
        ordersStore.put(ord3.id(), ord3);
    }

    @GetMapping("/orders")
    public ResponseEntity<Collection<Order>> getAllOrders(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "consumerId", required = false) String consumerId,
            HttpServletRequest request) {

        String correlationId = request.getHeader("X-Request-Id");
        log.info("GET /orders received (correlationId={}, status={}, consumerId={})",
            correlationId, status, consumerId);

        if (status != null && !status.isBlank()) {
            return ResponseEntity.ok(ordersStore.values().stream()
                .filter(o -> o.status().equalsIgnoreCase(status.trim()))
                .toList());
        }

        if (consumerId != null && !consumerId.isBlank()) {
            return ResponseEntity.ok(ordersStore.values().stream()
                .filter(o -> consumerId.trim().equalsIgnoreCase(o.consumerId()))
                .toList());
        }

        return ResponseEntity.ok(ordersStore.values());
    }

    @GetMapping("/orders/{id}")
    public ResponseEntity<Order> getOrderById(
            @PathVariable("id") String id,
            HttpServletRequest request) {

        String correlationId = request.getHeader("X-Request-Id");
        log.info("GET /orders/{} received (correlationId={})", id, correlationId);

        Order order = ordersStore.get(id);
        if (order == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(order);
    }

    @PostMapping("/orders")
    public ResponseEntity<Order> createOrder(
            @Valid @RequestBody CreateOrderRequest req,
            HttpServletRequest request) {

        String correlationId = request.getHeader("X-Request-Id");
        log.info("POST /orders received (correlationId={}, sku={}, qty={})",
            correlationId, req.itemSku(), req.quantity());

        String newId = "ord-" + orderSeq.incrementAndGet();
        Order created = new Order(
            newId,
            req.consumerId() != null ? req.consumerId() : "anonymous-consumer",
            req.itemSku(),
            req.quantity(),
            req.totalPrice(),
            "PENDING",
            Instant.now()
        );
        ordersStore.put(newId, created);

        return ResponseEntity.created(URI.create("/orders/" + newId)).body(created);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "orders-service",
            "timestamp", Instant.now().toString()
        ));
    }
}
