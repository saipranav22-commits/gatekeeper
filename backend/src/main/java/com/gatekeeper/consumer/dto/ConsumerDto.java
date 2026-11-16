package com.gatekeeper.consumer.dto;

import com.gatekeeper.consumer.entity.ApiConsumer;
import com.gatekeeper.consumer.entity.ConsumerStatus;
import com.gatekeeper.plan.dto.SubscriptionPlanDto;

import java.time.Instant;
import java.util.UUID;

public record ConsumerDto(
    UUID id,
    String name,
    String email,
    ConsumerStatus status,
    SubscriptionPlanDto plan,
    boolean isDeleted,
    Instant deletedAt,
    Instant createdAt,
    Instant updatedAt
) {
    public static ConsumerDto fromEntity(ApiConsumer consumer) {
        if (consumer == null) return null;
        return new ConsumerDto(
            consumer.getId(),
            consumer.getName(),
            consumer.getEmail(),
            consumer.getStatus(),
            consumer.getPlan() != null ? SubscriptionPlanDto.fromEntity(consumer.getPlan()) : null,
            consumer.isDeleted(),
            consumer.getDeletedAt(),
            consumer.getCreatedAt(),
            consumer.getUpdatedAt()
        );
    }
}
