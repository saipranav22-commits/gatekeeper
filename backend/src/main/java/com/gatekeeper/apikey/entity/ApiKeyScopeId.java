package com.gatekeeper.apikey.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class ApiKeyScopeId implements Serializable {

    @Column(name = "api_key_id", nullable = false)
    private UUID apiKeyId;

    @Column(name = "scope", nullable = false, length = 50)
    private String scope;

    public ApiKeyScopeId() {
    }

    public ApiKeyScopeId(UUID apiKeyId, String scope) {
        this.apiKeyId = apiKeyId;
        this.scope = scope;
    }

    public UUID getApiKeyId() {
        return apiKeyId;
    }

    public void setApiKeyId(UUID apiKeyId) {
        this.apiKeyId = apiKeyId;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ApiKeyScopeId that = (ApiKeyScopeId) o;
        return Objects.equals(apiKeyId, that.apiKeyId) && Objects.equals(scope, that.scope);
    }

    @Override
    public int hashCode() {
        return Objects.hash(apiKeyId, scope);
    }
}
