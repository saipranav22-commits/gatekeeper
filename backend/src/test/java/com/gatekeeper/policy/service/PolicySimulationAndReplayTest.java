package com.gatekeeper.policy.service;

import com.gatekeeper.analytics.entity.UsageEvent;
import com.gatekeeper.analytics.repository.UsageEventRepository;
import com.gatekeeper.policy.dto.CreatePolicyDefinitionRequest;
import com.gatekeeper.policy.dto.CreatePolicyVersionRequest;
import com.gatekeeper.policy.dto.PolicyReplayRequest;
import com.gatekeeper.policy.dto.PolicyReplayResponse;
import com.gatekeeper.policy.dto.PolicySimulationRequest;
import com.gatekeeper.policy.dto.PolicySimulationResponse;
import com.gatekeeper.policy.model.PolicyAction;
import com.gatekeeper.policy.model.PolicyRule;
import com.gatekeeper.policy.model.PolicyRuleConditions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("PolicySimulationAndReplayTest - Dry-Run & Historical Impact Analysis (Phase 5C)")
class PolicySimulationAndReplayTest {

    @Autowired
    private PolicyAdministrationService adminService;

    @Autowired
    private PolicySimulationService simulationService;

    @Autowired
    private PolicyReplayService replayService;

    @Autowired
    private UsageEventRepository usageEventRepository;

    private String testPolicyKey;

    @BeforeEach
    void setUp() {
        testPolicyKey = "sim-policy-" + UUID.randomUUID().toString().substring(0, 8);
        adminService.createDefinition(new CreatePolicyDefinitionRequest(
            testPolicyKey, "Simulation Test Policy", "Policy for simulation tests"
        ));
    }

    @Test
    @DisplayName("Dry-run simulation evaluates candidate rules without database or gateway mutations")
    void simulationEvaluatesCandidateRulesSafely() {
        // Active version allows all
        PolicyRule rActive = new PolicyRule("allow-all", 100, true, null, PolicyAction.ALLOW_CONTINUE, "Allow", null, null);
        adminService.createVersion(testPolicyKey, new CreatePolicyVersionRequest("v1", List.of(rActive)), "admin");
        adminService.activateVersion(testPolicyKey, 1, "admin", "Activate v1");

        // Simulate a candidate rule that would DENY POST requests
        PolicyRule rCandidate = new PolicyRule(
            "deny-post", 50, true,
            new PolicyRuleConditions("orders-service-v1", "POST", null, null, null, null, null, null),
            PolicyAction.DENY, "Deny post", "POST_DENIED", "No post allowed"
        );

        PolicySimulationRequest simReq = new PolicySimulationRequest(
            null,
            List.of(rCandidate),
            "orders-service-v1",
            "POST",
            "PRO",
            Set.of("orders:write"),
            "127.0.0.1",
            Instant.now()
        );

        PolicySimulationResponse simResp = simulationService.simulate(testPolicyKey, simReq);

        assertThat(simResp.decision()).isEqualTo(PolicyAction.DENY);
        assertThat(simResp.matchedRuleId()).isEqualTo("deny-post");
        assertThat(simResp.reasonCode()).isEqualTo("POST_DENIED");
        assertThat(simResp.hypotheticalQuotaConsumption()).isFalse();

        // Verify active policy in DB was NOT changed
        assertThat(adminService.getDefinition(testPolicyKey).activeVersionNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("Historical replay refuses to fabricate missing scopes and marks records NOT_EVALUABLE")
    void replayHandlesMissingScopeMetadataSafely() {
        // Create candidate version with scope condition
        PolicyRule rScope = new PolicyRule(
            "scope-restricted-rule", 100, true,
            new PolicyRuleConditions(null, null, null, Set.of("special:scope"), null, null, null, null),
            PolicyAction.DENY, "Scope check", null, null
        );
        adminService.createVersion(testPolicyKey, new CreatePolicyVersionRequest("v1", List.of(rScope)), "admin");

        // Seed sample usage event (which does NOT retain historical scopes)
        UsageEvent event = new UsageEvent("/api/v1/proxy/orders", "GET", 200, 25);
        event.setRouteId("orders-service-v1");
        usageEventRepository.save(event);

        PolicyReplayRequest replayReq = new PolicyReplayRequest(1, null, 50, 24);
        PolicyReplayResponse replayResp = replayService.replay(testPolicyKey, replayReq);

        // When rules depend on scopes, records cannot be faithfully evaluated
        assertThat(replayResp.notEvaluableCount()).isGreaterThan(0);
        assertThat(replayResp.sampleDifferences().get(0).candidateDecision()).isEqualTo("NOT_EVALUABLE");
        assertThat(replayResp.sampleDifferences().get(0).differenceReason()).contains("API key scopes");
    }

    @Test
    @DisplayName("Historical replay evaluates eligible path/method/plan rules accurately")
    void replayEvaluatesEligibleRulesAccurately() {
        // Seed usage events
        UsageEvent ev1 = new UsageEvent("/api/v1/proxy/orders", "GET", 200, 15);
        ev1.setRouteId("orders-service-v1");
        usageEventRepository.save(ev1);

        UsageEvent ev2 = new UsageEvent("/api/v1/proxy/orders", "POST", 201, 30);
        ev2.setRouteId("orders-service-v1");
        usageEventRepository.save(ev2);

        // Candidate version: deny POST on orders
        PolicyRule rDenyPost = new PolicyRule(
            "deny-orders-post", 100, true,
            new PolicyRuleConditions("orders-service-v1", "POST", null, null, null, null, null, null),
            PolicyAction.DENY, "Deny post", "POST_DENIED", "Post denied"
        );
        adminService.createVersion(testPolicyKey, new CreatePolicyVersionRequest("v1", List.of(rDenyPost)), "admin");

        PolicyReplayRequest req = new PolicyReplayRequest(1, null, 10, 24);
        PolicyReplayResponse resp = replayService.replay(testPolicyKey, req);

        assertThat(resp.totalEligibleRecords()).isGreaterThanOrEqualTo(2);
        assertThat(resp.newlyDenied()).isGreaterThanOrEqualTo(1); // POST was newly denied
        assertThat(resp.decisionsUnchanged()).isGreaterThanOrEqualTo(1); // GET remained unchanged
    }
}
