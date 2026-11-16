package com.gatekeeper.apikey.repository;

import com.gatekeeper.apikey.entity.ApiKeyScope;
import com.gatekeeper.apikey.entity.ApiKeyScopeId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ApiKeyScopeRepository extends JpaRepository<ApiKeyScope, ApiKeyScopeId> {

    List<ApiKeyScope> findByIdApiKeyId(UUID apiKeyId);
}
