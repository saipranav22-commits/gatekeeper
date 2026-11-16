package com.gatekeeper.plan.dto;

import com.gatekeeper.plan.entity.SubscriptionPlan;

import java.time.Instant;
import java.util.UUID;

public record SubscriptionPlanDto(
    UUID id,
    String name,
    String description,
    long bucketCapacity,
    long refillTokens,
    int refillPeriodSeconds,
    boolean isActive,
    Instant createdAt,
    Instant updatedAt
) {
    public static SubscriptionPlanDto fromEntity(SubscriptionPlan plan) {
        if (plan == null) return null;
        return new SubscriptionPlanDto(
            plan.getId(),
            plan.getName(),
            plan.getDescription(),
            plan.getBucketCapacity(),
            plan.getRefillTokens(),
            plan.getRefillPeriodSeconds(),
            plan.isActive(),
            plan.getCreatedAt(),
            plan.getUpdatedAt()
        );
    }
}
