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

    @org.springframework.data.jpa.repository.Query("SELECT k FROM ApiKey k JOIN FETCH k.consumer c JOIN FETCH c.plan p LEFT JOIN FETCH k.scopes WHERE k.keyPrefix = :keyPrefix")
    Optional<ApiKey> findByKeyPrefixWithDetails(@org.springframework.data.repository.query.Param("keyPrefix") String keyPrefix);

    Optional<ApiKey> findByKeyPrefixAndStatusAndIsDeletedFalse(String keyPrefix, ApiKeyStatus status);

    List<ApiKey> findByConsumerIdAndIsDeletedFalse(UUID consumerId);

    List<ApiKey> findByConsumerId(UUID consumerId);

    boolean existsByKeyPrefix(String keyPrefix);
}
