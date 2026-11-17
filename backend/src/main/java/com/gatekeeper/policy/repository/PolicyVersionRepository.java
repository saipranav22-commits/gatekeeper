package com.gatekeeper.policy.repository;

import com.gatekeeper.policy.entity.PolicyVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PolicyVersionRepository extends JpaRepository<PolicyVersion, UUID> {

    Optional<PolicyVersion> findByPolicyDefinitionIdAndVersionNumber(UUID definitionId, int versionNumber);

    Optional<PolicyVersion> findByPolicyDefinitionPolicyKeyAndVersionNumber(String policyKey, int versionNumber);

    List<PolicyVersion> findByPolicyDefinitionPolicyKeyOrderByVersionNumberDesc(String policyKey);

    List<PolicyVersion> findByPolicyDefinitionIdOrderByVersionNumberDesc(UUID definitionId);

    @Query("SELECT COALESCE(MAX(v.versionNumber), 0) FROM PolicyVersion v WHERE v.policyDefinition.id = :definitionId")
    int findMaxVersionNumberByDefinitionId(@Param("definitionId") UUID definitionId);
}
