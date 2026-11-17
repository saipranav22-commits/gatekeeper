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

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "policy_activation_audits")
public class PolicyActivationAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_definition_id", nullable = false)
    private PolicyDefinition policyDefinition;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "previous_version_number")
    private Integer previousVersionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 30)
    private PolicyAuditAction action;

    @Column(name = "actor", nullable = false, length = 100)
    private String actor;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public PolicyActivationAudit() {
    }

    public PolicyActivationAudit(
            PolicyDefinition policyDefinition,
            int versionNumber,
            Integer previousVersionNumber,
            PolicyAuditAction action,
            String actor,
            String reason) {
        this.policyDefinition = Objects.requireNonNull(policyDefinition, "policyDefinition must not be null");
        this.versionNumber = versionNumber;
        this.previousVersionNumber = previousVersionNumber;
        this.action = Objects.requireNonNull(action, "action must not be null");
        this.actor = Objects.requireNonNull(actor, "actor must not be null");
        this.reason = reason;
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

    public Integer getPreviousVersionNumber() {
        return previousVersionNumber;
    }

    public void setPreviousVersionNumber(Integer previousVersionNumber) {
        this.previousVersionNumber = previousVersionNumber;
    }

    public PolicyAuditAction getAction() {
        return action;
    }

    public void setAction(PolicyAuditAction action) {
        this.action = action;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
