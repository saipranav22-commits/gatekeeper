package com.gatekeeper.policy.service;

import com.gatekeeper.policy.dto.CreatePolicyDefinitionRequest;
import com.gatekeeper.policy.dto.CreatePolicyVersionRequest;
import com.gatekeeper.policy.dto.PolicyActivationAuditDto;
import com.gatekeeper.policy.dto.PolicyDefinitionDto;
import com.gatekeeper.policy.dto.PolicyVersionDto;
import com.gatekeeper.policy.engine.PolicyCacheManager;
import com.gatekeeper.policy.entity.PolicyStatus;
import com.gatekeeper.policy.model.PolicyAction;
import com.gatekeeper.policy.model.PolicyRule;
import com.gatekeeper.policy.model.PolicyRuleConditions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("PolicyAdministrationIntegrationTest - Database & Transactional Integrity (Phase 5C)")
class PolicyAdministrationIntegrationTest {

    @Autowired
    private PolicyAdministrationService adminService;

    @Autowired
    private PolicyCacheManager cacheManager;

    private String testPolicyKey;

    @BeforeEach
    void setUp() {
        testPolicyKey = "test-policy-" + UUID.randomUUID().toString().substring(0, 8);
        adminService.createDefinition(new CreatePolicyDefinitionRequest(
            testPolicyKey, "Integration Test Policy", "Policy for database integration tests"
        ));
    }

    @Test
    @DisplayName("Duplicate policy keys are strictly rejected")
    void duplicatePolicyKeyRejected() {
        assertThatThrownBy(() -> adminService.createDefinition(new CreatePolicyDefinitionRequest(
            testPolicyKey, "Duplicate", "Should fail"
        ))).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("Rule validation rejects duplicate rule IDs and malformed time windows")
    void ruleValidationEnforced() {
        PolicyRule r1 = new PolicyRule("rule-1", 100, true, null, PolicyAction.ALLOW_CONTINUE, "R1", null, null);
        PolicyRule r1Duplicate = new PolicyRule("rule-1", 200, true, null, PolicyAction.DENY, "R1 Dup", null, null);

        assertThatThrownBy(() -> adminService.createVersion(
            testPolicyKey, new CreatePolicyVersionRequest("Duplicate test", List.of(r1, r1Duplicate)), "admin"
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Duplicate ruleId");

        // Malformed time window
        PolicyRule rMalformedTime = new PolicyRule(
            "rule-time",
            100,
            true,
            new PolicyRuleConditions(null, null, null, null, null, "not-a-time", "04:00", null),
            PolicyAction.DENY,
            "Bad time",
            null,
            null
        );

        assertThatThrownBy(() -> adminService.createVersion(
            testPolicyKey, new CreatePolicyVersionRequest("Bad time test", List.of(rMalformedTime)), "admin"
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("malformed time window format");
    }

    @Test
    @DisplayName("Version creation creates immutable draft versions with sequential numbers")
    void immutableVersionsCreatedSequentially() {
        PolicyRule rule1 = new PolicyRule("allow-all", 100, true, null, PolicyAction.ALLOW_CONTINUE, "Allow all", null, null);
        PolicyVersionDto v1 = adminService.createVersion(
            testPolicyKey, new CreatePolicyVersionRequest("Initial version", List.of(rule1)), "admin"
        );

        assertThat(v1.versionNumber()).isEqualTo(1);
        assertThat(v1.status()).isEqualTo("DRAFT");
        assertThat(v1.rulesCount()).isEqualTo(1);

        PolicyRule rule2 = new PolicyRule("deny-all", 50, true, null, PolicyAction.DENY, "Deny all", null, null);
        PolicyVersionDto v2 = adminService.createVersion(
            testPolicyKey, new CreatePolicyVersionRequest("Second version", List.of(rule2)), "admin"
        );

        assertThat(v2.versionNumber()).isEqualTo(2);
        assertThat(v2.status()).isEqualTo("DRAFT");

        List<PolicyVersionDto> versions = adminService.listVersions(testPolicyKey);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).versionNumber()).isEqualTo(2); // ordered desc
        assertThat(versions.get(1).versionNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("Transactional activation, retirement, and atomic cache update")
    void transactionalActivationAndCacheUpdate() {
        PolicyRule rule = new PolicyRule("orders-deny", 100, true, null, PolicyAction.DENY, "Deny", "DENY_CODE", "Safe reason");
        adminService.createVersion(testPolicyKey, new CreatePolicyVersionRequest("v1", List.of(rule)), "admin");

        PolicyDefinitionDto activated = adminService.activateVersion(testPolicyKey, 1, "admin", "Initial deployment");
        assertThat(activated.activeVersionNumber()).isEqualTo(1);

        // Verify version status in DB
        PolicyVersionDto v1Db = adminService.getVersion(testPolicyKey, 1);
        assertThat(v1Db.status()).isEqualTo("ACTIVE");
        assertThat(v1Db.activatedBy()).isEqualTo("admin");

        // Verify in-memory cache updated atomically
        Optional<PolicyCacheManager.ActivePolicySnapshot> cacheOpt = cacheManager.getActivePolicy(testPolicyKey);
        assertThat(cacheOpt).isPresent();
        assertThat(cacheOpt.get().versionNumber()).isEqualTo(1);
        assertThat(cacheOpt.get().activeRules()).hasSize(1);
        assertThat(cacheOpt.get().activeRules().get(0).ruleId()).isEqualTo("orders-deny");

        // Verify audit log
        List<PolicyActivationAuditDto> audits = adminService.getAuditHistory(testPolicyKey);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).versionNumber()).isEqualTo(1);
        assertThat(audits.get(0).action()).isEqualTo("ACTIVATED");
        assertThat(audits.get(0).actor()).isEqualTo("admin");
    }

    @Test
    @DisplayName("Safe rollback to previous immutable version preserves historical definitions")
    void safeRollbackPreservesHistory() {
        // Create v1
        PolicyRule r1 = new PolicyRule("v1-rule", 100, true, null, PolicyAction.ALLOW_CONTINUE, "v1", null, null);
        adminService.createVersion(testPolicyKey, new CreatePolicyVersionRequest("v1", List.of(r1)), "admin");
        adminService.activateVersion(testPolicyKey, 1, "admin", "Activate v1");

        // Create v2 and activate
        PolicyRule r2 = new PolicyRule("v2-rule", 50, true, null, PolicyAction.DENY, "v2", null, null);
        adminService.createVersion(testPolicyKey, new CreatePolicyVersionRequest("v2", List.of(r2)), "admin");
        adminService.activateVersion(testPolicyKey, 2, "admin", "Activate v2 (bad policy)");

        PolicyDefinitionDto cur = adminService.getDefinition(testPolicyKey);
        assertThat(cur.activeVersionNumber()).isEqualTo(2);

        // Roll back to v1
        PolicyDefinitionDto rolledBack = adminService.rollback(testPolicyKey, 1, "admin", "Rolling back bad policy");
        assertThat(rolledBack.activeVersionNumber()).isEqualTo(1);

        // Verify v2 is now ROLLED_BACK and v1 is ACTIVE
        PolicyVersionDto v1 = adminService.getVersion(testPolicyKey, 1);
        PolicyVersionDto v2 = adminService.getVersion(testPolicyKey, 2);
        assertThat(v1.status()).isEqualTo("ACTIVE");
        assertThat(v2.status()).isEqualTo("ROLLED_BACK");

        // Historical rules in v1 are intact and not modified
        assertThat(v1.rules().get(0).ruleId()).isEqualTo("v1-rule");

        // Audit records the rollback
        List<PolicyActivationAuditDto> audits = adminService.getAuditHistory(testPolicyKey);
        assertThat(audits).hasSize(3);
        assertThat(audits.get(0).action()).isEqualTo("ROLLED_BACK");
        assertThat(audits.get(0).versionNumber()).isEqualTo(1);
        assertThat(audits.get(0).previousVersionNumber()).isEqualTo(2);

        // Cache updated to v1
        Optional<PolicyCacheManager.ActivePolicySnapshot> cacheOpt = cacheManager.getActivePolicy(testPolicyKey);
        assertThat(cacheOpt).isPresent();
        assertThat(cacheOpt.get().versionNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("Shadow policy configuration attaches candidate version without changing authoritative status")
    void shadowPolicyConfiguration() {
        PolicyRule r1 = new PolicyRule("active-rule", 100, true, null, PolicyAction.ALLOW_CONTINUE, "v1", null, null);
        adminService.createVersion(testPolicyKey, new CreatePolicyVersionRequest("v1", List.of(r1)), "admin");
        adminService.activateVersion(testPolicyKey, 1, "admin", "Activate v1");

        PolicyRule r2 = new PolicyRule("shadow-rule", 50, true, null, PolicyAction.DENY, "v2", null, null);
        adminService.createVersion(testPolicyKey, new CreatePolicyVersionRequest("v2", List.of(r2)), "admin");

        // Configure v2 as shadow
        PolicyDefinitionDto shadowConfig = adminService.configureShadowVersion(testPolicyKey, 2, "admin");
        assertThat(shadowConfig.activeVersionNumber()).isEqualTo(1); // active is STILL v1!
        assertThat(shadowConfig.shadowVersionNumber()).isEqualTo(2);

        Optional<PolicyCacheManager.ActivePolicySnapshot> cacheOpt = cacheManager.getActivePolicy(testPolicyKey);
        assertThat(cacheOpt).isPresent();
        assertThat(cacheOpt.get().versionNumber()).isEqualTo(1);
        assertThat(cacheOpt.get().shadowVersionNumber()).isEqualTo(2);
        assertThat(cacheOpt.get().shadowRules()).hasSize(1);
        assertThat(cacheOpt.get().shadowRules().get(0).ruleId()).isEqualTo("shadow-rule");

        // Clear shadow version
        PolicyDefinitionDto cleared = adminService.configureShadowVersion(testPolicyKey, null, "admin");
        assertThat(cleared.shadowVersionNumber()).isNull();
    }
}
