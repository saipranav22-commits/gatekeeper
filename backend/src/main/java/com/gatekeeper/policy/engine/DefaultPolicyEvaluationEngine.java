package com.gatekeeper.policy.engine;

import com.gatekeeper.policy.model.PolicyAction;
import com.gatekeeper.policy.model.PolicyDecision;
import com.gatekeeper.policy.model.PolicyDecisionTrace;
import com.gatekeeper.policy.model.PolicyEvaluationContext;
import com.gatekeeper.policy.model.PolicyRule;
import com.gatekeeper.policy.model.PolicyRuleConditions;
import com.gatekeeper.policy.model.RuleEvaluationTrace;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default high-performance, deterministic implementation of {@link PolicyEvaluationEngine}.
 */
@Component
public class DefaultPolicyEvaluationEngine implements PolicyEvaluationEngine {

    public static final int MAX_RULES_PER_POLICY = 500;

    @Override
    public PolicyDecision evaluate(
            String policyKey,
            Integer versionNumber,
            List<PolicyRule> rules,
            PolicyEvaluationContext context) {

        long startNanos = System.nanoTime();
        UUID evaluationId = UUID.randomUUID();

        if (rules == null || rules.isEmpty()) {
            long duration = System.nanoTime() - startNanos;
            PolicyDecisionTrace trace = new PolicyDecisionTrace(
                evaluationId,
                context.requestTimeUtc(),
                policyKey,
                versionNumber,
                context.routeId(),
                PolicyAction.ALLOW_CONTINUE,
                null,
                "DEFAULT_ALLOW",
                "No rules configured in policy version",
                duration,
                0,
                Collections.emptyList()
            );
            return PolicyDecision.allowDefault(policyKey, versionNumber, duration, trace);
        }

        // Bounded rule count guard
        int candidateCount = Math.min(rules.size(), MAX_RULES_PER_POLICY);
        List<PolicyRule> sortedRules = new ArrayList<>(rules.subList(0, candidateCount));
        Collections.sort(sortedRules); // Sorts by priority asc, then ruleId asc

        List<RuleEvaluationTrace> traces = new ArrayList<>(candidateCount);

        for (PolicyRule rule : sortedRules) {
            if (rule.enabled() != null && !rule.enabled()) {
                traces.add(RuleEvaluationTrace.nonMatched(rule, "Rule is disabled"));
                continue;
            }

            MatchResult match = evaluateRuleConditions(rule.conditions(), context);
            if (match.matched()) {
                traces.add(RuleEvaluationTrace.matched(rule, match.explanation()));
                long duration = System.nanoTime() - startNanos;

                PolicyDecisionTrace trace = new PolicyDecisionTrace(
                    evaluationId,
                    context.requestTimeUtc(),
                    policyKey,
                    versionNumber,
                    context.routeId(),
                    rule.action(),
                    rule.ruleId(),
                    rule.reasonCode(),
                    rule.safeReason(),
                    duration,
                    traces.size(),
                    traces
                );

                return PolicyDecision.match(rule, policyKey, versionNumber, duration, trace);
            } else {
                traces.add(RuleEvaluationTrace.nonMatched(rule, match.explanation()));
            }
        }

        // No matching rule found -> default ALLOW_CONTINUE
        long duration = System.nanoTime() - startNanos;
        PolicyDecisionTrace trace = new PolicyDecisionTrace(
            evaluationId,
            context.requestTimeUtc(),
            policyKey,
            versionNumber,
            context.routeId(),
            PolicyAction.ALLOW_CONTINUE,
            null,
            "DEFAULT_ALLOW",
            "No policy rules matched request context",
            duration,
            traces.size(),
            traces
        );

        return PolicyDecision.allowDefault(policyKey, versionNumber, duration, trace);
    }

    private MatchResult evaluateRuleConditions(PolicyRuleConditions conditions, PolicyEvaluationContext context) {
        if (conditions == null) {
            return MatchResult.success("Empty conditions match all requests");
        }

        // 1. Route ID match
        if (conditions.routeId() != null && !conditions.routeId().isBlank()) {
            if (context.routeId() == null || !conditions.routeId().equalsIgnoreCase(context.routeId().trim())) {
                return MatchResult.fail(String.format("Route '%s' does not match required '%s'",
                    context.routeId(), conditions.routeId()));
            }
        }

        // 2. HTTP Method match
        if (conditions.httpMethod() != null && !conditions.httpMethod().isBlank()) {
            if (context.httpMethod() == null || !conditions.httpMethod().equalsIgnoreCase(context.httpMethod().trim())) {
                return MatchResult.fail(String.format("HTTP method '%s' does not match required '%s'",
                    context.httpMethod(), conditions.httpMethod()));
            }
        }

        // 3. Consumer Plan match
        if (conditions.consumerPlan() != null && !conditions.consumerPlan().isBlank()) {
            if (context.consumerPlan() == null || !conditions.consumerPlan().equalsIgnoreCase(context.consumerPlan().trim())) {
                return MatchResult.fail(String.format("Consumer plan '%s' does not match required '%s'",
                    context.consumerPlan(), conditions.consumerPlan()));
            }
        }

        // 4. Required Scopes match
        if (conditions.requiredScopes() != null && !conditions.requiredScopes().isEmpty()) {
            Set<String> missing = conditions.requiredScopes().stream()
                .filter(scope -> !context.apiKeyScopes().contains(scope))
                .collect(Collectors.toSet());
            if (!missing.isEmpty()) {
                return MatchResult.fail(String.format("Missing required scope(s): %s", missing));
            }
        }

        // 5. Excluded Scopes match
        if (conditions.excludedScopes() != null && !conditions.excludedScopes().isEmpty()) {
            Set<String> present = conditions.excludedScopes().stream()
                .filter(scope -> context.apiKeyScopes().contains(scope))
                .collect(Collectors.toSet());
            if (!present.isEmpty()) {
                return MatchResult.fail(String.format("Contains excluded scope(s): %s", present));
            }
        }

        // 6. Time Window in UTC
        if (conditions.timeWindowStartUtc() != null && conditions.timeWindowEndUtc() != null) {
            try {
                LocalTime start = LocalTime.parse(conditions.timeWindowStartUtc());
                LocalTime end = LocalTime.parse(conditions.timeWindowEndUtc());
                LocalTime current = context.requestTimeUtc().atZone(ZoneOffset.UTC).toLocalTime();

                boolean inWindow;
                if (!start.isAfter(end)) {
                    inWindow = !current.isBefore(start) && !current.isAfter(end);
                } else {
                    // Window spans midnight (e.g. 23:00 to 02:00)
                    inWindow = !current.isBefore(start) || !current.isAfter(end);
                }

                if (!inWindow) {
                    return MatchResult.fail(String.format("Current time %s UTC outside allowed window [%s - %s] UTC",
                        current, start, end));
                }
            } catch (DateTimeParseException ex) {
                return MatchResult.fail("Malformed time window format: " + ex.getMessage());
            }
        }

        // 7. Days of Week in UTC
        if (conditions.daysOfWeek() != null && !conditions.daysOfWeek().isEmpty()) {
            DayOfWeek currentDay = context.requestTimeUtc().atZone(ZoneOffset.UTC).getDayOfWeek();
            boolean dayMatch = conditions.daysOfWeek().stream()
                .anyMatch(d -> d.equalsIgnoreCase(currentDay.name()));
            if (!dayMatch) {
                return MatchResult.fail(String.format("Day of week %s does not match required days: %s",
                    currentDay, conditions.daysOfWeek()));
            }
        }

        return MatchResult.success("All match criteria satisfied");
    }

    private record MatchResult(boolean matched, String explanation) {
        public static MatchResult success(String explanation) {
            return new MatchResult(true, explanation);
        }

        public static MatchResult fail(String reason) {
            return new MatchResult(false, reason);
        }
    }
}
