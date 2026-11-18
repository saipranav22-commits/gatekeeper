package com.gatekeeper.chaos.model;

import java.util.Objects;

/**
 * Immutable assertion record representing an expected reliability property, its evaluated
 * outcome, and the concrete evidence observed during the chaos experiment.
 */
public record ExperimentAssertion(
    String name,
    String description,
    AssertionOutcome outcome,
    String observedDetails
) {
    public ExperimentAssertion {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        if (observedDetails == null) {
            observedDetails = "";
        }
    }

    public static ExperimentAssertion pass(String name, String description, String details) {
        return new ExperimentAssertion(name, description, AssertionOutcome.PASS, details);
    }

    public static ExperimentAssertion fail(String name, String description, String details) {
        return new ExperimentAssertion(name, description, AssertionOutcome.FAIL, details);
    }

    public static ExperimentAssertion skipped(String name, String description, String reason) {
        return new ExperimentAssertion(name, description, AssertionOutcome.SKIPPED, reason);
    }

    public static ExperimentAssertion inconclusive(String name, String description, String reason) {
        return new ExperimentAssertion(name, description, AssertionOutcome.INCONCLUSIVE, reason);
    }
}
