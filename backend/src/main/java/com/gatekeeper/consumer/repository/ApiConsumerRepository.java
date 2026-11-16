package com.gatekeeper.consumer.repository;

import com.gatekeeper.consumer.entity.ApiConsumer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiConsumerRepository extends JpaRepository<ApiConsumer, UUID> {

    Optional<ApiConsumer> findByEmail(String email);

    Optional<ApiConsumer> findByIdAndIsDeletedFalse(UUID id);

    List<ApiConsumer> findByPlanIdAndIsDeletedFalse(UUID planId);

    List<ApiConsumer> findByIsDeletedFalse();

    boolean existsByEmail(String email);
}
