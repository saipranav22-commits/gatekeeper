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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConsumerServiceTest {

    @Mock
    private ApiConsumerRepository consumerRepository;

    @Mock
    private SubscriptionPlanService planService;

    @InjectMocks
    private ConsumerService consumerService;

    private SubscriptionPlan activePlan;
    private SubscriptionPlan inactivePlan;

    @BeforeEach
    void setUp() {
        this.activePlan = new SubscriptionPlan("PRO", "Pro tier", 600, 600, 60);
        this.activePlan.setId(UUID.randomUUID());

        this.inactivePlan = new SubscriptionPlan("DEPRECATED", "Old tier", 100, 100, 60);
        this.inactivePlan.setId(UUID.randomUUID());
        this.inactivePlan.setActive(false);
    }

    @Test
    @DisplayName("Create consumer succeeds when valid request and active plan")
    void testCreateConsumerSuccess() {
        UUID planId = this.activePlan.getId();
        CreateConsumerRequest request = new CreateConsumerRequest("Acme Corp", "dev@acme.com", planId);

        when(this.consumerRepository.existsByEmail("dev@acme.com")).thenReturn(false);
        when(this.planService.getPlanEntityById(planId)).thenReturn(this.activePlan);
        when(this.consumerRepository.save(any(ApiConsumer.class))).thenAnswer(invocation -> {
            ApiConsumer c = invocation.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        ConsumerDto result = this.consumerService.createConsumer(request);

        assertThat(result).isNotNull();
        assertThat(result.name()).isEqualTo("Acme Corp");
        assertThat(result.email()).isEqualTo("dev@acme.com");
        assertThat(result.status()).isEqualTo(ConsumerStatus.ACTIVE);
        verify(this.consumerRepository).save(any(ApiConsumer.class));
    }

    @Test
    @DisplayName("Create consumer throws when email already exists")
    void testCreateConsumerDuplicateEmail() {
        CreateConsumerRequest request = new CreateConsumerRequest("Acme Corp", "dev@acme.com", UUID.randomUUID());
        when(this.consumerRepository.existsByEmail("dev@acme.com")).thenReturn(true);

        assertThatThrownBy(() -> this.consumerService.createConsumer(request))
            .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    @DisplayName("Create consumer throws when selected plan is inactive")
    void testCreateConsumerInactivePlan() {
        UUID planId = this.inactivePlan.getId();
        CreateConsumerRequest request = new CreateConsumerRequest("Acme Corp", "dev@acme.com", planId);

        when(this.consumerRepository.existsByEmail("dev@acme.com")).thenReturn(false);
        when(this.planService.getPlanEntityById(planId)).thenReturn(this.inactivePlan);

        assertThatThrownBy(() -> this.consumerService.createConsumer(request))
            .isInstanceOf(InvalidStateTransitionException.class)
            .hasMessageContaining("inactive subscription plan");
    }

    @Test
    @DisplayName("Suspend and reactivate consumer transitions state correctly")
    void testSuspendAndReactivateConsumer() {
        UUID consumerId = UUID.randomUUID();
        ApiConsumer consumer = new ApiConsumer("Beta Labs", "beta@labs.com", this.activePlan);
        consumer.setId(consumerId);

        when(this.consumerRepository.findById(consumerId)).thenReturn(Optional.of(consumer));

        ConsumerDto suspended = this.consumerService.suspendConsumer(consumerId);
        assertThat(suspended.status()).isEqualTo(ConsumerStatus.SUSPENDED);

        ConsumerDto reactivated = this.consumerService.reactivateConsumer(consumerId);
        assertThat(reactivated.status()).isEqualTo(ConsumerStatus.ACTIVE);
    }

    @Test
    @DisplayName("Soft-delete consumer marks isDeleted true and sets status DEACTIVATED")
    void testSoftDeleteConsumer() {
        UUID consumerId = UUID.randomUUID();
        ApiConsumer consumer = new ApiConsumer("Gamma Corp", "gamma@corp.com", this.activePlan);
        consumer.setId(consumerId);

        when(this.consumerRepository.findById(consumerId)).thenReturn(Optional.of(consumer));

        ConsumerDto deleted = this.consumerService.softDeleteConsumer(consumerId);
        assertThat(deleted.isDeleted()).isTrue();
        assertThat(deleted.deletedAt()).isNotNull();
        assertThat(deleted.status()).isEqualTo(ConsumerStatus.DEACTIVATED);
    }

    @Test
    @DisplayName("Operations on deleted consumer throw ResourceNotFoundException")
    void testDeletedConsumerThrowsNotFound() {
        UUID consumerId = UUID.randomUUID();
        ApiConsumer consumer = new ApiConsumer("Delta Inc", "delta@inc.com", this.activePlan);
        consumer.setId(consumerId);
        consumer.softDelete();

        when(this.consumerRepository.findById(consumerId)).thenReturn(Optional.of(consumer));

        assertThatThrownBy(() -> this.consumerService.getConsumerById(consumerId))
            .isInstanceOf(ResourceNotFoundException.class);

        assertThatThrownBy(() -> this.consumerService.suspendConsumer(consumerId))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
