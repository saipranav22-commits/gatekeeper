package com.gatekeeper.policy.repository;

import com.gatekeeper.policy.entity.PolicyActivationAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PolicyActivationAuditRepository extends JpaRepository<PolicyActivationAudit, UUID> {

    List<PolicyActivationAudit> findByPolicyDefinitionPolicyKeyOrderByCreatedAtDesc(String policyKey);

    List<PolicyActivationAudit> findByPolicyDefinitionIdOrderByCreatedAtDesc(UUID definitionId);
}
