package com.gatekeeper.chaos.model;

/**
 * Gatekeeper-owned local test services and dependencies permitted for controlled chaos experiments.
 * Strict target allowlisting prohibits injecting faults into arbitrary external hosts or production resources.
 */
public enum ChaosTarget {
    ORDERS_SERVICE("orders-service-v1", "http://localhost:8081"),
    INVENTORY_SERVICE("inventory-service-v1", "http://localhost:8082"),
    REDIS_DEPENDENCY("redis-token-bucket", "localhost:6380"),
    POLICY_ENGINE("policy-engine", "internal");

    private final String serviceKey;
    private final String localTargetUri;

    ChaosTarget(String serviceKey, String localTargetUri) {
        this.serviceKey = serviceKey;
        this.localTargetUri = localTargetUri;
    }

    public String getServiceKey() {
        return serviceKey;
    }

    public String getLocalTargetUri() {
        return localTargetUri;
    }

    public static ChaosTarget fromKey(String key) {
        if (key == null) return null;
        for (ChaosTarget t : values()) {
            if (t.serviceKey.equalsIgnoreCase(key.trim())) {
                return t;
            }
        }
        return null;
    }
}
