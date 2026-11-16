package com.gatekeeper.apikey.repository;

import com.gatekeeper.apikey.entity.ApiKey;
import com.gatekeeper.apikey.entity.ApiKeyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByKeyPrefix(String keyPrefix);

    Optional<ApiKey> findByKeyPrefixAndStatusAndIsDeletedFalse(String keyPrefix, ApiKeyStatus status);

    List<ApiKey> findByConsumerIdAndIsDeletedFalse(UUID consumerId);

    List<ApiKey> findByConsumerId(UUID consumerId);

    boolean existsByKeyPrefix(String keyPrefix);
}
