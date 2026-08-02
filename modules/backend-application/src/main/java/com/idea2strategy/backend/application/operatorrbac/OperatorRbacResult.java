package com.idea2strategy.backend.application.operatorrbac;

import java.util.Objects;

public record OperatorRbacResult(
        DecisionStatus decisionStatus,
        String code,
        OperatorRbacDecision.Mutation mutation,
        OperatorRbacDecision.Evidence evidence) {

    public OperatorRbacResult {
        Objects.requireNonNull(decisionStatus, "decisionStatus");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(evidence, "evidence");
        if (decisionStatus == DecisionStatus.APPLIED && mutation == null) {
            throw new IllegalArgumentException("applied result requires a mutation");
        }
        if (decisionStatus != DecisionStatus.APPLIED && mutation != null) {
            throw new IllegalArgumentException("non-applied result cannot contain a mutation");
        }
    }

    public enum DecisionStatus {
        APPLIED,
        NO_OP,
        REJECTED
    }
}
