package com.gatekeeper.analytics.repository;

import com.gatekeeper.analytics.entity.UsageEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface UsageEventRepository extends JpaRepository<UsageEvent, UUID> {

    List<UsageEvent> findByConsumerIdAndTimestampBetween(UUID consumerId, Instant start, Instant end);

    Page<UsageEvent> findByConsumerIdAndTimestampBetweenOrderByTimestampDesc(
        UUID consumerId, Instant start, Instant end, Pageable pageable);

    List<UsageEvent> findByApiKeyIdAndTimestampBetween(UUID apiKeyId, Instant start, Instant end);

    long countByRateLimitedTrueAndTimestampBetween(Instant start, Instant end);

    List<UsageEvent> findByRateLimitedTrueAndTimestampBetween(Instant start, Instant end);

    List<UsageEvent> findByTimestampBetween(Instant start, Instant end);
}
