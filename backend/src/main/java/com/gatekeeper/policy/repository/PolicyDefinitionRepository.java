package com.gatekeeper.policy.repository;

import com.gatekeeper.policy.entity.PolicyDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PolicyDefinitionRepository extends JpaRepository<PolicyDefinition, UUID> {

    Optional<PolicyDefinition> findByPolicyKey(String policyKey);

    boolean existsByPolicyKey(String policyKey);
}
