package com.gatekeeper.policy.engine;

import com.gatekeeper.policy.model.PolicyAction;
import com.gatekeeper.policy.model.PolicyDecision;
import com.gatekeeper.policy.model.PolicyEvaluationContext;
import com.gatekeeper.policy.model.PolicyRule;
import com.gatekeeper.policy.model.PolicyRuleConditions;
import com.gatekeeper.policy.model.RuleEvaluationTrace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PolicyEvaluationEngineTest - Deterministic Engine Verification (Phase 5C)")
class PolicyEvaluationEngineTest {

    private DefaultPolicyEvaluationEngine engine;

    @BeforeEach
    void setUp() {
        engine = new DefaultPolicyEvaluationEngine();
    }

    @Test
    @DisplayName("Empty rules list defaults to ALLOW_CONTINUE with default trace")
    void emptyRulesListDefaultsToAllowContinue() {
        PolicyEvaluationContext ctx = new PolicyEvaluationContext(
            "orders-service-v1", "GET", "127.0.0.1", UUID.randomUUID(), "FREE", Set.of(), Instant.now()
        );

        PolicyDecision decision = engine.evaluate("test-policy", 1, Collections.emptyList(), ctx);

        assertThat(decision.action()).isEqualTo(PolicyAction.ALLOW_CONTINUE);
        assertThat(decision.matchedRuleId()).isNull();
        assertThat(decision.reasonCode()).isEqualTo("DEFAULT_ALLOW");
        assertThat(decision.quotaConsumed()).isTrue();
        assertThat(decision.trace().candidateRulesCount()).isZero();
    }

    @Test
    @DisplayName("Strict priority ordering: lowest numerical priority evaluated first")
    void strictPriorityOrdering() {
        PolicyRule ruleLowPriority = new PolicyRule(
            "rule-low-prio",
            200,
            true,
            new PolicyRuleConditions("orders-service-v1", "POST", null, null, null, null, null, null),
            PolicyAction.ALLOW_CONTINUE,
            "Low priority allow",
            "ALLOW_LOW",
            "Permitted by low priority"
        );

        PolicyRule ruleHighPriority = new PolicyRule(
            "rule-high-prio",
            100,
            true,
            new PolicyRuleConditions("orders-service-v1", "POST", null, null, null, null, null, null),
            PolicyAction.DENY,
            "High priority deny",
            "DENY_HIGH",
            "Denied by high priority"
        );

        PolicyEvaluationContext ctx = new PolicyEvaluationContext(
            "orders-service-v1", "POST", "127.0.0.1", UUID.randomUUID(), "PRO", Set.of("orders:write"), Instant.now()
        );

        // Pass in reverse order to ensure engine sorts deterministically
        PolicyDecision decision = engine.evaluate("orders-policy", 1, List.of(ruleLowPriority, ruleHighPriority), ctx);

        assertThat(decision.action()).isEqualTo(PolicyAction.DENY);
        assertThat(decision.matchedRuleId()).isEqualTo("rule-high-prio");
        assertThat(decision.reasonCode()).isEqualTo("DENY_HIGH");
        assertThat(decision.quotaConsumed()).isFalse();
    }

    @Test
    @DisplayName("Stable tie-breaking: identical priorities break tie by ruleId alphabetical ascending")
    void stableTieBreakingByRuleId() {
        PolicyRule ruleB = new PolicyRule(
            "rule-b",
            100,
            true,
            new PolicyRuleConditions("orders-service-v1", null, null, null, null, null, null, null),
            PolicyAction.ALLOW_CONTINUE,
            "Rule B",
            "ALLOW_B",
            "Allowed by B"
        );

        PolicyRule ruleA = new PolicyRule(
            "rule-a",
            100,
            true,
            new PolicyRuleConditions("orders-service-v1", null, null, null, null, null, null, null),
            PolicyAction.DENY,
            "Rule A",
            "DENY_A",
            "Denied by A"
        );

        PolicyEvaluationContext ctx = new PolicyEvaluationContext(
            "orders-service-v1", "GET", "127.0.0.1", UUID.randomUUID(), "FREE", Set.of(), Instant.now()
        );

        // rule-a should win tie-break over rule-b because "rule-a" < "rule-b"
        PolicyDecision decision = engine.evaluate("tie-policy", 1, List.of(ruleB, ruleA), ctx);

        assertThat(decision.action()).isEqualTo(PolicyAction.DENY);
        assertThat(decision.matchedRuleId()).isEqualTo("rule-a");
    }

    @Test
    @DisplayName("Disabled rules are skipped and recorded in diagnostic trace")
    void disabledRulesSkipped() {
        PolicyRule disabledRule = new PolicyRule(
            "disabled-deny",
            10,
            false, // disabled!
            new PolicyRuleConditions("orders-service-v1", null, null, null, null, null, null, null),
            PolicyAction.DENY,
            "Disabled rule",
            "DENY_DIS",
            "Disabled"
        );

        PolicyRule activeRule = new PolicyRule(
            "active-allow",
            20,
            true,
            new PolicyRuleConditions("orders-service-v1", null, null, null, null, null, null, null),
            PolicyAction.ALLOW_CONTINUE,
            "Active allow",
            "ALLOW_ACT",
            "Allowed"
        );

        PolicyEvaluationContext ctx = new PolicyEvaluationContext(
            "orders-service-v1", "GET", "127.0.0.1", UUID.randomUUID(), "FREE", Set.of(), Instant.now()
        );

        PolicyDecision decision = engine.evaluate("test-policy", 1, List.of(disabledRule, activeRule), ctx);

        assertThat(decision.action()).isEqualTo(PolicyAction.ALLOW_CONTINUE);
        assertThat(decision.matchedRuleId()).isEqualTo("active-allow");

        List<RuleEvaluationTrace> traces = decision.trace().ruleEvaluations();
        assertThat(traces).hasSize(2);
        assertThat(traces.get(0).ruleId()).isEqualTo("disabled-deny");
        assertThat(traces.get(0).matched()).isFalse();
        assertThat(traces.get(0).nonMatchReason()).contains("disabled");
    }

    @Test
    @DisplayName("Route and HTTP method matching with explainable non-match traces")
    void routeAndMethodMatching() {
        PolicyRule ordersPostRule = new PolicyRule(
            "deny-orders-write",
            50,
            true,
            new PolicyRuleConditions("orders-service-v1", "POST", null, null, null, null, null, null),
            PolicyAction.DENY,
            "Deny orders creation",
            "ORDERS_WRITE_DENIED",
            "Creation of orders is restricted"
        );

        // Test GET request on orders route -> should not match rule
        PolicyEvaluationContext getCtx = new PolicyEvaluationContext(
            "orders-service-v1", "GET", "127.0.0.1", UUID.randomUUID(), "FREE", Set.of(), Instant.now()
        );
        PolicyDecision getDecision = engine.evaluate("orders-policy", 1, List.of(ordersPostRule), getCtx);
        assertThat(getDecision.action()).isEqualTo(PolicyAction.ALLOW_CONTINUE);
        assertThat(getDecision.trace().ruleEvaluations().get(0).nonMatchReason())
            .contains("HTTP method 'GET' does not match required 'POST'");

        // Test POST request on inventory route -> should not match rule
        PolicyEvaluationContext invCtx = new PolicyEvaluationContext(
            "inventory-service-v1", "POST", "127.0.0.1", UUID.randomUUID(), "FREE", Set.of(), Instant.now()
        );
        PolicyDecision invDecision = engine.evaluate("orders-policy", 1, List.of(ordersPostRule), invCtx);
        assertThat(invDecision.action()).isEqualTo(PolicyAction.ALLOW_CONTINUE);
        assertThat(invDecision.trace().ruleEvaluations().get(0).nonMatchReason())
            .contains("Route 'inventory-service-v1' does not match required 'orders-service-v1'");

        // Test POST request on orders route -> matches!
        PolicyEvaluationContext postCtx = new PolicyEvaluationContext(
            "orders-service-v1", "POST", "127.0.0.1", UUID.randomUUID(), "FREE", Set.of(), Instant.now()
        );
        PolicyDecision postDecision = engine.evaluate("orders-policy", 1, List.of(ordersPostRule), postCtx);
        assertThat(postDecision.action()).isEqualTo(PolicyAction.DENY);
        assertThat(postDecision.matchedRuleId()).isEqualTo("deny-orders-write");
        assertThat(postDecision.reasonCode()).isEqualTo("ORDERS_WRITE_DENIED");
    }

    @Test
    @DisplayName("Plan-based matching (e.g. restrict FREE tier)")
    void planBasedMatching() {
        PolicyRule freeTierDeny = new PolicyRule(
            "restrict-free-tier",
            100,
            true,
            new PolicyRuleConditions("orders-service-v1", null, "FREE", null, null, null, null, null),
            PolicyAction.DENY,
            "Free tier not permitted on orders",
            "FREE_TIER_RESTRICTED",
            "Please upgrade to PRO or ENTERPRISE"
        );

        // Free tier -> denied
        PolicyEvaluationContext freeCtx = new PolicyEvaluationContext(
            "orders-service-v1", "GET", "127.0.0.1", UUID.randomUUID(), "FREE", Set.of(), Instant.now()
        );
        PolicyDecision freeDecision = engine.evaluate("plan-policy", 1, List.of(freeTierDeny), freeCtx);
        assertThat(freeDecision.action()).isEqualTo(PolicyAction.DENY);

        // Pro tier -> allowed
        PolicyEvaluationContext proCtx = new PolicyEvaluationContext(
            "orders-service-v1", "GET", "127.0.0.1", UUID.randomUUID(), "PRO", Set.of(), Instant.now()
        );
        PolicyDecision proDecision = engine.evaluate("plan-policy", 1, List.of(freeTierDeny), proCtx);
        assertThat(proDecision.action()).isEqualTo(PolicyAction.ALLOW_CONTINUE);
        assertThat(proDecision.trace().ruleEvaluations().get(0).nonMatchReason())
            .contains("Consumer plan 'PRO' does not match required 'FREE'");
    }

    @Test
    @DisplayName("Scope conditions (required scopes and excluded scopes)")
    void scopeConditionsMatching() {
        PolicyRule vipRule = new PolicyRule(
            "vip-only-rule",
            50,
            true,
            new PolicyRuleConditions(null, null, null, Set.of("orders:vip", "orders:admin"), null, null, null, null),
            PolicyAction.ALLOW_CONTINUE,
            "VIP access allow",
            "VIP_ALLOWED",
            "VIP confirmed"
        );

        PolicyRule generalDeny = new PolicyRule(
            "general-deny",
            100,
            true,
            new PolicyRuleConditions(null, null, null, null, null, null, null, null),
            PolicyAction.DENY,
            "Non-VIP denied",
            "NON_VIP_DENIED",
            "Only VIPs allowed"
        );

        // Missing one of required scopes
        PolicyEvaluationContext partialScopeCtx = new PolicyEvaluationContext(
            "orders-service-v1", "GET", "127.0.0.1", UUID.randomUUID(), "PRO", Set.of("orders:vip"), Instant.now()
        );
        PolicyDecision d1 = engine.evaluate("scope-policy", 1, List.of(vipRule, generalDeny), partialScopeCtx);
        assertThat(d1.action()).isEqualTo(PolicyAction.DENY);
        assertThat(d1.matchedRuleId()).isEqualTo("general-deny");
        assertThat(d1.trace().ruleEvaluations().get(0).nonMatchReason()).contains("Missing required scope(s)");

        // Has both required scopes
        PolicyEvaluationContext fullScopeCtx = new PolicyEvaluationContext(
            "orders-service-v1", "GET", "127.0.0.1", UUID.randomUUID(), "PRO", Set.of("orders:vip", "orders:admin"), Instant.now()
        );
        PolicyDecision d2 = engine.evaluate("scope-policy", 1, List.of(vipRule, generalDeny), fullScopeCtx);
        assertThat(d2.action()).isEqualTo(PolicyAction.ALLOW_CONTINUE);
        assertThat(d2.matchedRuleId()).isEqualTo("vip-only-rule");
    }

    @Test
    @DisplayName("UTC Time window matching (including standard and midnight-crossing windows)")
    void timeWindowMatching() {
        // Window 1: standard 02:00 to 04:00 UTC
        PolicyRule maintenanceRule = new PolicyRule(
            "maintenance-deny",
            50,
            true,
            new PolicyRuleConditions(null, null, null, null, null, "02:00:00", "04:00:00", null),
            PolicyAction.DENY,
            "Nightly maintenance",
            "MAINTENANCE_WINDOW",
            "Service under nightly maintenance"
        );

        // 03:00 UTC -> inside window -> DENY
        Instant at0300 = Instant.parse("2026-11-17T03:00:00Z");
        PolicyEvaluationContext inWindowCtx = new PolicyEvaluationContext(
            "orders-service-v1", "GET", "127.0.0.1", UUID.randomUUID(), "PRO", Set.of(), at0300
        );
        PolicyDecision inDecision = engine.evaluate("maint-policy", 1, List.of(maintenanceRule), inWindowCtx);
        assertThat(inDecision.action()).isEqualTo(PolicyAction.DENY);

        // 12:00 UTC -> outside window -> ALLOW
        Instant at1200 = Instant.parse("2026-11-17T12:00:00Z");
        PolicyEvaluationContext outWindowCtx = new PolicyEvaluationContext(
            "orders-service-v1", "GET", "127.0.0.1", UUID.randomUUID(), "PRO", Set.of(), at1200
        );
        PolicyDecision outDecision = engine.evaluate("maint-policy", 1, List.of(maintenanceRule), outWindowCtx);
        assertThat(outDecision.action()).isEqualTo(PolicyAction.ALLOW_CONTINUE);

        // Window 2: midnight-crossing window 23:00 to 02:00 UTC
        PolicyRule midnightRule = new PolicyRule(
            "midnight-deny",
            50,
            true,
            new PolicyRuleConditions(null, null, null, null, null, "23:00:00", "02:00:00", null),
            PolicyAction.DENY,
            "Late night maintenance",
            "MIDNIGHT_MAINTENANCE",
            "Late night maintenance"
        );

        // 23:30 UTC -> inside midnight-crossing window -> DENY
        Instant at2330 = Instant.parse("2026-11-17T23:30:00Z");
        PolicyEvaluationContext midCtx1 = new PolicyEvaluationContext(
            "orders-service-v1", "GET", "127.0.0.1", UUID.randomUUID(), "PRO", Set.of(), at2330
        );
        assertThat(engine.evaluate("maint-policy", 1, List.of(midnightRule), midCtx1).action())
            .isEqualTo(PolicyAction.DENY);

        // 01:15 UTC -> inside midnight-crossing window -> DENY
        Instant at0115 = Instant.parse("2026-11-17T01:15:00Z");
        PolicyEvaluationContext midCtx2 = new PolicyEvaluationContext(
            "orders-service-v1", "GET", "127.0.0.1", UUID.randomUUID(), "PRO", Set.of(), at0115
        );
        assertThat(engine.evaluate("maint-policy", 1, List.of(midnightRule), midCtx2).action())
            .isEqualTo(PolicyAction.DENY);
    }
}
