package com.gatekeeper.policy.dto;

import java.util.List;
import java.util.UUID;

public record PolicyReplayResponse(
    String policyKey,
    Integer activeVersionEvaluated,
    Integer candidateVersionEvaluated,
    int totalRecordsSampled,
    int totalEligibleRecords,
    int decisionsUnchanged,
    int newlyDenied,
    int newlyPermitted,
    int notEvaluableCount,
    List<ReplayDifferenceDetail> sampleDifferences
) {
    public record ReplayDifferenceDetail(
        UUID eventId,
        String endpointPath,
        String httpMethod,
        String consumerPlan,
        String activeDecision,
        String candidateDecision,
        String differenceReason
    ) {}
}
