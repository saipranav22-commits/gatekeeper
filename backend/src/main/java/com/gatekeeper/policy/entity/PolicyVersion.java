package com.gatekeeper.policy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
    name = "policy_versions",
    uniqueConstraints = @UniqueConstraint(name = "uk_policy_version", columnNames = {"policy_definition_id", "version_number"})
)
public class PolicyVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_definition_id", nullable = false)
    private PolicyDefinition policyDefinition;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PolicyStatus status = PolicyStatus.DRAFT;

    @Column(name = "rules_json", nullable = false, columnDefinition = "TEXT")
    private String rulesJson;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "activated_by", length = 100)
    private String activatedBy;

    public PolicyVersion() {
    }

    public PolicyVersion(
            PolicyDefinition policyDefinition,
            int versionNumber,
            String rulesJson,
            String description,
            String createdBy) {
        this.policyDefinition = Objects.requireNonNull(policyDefinition, "policyDefinition must not be null");
        this.versionNumber = versionNumber;
        this.rulesJson = Objects.requireNonNull(rulesJson, "rulesJson must not be null");
        this.description = description;
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy must not be null");
        this.status = PolicyStatus.DRAFT;
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public PolicyDefinition getPolicyDefinition() {
        return policyDefinition;
    }

    public void setPolicyDefinition(PolicyDefinition policyDefinition) {
        this.policyDefinition = policyDefinition;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public void setVersionNumber(int versionNumber) {
        this.versionNumber = versionNumber;
    }

    public PolicyStatus getStatus() {
        return status;
    }

    public void setStatus(PolicyStatus status) {
        this.status = status;
    }

    public String getRulesJson() {
        return rulesJson;
    }

    public void setRulesJson(String rulesJson) {
        this.rulesJson = rulesJson;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public void setActivatedAt(Instant activatedAt) {
        this.activatedAt = activatedAt;
    }

    public String getActivatedBy() {
        return activatedBy;
    }

    public void setActivatedBy(String activatedBy) {
        this.activatedBy = activatedBy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PolicyVersion that = (PolicyVersion) o;
        return versionNumber == that.versionNumber &&
               Objects.equals(policyDefinition != null ? policyDefinition.getId() : null,
                              that.policyDefinition != null ? that.policyDefinition.getId() : null);
    }

    @Override
    public int hashCode() {
        return Objects.hash(policyDefinition != null ? policyDefinition.getId() : null, versionNumber);
    }
}
