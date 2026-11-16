package com.gatekeeper.plan.service;

import com.gatekeeper.common.exception.ResourceNotFoundException;
import com.gatekeeper.plan.dto.SubscriptionPlanDto;
import com.gatekeeper.plan.entity.SubscriptionPlan;
import com.gatekeeper.plan.repository.SubscriptionPlanRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Profile("!foundation")
@Transactional(readOnly = true)
public class SubscriptionPlanService {

    private final SubscriptionPlanRepository planRepository;

    public SubscriptionPlanService(SubscriptionPlanRepository planRepository) {
        this.planRepository = planRepository;
    }

    public List<SubscriptionPlanDto> listAvailablePlans() {
        return this.planRepository.findByIsActiveTrue()
            .stream()
            .map(SubscriptionPlanDto::fromEntity)
            .toList();
    }

    public SubscriptionPlan getPlanEntityById(UUID id) {
        SubscriptionPlan plan = this.planRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("SubscriptionPlan", "id", id));
        validatePlanParameters(plan);
        return plan;
    }

    public SubscriptionPlanDto getPlanById(UUID id) {
        return SubscriptionPlanDto.fromEntity(getPlanEntityById(id));
    }

    public SubscriptionPlanDto getPlanByName(String name) {
        SubscriptionPlan plan = this.planRepository.findByName(name)
            .orElseThrow(() -> new ResourceNotFoundException("SubscriptionPlan", "name", name));
        validatePlanParameters(plan);
        return SubscriptionPlanDto.fromEntity(plan);
    }

    public void validatePlanParameters(SubscriptionPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("Subscription plan cannot be null");
        }
        if (plan.getBucketCapacity() <= 0) {
            throw new IllegalArgumentException("Bucket capacity must be strictly positive");
        }
        if (plan.getRefillTokens() <= 0) {
            throw new IllegalArgumentException("Refill tokens must be strictly positive");
        }
        if (plan.getRefillPeriodSeconds() <= 0) {
            throw new IllegalArgumentException("Refill period in seconds must be strictly positive");
        }
    }
}
