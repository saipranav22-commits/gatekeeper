package com.gatekeeper.analytics.repository;

import com.gatekeeper.analytics.entity.DeadLetterUsageEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface DeadLetterRepository extends JpaRepository<DeadLetterUsageEvent, UUID> {
}
