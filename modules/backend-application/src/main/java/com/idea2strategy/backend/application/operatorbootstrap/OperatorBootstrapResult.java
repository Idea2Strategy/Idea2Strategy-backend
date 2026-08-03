package com.idea2strategy.backend.application.operatorbootstrap;

import java.time.Instant;
import java.util.UUID;

public record OperatorBootstrapResult(
        boolean replayed,
        String bootstrapKey,
        String manifestHash,
        String catalogVersion,
        UUID operatorAccountId,
        UUID operatorRoleAssignmentId,
        UUID correlationId,
        UUID auditEventId,
        Instant appliedAt) {}
