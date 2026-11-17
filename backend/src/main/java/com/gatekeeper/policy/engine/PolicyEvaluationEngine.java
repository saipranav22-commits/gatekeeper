package com.gatekeeper.policy.engine;

import com.gatekeeper.policy.model.PolicyDecision;
import com.gatekeeper.policy.model.PolicyEvaluationContext;
import com.gatekeeper.policy.model.PolicyRule;

import java.util.List;

/**
 * Pure, deterministic policy evaluation engine.
 *
 * <p>Evaluation Invariants:
 * <ul>
 *   <li><b>Explicit Precedence:</b> Rules are evaluated in strict priority order (lowest number = highest precedence).</li>
 *   <li><b>Deterministic Tie-Breaking:</b> Identical priorities are deterministically ordered by {@code ruleId} alphabetical order.</li>
 *   <li><b>First-Match Semantics:</b> The first matching enabled rule determines the decision.</li>
 *   <li><b>Default Permissive Continuation:</b> If no rule matches, returns {@code ALLOW_CONTINUE} while mandatory security applies.</li>
 *   <li><b>Zero Side-Effects:</b> Evaluation does not modify any external state, Redis quotas, or databases.</li>
 * </ul>
 */
public interface PolicyEvaluationEngine {

    /**
     * Evaluates a list of rules against the given request context.
     *
     * @param policyKey identifier of the policy
     * @param versionNumber version number being evaluated
     * @param rules list of rules in the policy version
     * @param context trusted request context
     * @return structured policy decision with explainable trace
     */
    PolicyDecision evaluate(String policyKey, Integer versionNumber, List<PolicyRule> rules, PolicyEvaluationContext context);
}
