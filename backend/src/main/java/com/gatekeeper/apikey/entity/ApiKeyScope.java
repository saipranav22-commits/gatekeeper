package com.gatekeeper.apikey.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

import java.util.Objects;

@Entity
@Table(name = "api_key_scopes")
public class ApiKeyScope {

    @EmbeddedId
    private ApiKeyScopeId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("apiKeyId")
    @JoinColumn(name = "api_key_id", nullable = false)
    private ApiKey apiKey;

    public ApiKeyScope() {
    }

    public ApiKeyScope(ApiKey apiKey, String scope) {
        this.apiKey = apiKey;
        this.id = new ApiKeyScopeId(apiKey != null ? apiKey.getId() : null, scope);
    }

    public ApiKeyScopeId getId() {
        return id;
    }

    public void setId(ApiKeyScopeId id) {
        this.id = id;
    }

    public ApiKey getApiKey() {
        return apiKey;
    }

    public void setApiKey(ApiKey apiKey) {
        this.apiKey = apiKey;
        if (this.id != null && apiKey != null) {
            this.id.setApiKeyId(apiKey.getId());
        }
    }

    public String getScope() {
        return id != null ? id.getScope() : null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ApiKeyScope that = (ApiKeyScope) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : getClass().hashCode();
    }
}
