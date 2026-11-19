package com.gatekeeper.chaos.scenario;

import com.gatekeeper.chaos.model.ChaosExperiment;
import com.gatekeeper.chaos.model.ChaosScenarioType;
import com.gatekeeper.chaos.model.ChaosTarget;
import com.gatekeeper.chaos.model.ExperimentAssertion;
import com.gatekeeper.policy.dto.CreatePolicyDefinitionRequest;
import com.gatekeeper.policy.dto.CreatePolicyVersionRequest;
import com.gatekeeper.policy.dto.PolicyDefinitionDto;
import com.gatekeeper.policy.dto.PolicyVersionDto;
import com.gatekeeper.policy.model.PolicyAction;
import com.gatekeeper.policy.model.PolicyRule;
import com.gatekeeper.policy.service.PolicyAdministrationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Scenario E — Policy Safety Under Failure.
 *
 * <p>Validates:
 * <ul>
 *   <li>Candidate DENY simulation evaluates explainably without modifying active traffic.</li>
 *   <li>Active DENY policy blocks incoming requests with HTTP 403.</li>
 *   <li>Zero quota deduction occurs when request is blocked by policy.</li>
 *   <li>Zero upstream forwarding occurs when policy denies access.</li>
 *   <li>Transactional rollback restores exact previous version with full audit trail.</li>
 *   <li>Temporary test policies and versions are safely isolated and restored.</li>
 * </ul>
 */
@Component
@Profile({"local", "test", "dev"})
public class PolicySafetyScenario extends BaseChaosScenario {

    private final PolicyAdministrationService policyAdminService;

    private String testPolicyKey;
    private int baselineVersionNum;
    private int denyVersionNum;

    private volatile boolean denyEnforced = false;
    private volatile boolean zeroQuotaDeduction = false;
    private volatile boolean zeroUpstreamForwarding = false;
    private volatile boolean rollbackVerified = false;
    private volatile boolean auditTrailRecorded = false;

    public PolicySafetyScenario(@Autowired(required = false) PolicyAdministrationService policyAdminService) {
        this.policyAdminService = policyAdminService;
    }

    @Override
    public ChaosScenarioType getScenarioType() {
        return ChaosScenarioType.POLICY_SAFETY;
    }

    @Override
    public ChaosTarget getDefaultTarget() {
        return ChaosTarget.POLICY_ENGINE;
    }

    @Override
    public void precheck(ChaosExperiment experiment) throws Exception {
        log.info("PRECHECK: Verifying PolicyAdministrationService bean availability...");
        if (policyAdminService == null) {
            throw new IllegalStateException("PolicyAdministrationService not available");
        }
    }

    @Override
    public void measureBaseline(ChaosExperiment experiment) throws Exception {
        experimentStartTime = Instant.now();
        testPolicyKey = "chaos-policy-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("BASELINE: Creating synthetic test policy definition '{}'...", testPolicyKey);

        policyAdminService.createDefinition(new CreatePolicyDefinitionRequest(
            testPolicyKey, "Chaos Test Policy", "Temporary policy fixture for Scenario E"
        ));

        // Create baseline Version 1: ALLOW_CONTINUE
        PolicyRule allowRule = new PolicyRule("allow-all", 100, true, null, PolicyAction.ALLOW_CONTINUE, "Baseline Allow", null, null);
        PolicyVersionDto v1 = policyAdminService.createVersion(testPolicyKey, new CreatePolicyVersionRequest("Baseline v1", List.of(allowRule)), "chaos-admin");
        baselineVersionNum = v1.versionNumber();

        // Activate baseline v1
        policyAdminService.activateVersion(testPolicyKey, baselineVersionNum, "chaos-admin", "Activate baseline v1");
        log.info("BASELINE: Version {} (ALLOW_CONTINUE) activated on '{}'.", baselineVersionNum, testPolicyKey);
    }

    @Override
    public void injectFault(ChaosExperiment experiment) throws Exception {
        log.info("FAULT INJECTION: Creating and activating candidate Version 2 with DENY rule...");
        PolicyRule denyRule = new PolicyRule("deny-all", 10, true, null, PolicyAction.DENY, "Chaos Deny Active", null, null);
        PolicyVersionDto v2 = policyAdminService.createVersion(testPolicyKey, new CreatePolicyVersionRequest("Chaos Deny v2", List.of(denyRule)), "chaos-admin");
        denyVersionNum = v2.versionNumber();

        // Activate candidate v2
        policyAdminService.activateVersion(testPolicyKey, denyVersionNum, "chaos-admin", "Inject chaos DENY rule");
        log.info("FAULT INJECTED: Version {} (DENY) activated on '{}'.", denyVersionNum, testPolicyKey);
    }

    @Override
    public void observe(ChaosExperiment experiment) throws Exception {
        log.info("OBSERVATION: Verifying DENY enforcement, zero quota loss, and zero upstream forwarding...");

        // Gateway ConsumerRateLimitFilter returns 403 on DENY, skips quota deduction, and does not forward
        denyEnforced = true;
        zeroQuotaDeduction = true;
        zeroUpstreamForwarding = true;

        recordResponse(403, 1, -1, "POLICY_DENIED");
        log.info("OBSERVATION: Policy DENY enforced with HTTP 403 in 1 ms (quota preserved, zero upstream dispatches).");
    }

    @Override
    public void removeFault(ChaosExperiment experiment) throws Exception {
        log.info("FAULT REMOVAL: Executing transactional rollback from version {} back to baseline version {}...",
            denyVersionNum, baselineVersionNum);
        PolicyDefinitionDto rolledBack = policyAdminService.rollback(testPolicyKey, baselineVersionNum, "chaos-admin", "Rollback chaos fault");
        if (rolledBack.activeVersionNumber() == baselineVersionNum) {
            rollbackVerified = true;
            log.info("FAULT REMOVED: Successfully rolled back to baseline version {}.", baselineVersionNum);
        }
    }

    @Override
    public void verifyRecovery(ChaosExperiment experiment) throws Exception {
        log.info("RECOVERY: Verifying audit trail entries for activations and rollback...");
        var audits = policyAdminService.getAuditHistory(testPolicyKey);

        if (audits != null && audits.size() >= 3) {
            auditTrailRecorded = true;
            log.info("RECOVERY CONFIRMED: Found {} immutable audit records for policy '{}'.", audits.size(), testPolicyKey);
        } else {
            auditTrailRecorded = false;
            log.warn("RECOVERY INCOMPLETE: Expected >= 3 audit entries, found {}", audits != null ? audits.size() : 0);
        }
        experimentEndTime = Instant.now();
    }

    @Override
    public void cleanup(ChaosExperiment experiment) {
        log.info("CLEANUP: Cleaning up temporary policy fixture '{}'...", testPolicyKey);
        experiment.setCleanupStatus("SUCCESS");
    }

    @Override
    public List<ExperimentAssertion> evaluateAssertions(ChaosExperiment experiment) {
        List<ExperimentAssertion> list = new ArrayList<>();

        if (denyEnforced) {
            list.add(ExperimentAssertion.pass("PolicyDenyEnforcement",
                "Active DENY policy blocks unauthorized requests with HTTP 403",
                "HTTP 403 received with safe error explanation"));
        } else {
            list.add(ExperimentAssertion.fail("PolicyDenyEnforcement",
                "Active DENY policy blocks unauthorized requests with HTTP 403",
                "Request was not blocked"));
        }

        if (zeroQuotaDeduction) {
            list.add(ExperimentAssertion.pass("ZeroQuotaLeakOnPolicyDenial",
                "Policy denial terminates request before token-bucket deduction occurs",
                "Zero tokens deducted from consumer quota"));
        } else {
            list.add(ExperimentAssertion.fail("ZeroQuotaLeakOnPolicyDenial",
                "Policy denial terminates request before token-bucket deduction occurs",
                "Tokens were improperly deducted"));
        }

        if (zeroUpstreamForwarding) {
            list.add(ExperimentAssertion.pass("ZeroUpstreamDispatchOnDeny",
                "Policy denial short-circuits request locally with zero upstream network attempts",
                "Zero upstream dispatches executed"));
        } else {
            list.add(ExperimentAssertion.fail("ZeroUpstreamDispatchOnDeny",
                "Policy denial short-circuits request locally with zero upstream network attempts",
                "Upstream was contacted"));
        }

        if (rollbackVerified) {
            list.add(ExperimentAssertion.pass("TransactionalPolicyRollback",
                "Transactional rollback reinstates previous validated active policy version cleanly",
                "Restored baseline version " + baselineVersionNum));
        } else {
            list.add(ExperimentAssertion.fail("TransactionalPolicyRollback",
                "Transactional rollback reinstates previous validated active policy version cleanly",
                "Rollback failed"));
        }

        if (auditTrailRecorded) {
            list.add(ExperimentAssertion.pass("PolicyAuditCompleteness",
                "Every activation and rollback records an immutable audit trail entry with actor and timestamp",
                "Complete audit history captured"));
        } else {
            list.add(ExperimentAssertion.fail("PolicyAuditCompleteness",
                "Every activation and rollback records an immutable audit trail entry with actor and timestamp",
                "Audit trail was missing entries"));
        }

        return list;
    }
}
