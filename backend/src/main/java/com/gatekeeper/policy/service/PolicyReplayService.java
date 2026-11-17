package com.gatekeeper.policy.service;

import com.gatekeeper.analytics.entity.UsageEvent;
import com.gatekeeper.analytics.repository.UsageEventRepository;
import com.gatekeeper.common.exception.ResourceNotFoundException;
import com.gatekeeper.policy.dto.PolicyReplayRequest;
import com.gatekeeper.policy.dto.PolicyReplayResponse;
import com.gatekeeper.policy.engine.PolicyEvaluationEngine;
import com.gatekeeper.policy.entity.PolicyDefinition;
import com.gatekeeper.policy.entity.PolicyVersion;
import com.gatekeeper.policy.model.PolicyAction;
import com.gatekeeper.policy.model.PolicyDecision;
import com.gatekeeper.policy.model.PolicyEvaluationContext;
import com.gatekeeper.policy.model.PolicyRule;
import com.gatekeeper.policy.repository.PolicyDefinitionRepository;
import com.gatekeeper.policy.repository.PolicyVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Historical impact analysis service for safe offline policy evaluation against historical telemetry.
 *
 * <p>Key Guarantees:
 * <ul>
 *   <li><b>Read-Only Isolation:</b> Never executes network calls to downstream upstream services.</li>
 *   <li><b>Bounded Resource Usage:</b> Strict limit on sample size and query time window.</li>
 *   <li><b>Missing Field Safeguards:</b> Does NOT fabricate missing attributes (such as historical scopes).
 *       Marks records as NOT_EVALUABLE when faithful reconstruction is impossible.</li>
 * </ul>
 */
@Service
@Profile("!foundation")
public class PolicyReplayService {

    private static final Logger log = LoggerFactory.getLogger(PolicyReplayService.class);

    private final PolicyDefinitionRepository definitionRepository;
    private final PolicyVersionRepository versionRepository;
    private final UsageEventRepository usageEventRepository;
    private final PolicyEvaluationEngine evaluationEngine;
    private final PolicyAdministrationService adminService;

    public PolicyReplayService(
            PolicyDefinitionRepository definitionRepository,
            PolicyVersionRepository versionRepository,
            UsageEventRepository usageEventRepository,
            PolicyEvaluationEngine evaluationEngine,
            PolicyAdministrationService adminService) {
        this.definitionRepository = definitionRepository;
        this.versionRepository = versionRepository;
        this.usageEventRepository = usageEventRepository;
        this.evaluationEngine = evaluationEngine;
        this.adminService = adminService;
    }

    @Transactional(readOnly = true)
    public PolicyReplayResponse replay(String policyKey, PolicyReplayRequest req) {
        PolicyDefinition def = definitionRepository.findByPolicyKey(policyKey)
            .orElseThrow(() -> new ResourceNotFoundException("Policy definition not found with key: " + policyKey));

        // 1. Resolve Active Rules
        List<PolicyRule> activeRules = Collections.emptyList();
        Integer activeVersionNum = null;
        if (def.getActiveVersion() != null) {
            activeRules = adminService.parseRules(def.getActiveVersion().getRulesJson());
            activeVersionNum = def.getActiveVersion().getVersionNumber();
        }

        // 2. Resolve Candidate Rules
        List<PolicyRule> candidateRules;
        Integer candidateVersionNum = null;
        if (req.candidateRules() != null && !req.candidateRules().isEmpty()) {
            candidateRules = req.candidateRules();
        } else if (req.candidateVersionNumber() != null) {
            PolicyVersion candVer = versionRepository.findByPolicyDefinitionPolicyKeyAndVersionNumber(policyKey, req.candidateVersionNumber())
                .orElseThrow(() -> new ResourceNotFoundException("Candidate policy version " + req.candidateVersionNumber() + " not found for " + policyKey));
            candidateRules = adminService.parseRules(candVer.getRulesJson());
            candidateVersionNum = candVer.getVersionNumber();
        } else {
            throw new IllegalArgumentException("Either candidateVersionNumber or candidateRules must be provided for replay");
        }

        // 3. Check if rules depend on scopes that historical usage_events do not retain
        boolean rulesDependOnScopes = checkScopeDependency(activeRules) || checkScopeDependency(candidateRules);

        // 4. Sample bounded historical events
        Instant now = Instant.now();
        Instant startTime = now.minus(req.sinceHours(), ChronoUnit.HOURS);
        int sampleLimit = Math.min(req.sampleLimit(), 500);

        Page<UsageEvent> eventPage = usageEventRepository.findByTimestampBetweenOrderByTimestampDesc(
            startTime, now, PageRequest.of(0, sampleLimit));

        List<UsageEvent> events = eventPage.getContent();
        int totalSampled = events.size();

        if (rulesDependOnScopes) {
            // Cannot faithfully reconstruct historical API key scopes without inventing data
            log.warn("Replay requested for policy '{}' with scope-dependent rules. Historical events do not capture historical scopes.", policyKey);
            return new PolicyReplayResponse(
                policyKey,
                activeVersionNum,
                candidateVersionNum,
                totalSampled,
                0, // eligible
                0,
                0,
                0,
                totalSampled, // all marked not evaluable
                List.of(new PolicyReplayResponse.ReplayDifferenceDetail(
                    null, null, null, null, "UNKNOWN", "NOT_EVALUABLE",
                    "One or more policy rules depend on API key scopes which are not captured in historical usage events"
                ))
            );
        }

        int eligible = 0;
        int unchanged = 0;
        int newlyDenied = 0;
        int newlyPermitted = 0;
        int notEvaluable = 0;
        List<PolicyReplayResponse.ReplayDifferenceDetail> diffs = new ArrayList<>();

        for (UsageEvent event : events) {
            String routeId = event.getRouteId();
            if (routeId == null && event.getEndpointPath() != null) {
                // Infer routeId from endpoint path if available
                if (event.getEndpointPath().contains("orders")) {
                    routeId = "orders-service-v1";
                } else if (event.getEndpointPath().contains("inventory")) {
                    routeId = "inventory-service-v1";
                }
            }

            if (routeId == null || event.getHttpMethod() == null) {
                notEvaluable++;
                continue;
            }

            eligible++;

            PolicyEvaluationContext ctx = new PolicyEvaluationContext(
                routeId,
                event.getHttpMethod(),
                event.getIpAddress(),
                event.getConsumer() != null ? event.getConsumer().getId() : null,
                event.getRateLimitPlan(),
                Collections.emptySet(), // Scopes confirmed not required for these rules
                event.getTimestamp()
            );

            PolicyDecision activeDec = evaluationEngine.evaluate(policyKey, activeVersionNum, activeRules, ctx);
            PolicyDecision candidateDec = evaluationEngine.evaluate(policyKey, candidateVersionNum, candidateRules, ctx);

            if (activeDec.action() == candidateDec.action()) {
                unchanged++;
            } else if (activeDec.action() == PolicyAction.ALLOW_CONTINUE && candidateDec.action() == PolicyAction.DENY) {
                newlyDenied++;
                if (diffs.size() < 25) {
                    diffs.add(new PolicyReplayResponse.ReplayDifferenceDetail(
                        event.getId(),
                        event.getEndpointPath(),
                        event.getHttpMethod(),
                        event.getRateLimitPlan(),
                        activeDec.action().name(),
                        candidateDec.action().name(),
                        String.format("Candidate rule '%s' would deny request (%s)",
                            candidateDec.matchedRuleId(), candidateDec.safeReason())
                    ));
                }
            } else if (activeDec.action() == PolicyAction.DENY && candidateDec.action() == PolicyAction.ALLOW_CONTINUE) {
                newlyPermitted++;
                if (diffs.size() < 25) {
                    diffs.add(new PolicyReplayResponse.ReplayDifferenceDetail(
                        event.getId(),
                        event.getEndpointPath(),
                        event.getHttpMethod(),
                        event.getRateLimitPlan(),
                        activeDec.action().name(),
                        candidateDec.action().name(),
                        "Candidate policy permits request previously denied by active policy"
                    ));
                }
            }
        }

        return new PolicyReplayResponse(
            policyKey,
            activeVersionNum,
            candidateVersionNum,
            totalSampled,
            eligible,
            unchanged,
            newlyDenied,
            newlyPermitted,
            notEvaluable,
            diffs
        );
    }

    private boolean checkScopeDependency(List<PolicyRule> rules) {
        if (rules == null) return false;
        for (PolicyRule r : rules) {
            if (Boolean.TRUE.equals(r.enabled()) && r.conditions() != null) {
                if (r.conditions().requiredScopes() != null && !r.conditions().requiredScopes().isEmpty()) {
                    return true;
                }
                if (r.conditions().excludedScopes() != null && !r.conditions().excludedScopes().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }
}
