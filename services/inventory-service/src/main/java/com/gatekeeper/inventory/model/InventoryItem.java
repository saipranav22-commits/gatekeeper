package com.gatekeeper.inventory.model;

import java.math.BigDecimal;

public record InventoryItem(
    String sku,
    String name,
    int quantity,
    BigDecimal unitPrice,
    String warehouse,
    boolean inStock
) {}
