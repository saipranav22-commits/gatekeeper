package com.gatekeeper.consumer.service;

import com.gatekeeper.common.exception.DuplicateResourceException;
import com.gatekeeper.common.exception.InvalidStateTransitionException;
import com.gatekeeper.common.exception.ResourceNotFoundException;
import com.gatekeeper.consumer.dto.ConsumerDto;
import com.gatekeeper.consumer.dto.CreateConsumerRequest;
import com.gatekeeper.consumer.entity.ApiConsumer;
import com.gatekeeper.consumer.entity.ConsumerStatus;
import com.gatekeeper.consumer.repository.ApiConsumerRepository;
import com.gatekeeper.plan.entity.SubscriptionPlan;
import com.gatekeeper.plan.service.SubscriptionPlanService;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Profile("!foundation")
@Transactional
public class ConsumerService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final ApiConsumerRepository consumerRepository;
    private final SubscriptionPlanService planService;

    public ConsumerService(ApiConsumerRepository consumerRepository, SubscriptionPlanService planService) {
        this.consumerRepository = consumerRepository;
        this.planService = planService;
    }

    public ConsumerDto createConsumer(CreateConsumerRequest request) {
        Objects.requireNonNull(request, "CreateConsumerRequest must not be null");
        validateConsumerData(request.name(), request.email());

        if (this.consumerRepository.existsByEmail(request.email().trim().toLowerCase())) {
            throw new DuplicateResourceException("ApiConsumer", "email", request.email());
        }

        SubscriptionPlan plan = this.planService.getPlanEntityById(request.planId());
        if (!plan.isActive()) {
            throw new InvalidStateTransitionException("Cannot associate consumer with inactive subscription plan: " + plan.getName());
        }

        ApiConsumer consumer = new ApiConsumer(request.name().trim(), request.email().trim().toLowerCase(), plan);
        ApiConsumer saved = this.consumerRepository.save(consumer);
        return ConsumerDto.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public ConsumerDto getConsumerById(UUID id) {
        ApiConsumer consumer = getActiveConsumerEntity(id);
        return ConsumerDto.fromEntity(consumer);
    }

    @Transactional(readOnly = true)
    public ApiConsumer getActiveConsumerEntity(UUID id) {
        ApiConsumer consumer = this.consumerRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("ApiConsumer", "id", id));
        if (consumer.isDeleted()) {
            throw new ResourceNotFoundException("ApiConsumer", "id", id);
        }
        return consumer;
    }

    @Transactional(readOnly = true)
    public Page<ConsumerDto> listConsumers(Pageable pageable) {
        return this.consumerRepository.findAll(pageable)
            .map(ConsumerDto::fromEntity);
    }

    public ConsumerDto changeSubscriptionPlan(UUID consumerId, UUID newPlanId) {
        ApiConsumer consumer = getActiveConsumerEntity(consumerId);
        SubscriptionPlan newPlan = this.planService.getPlanEntityById(newPlanId);

        if (!newPlan.isActive()) {
            throw new InvalidStateTransitionException("Target plan '" + newPlan.getName() + "' is inactive");
        }

        consumer.setPlan(newPlan);
        return ConsumerDto.fromEntity(consumer);
    }

    public ConsumerDto suspendConsumer(UUID consumerId) {
        ApiConsumer consumer = getActiveConsumerEntity(consumerId);
        if (consumer.getStatus() == ConsumerStatus.SUSPENDED) {
            return ConsumerDto.fromEntity(consumer); // idempotent
        }
        if (consumer.getStatus() == ConsumerStatus.DEACTIVATED) {
            throw new InvalidStateTransitionException("Cannot suspend deactivated consumer");
        }
        consumer.setStatus(ConsumerStatus.SUSPENDED);
        return ConsumerDto.fromEntity(consumer);
    }

    public ConsumerDto reactivateConsumer(UUID consumerId) {
        ApiConsumer consumer = getActiveConsumerEntity(consumerId);
        if (consumer.getStatus() == ConsumerStatus.ACTIVE) {
            return ConsumerDto.fromEntity(consumer); // idempotent
        }
        if (consumer.isDeleted() || consumer.getStatus() == ConsumerStatus.DEACTIVATED) {
            throw new InvalidStateTransitionException("Cannot reactivate a deleted or deactivated consumer");
        }
        consumer.setStatus(ConsumerStatus.ACTIVE);
        return ConsumerDto.fromEntity(consumer);
    }

    public ConsumerDto softDeleteConsumer(UUID consumerId) {
        ApiConsumer consumer = getActiveConsumerEntity(consumerId);
        consumer.softDelete();
        return ConsumerDto.fromEntity(consumer);
    }

    private void validateConsumerData(String name, String email) {
        if (name == null || name.trim().isBlank()) {
            throw new IllegalArgumentException("Consumer name must not be blank");
        }
        if (name.trim().length() > 100) {
            throw new IllegalArgumentException("Consumer name cannot exceed 100 characters");
        }
        if (email == null || email.trim().isBlank()) {
            throw new IllegalArgumentException("Consumer email must not be blank");
        }
        if (email.trim().length() > 150) {
            throw new IllegalArgumentException("Consumer email cannot exceed 150 characters");
        }
        if (!EMAIL_PATTERN.matcher(email.trim()).matches()) {
            throw new IllegalArgumentException("Invalid email format: " + email);
        }
    }
}
