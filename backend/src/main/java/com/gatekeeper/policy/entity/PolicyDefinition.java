package com.gatekeeper.policy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "policy_definitions")
public class PolicyDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "policy_key", nullable = false, unique = true, length = 64)
    private String policyKey;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 255)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "active_version_id")
    private PolicyVersion activeVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shadow_version_id")
    private PolicyVersion shadowVersion;

    @Version
    @Column(name = "version_lock", nullable = false)
    private Long versionLock = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public PolicyDefinition() {
    }

    public PolicyDefinition(String policyKey, String name, String description) {
        this.policyKey = Objects.requireNonNull(policyKey, "policyKey must not be null").trim();
        this.name = Objects.requireNonNull(name, "name must not be null").trim();
        this.description = description;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.versionLock == null) {
            this.versionLock = 0L;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getPolicyKey() {
        return policyKey;
    }

    public void setPolicyKey(String policyKey) {
        this.policyKey = policyKey;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public PolicyVersion getActiveVersion() {
        return activeVersion;
    }

    public void setActiveVersion(PolicyVersion activeVersion) {
        this.activeVersion = activeVersion;
    }

    public PolicyVersion getShadowVersion() {
        return shadowVersion;
    }

    public void setShadowVersion(PolicyVersion shadowVersion) {
        this.shadowVersion = shadowVersion;
    }

    public Long getVersionLock() {
        return versionLock;
    }

    public void setVersionLock(Long versionLock) {
        this.versionLock = versionLock;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PolicyDefinition that = (PolicyDefinition) o;
        return Objects.equals(policyKey, that.policyKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(policyKey);
    }
}
