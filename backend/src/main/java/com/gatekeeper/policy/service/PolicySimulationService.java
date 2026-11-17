package com.gatekeeper.policy.service;

import com.gatekeeper.common.exception.ResourceNotFoundException;
import com.gatekeeper.policy.dto.PolicySimulationRequest;
import com.gatekeeper.policy.dto.PolicySimulationResponse;
import com.gatekeeper.policy.engine.PolicyEvaluationEngine;
import com.gatekeeper.policy.entity.PolicyDefinition;
import com.gatekeeper.policy.entity.PolicyVersion;
import com.gatekeeper.policy.model.PolicyAction;
import com.gatekeeper.policy.model.PolicyDecision;
import com.gatekeeper.policy.model.PolicyEvaluationContext;
import com.gatekeeper.policy.model.PolicyRule;
import com.gatekeeper.policy.repository.PolicyDefinitionRepository;
import com.gatekeeper.policy.repository.PolicyVersionRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Pure simulation service for dry-run policy evaluation.
 *
 * <p>Strict Isolation Invariants:
 * <ul>
 *   <li>Never mutates active policy state or version history.</li>
 *   <li>Never decrements Redis Token Bucket quotas.</li>
 *   <li>Never executes upstream HTTP network dispatches.</li>
 *   <li>Never alters circuit breakers or route bulkheads.</li>
 * </ul>
 */
@Service
@Profile("!foundation")
public class PolicySimulationService {

    private final PolicyDefinitionRepository definitionRepository;
    private final PolicyVersionRepository versionRepository;
    private final PolicyEvaluationEngine evaluationEngine;
    private final PolicyAdministrationService adminService;

    public PolicySimulationService(
            PolicyDefinitionRepository definitionRepository,
            PolicyVersionRepository versionRepository,
            PolicyEvaluationEngine evaluationEngine,
            PolicyAdministrationService adminService) {
        this.definitionRepository = definitionRepository;
        this.versionRepository = versionRepository;
        this.evaluationEngine = evaluationEngine;
        this.adminService = adminService;
    }

    @Transactional(readOnly = true)
    public PolicySimulationResponse simulate(String policyKey, PolicySimulationRequest req) {
        PolicyDefinition def = definitionRepository.findByPolicyKey(policyKey)
            .orElseThrow(() -> new ResourceNotFoundException("Policy definition not found with key: " + policyKey));

        List<PolicyRule> rulesToEvaluate;
        Integer versionEvaluated = null;

        if (req.candidateRules() != null && !req.candidateRules().isEmpty()) {
            rulesToEvaluate = req.candidateRules();
        } else if (req.candidateVersionNumber() != null) {
            PolicyVersion version = versionRepository.findByPolicyDefinitionPolicyKeyAndVersionNumber(policyKey, req.candidateVersionNumber())
                .orElseThrow(() -> new ResourceNotFoundException("Policy version " + req.candidateVersionNumber() + " not found for policy " + policyKey));
            rulesToEvaluate = adminService.parseRules(version.getRulesJson());
            versionEvaluated = version.getVersionNumber();
        } else if (def.getActiveVersion() != null) {
            rulesToEvaluate = adminService.parseRules(def.getActiveVersion().getRulesJson());
            versionEvaluated = def.getActiveVersion().getVersionNumber();
        } else {
            rulesToEvaluate = Collections.emptyList();
            versionEvaluated = 0;
        }

        PolicyEvaluationContext context = new PolicyEvaluationContext(
            req.routeId(),
            req.httpMethod(),
            req.clientIp(),
            null, // Simulation does not require a real consumer UUID
            req.consumerPlan(),
            req.apiKeyScopes(),
            req.requestTimeUtc() != null ? req.requestTimeUtc() : Instant.now()
        );

        PolicyDecision decision = evaluationEngine.evaluate(policyKey, versionEvaluated, rulesToEvaluate, context);

        return new PolicySimulationResponse(
            policyKey,
            versionEvaluated,
            decision.action(),
            decision.matchedRuleId(),
            decision.reasonCode(),
            decision.safeReason(),
            decision.action() == PolicyAction.ALLOW_CONTINUE,
            decision.durationNanos(),
            decision.trace()
        );
    }
}
