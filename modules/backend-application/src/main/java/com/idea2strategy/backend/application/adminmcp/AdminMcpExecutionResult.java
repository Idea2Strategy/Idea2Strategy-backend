package com.idea2strategy.backend.application.adminmcp;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record AdminMcpExecutionResult(
        Status status,
        String code,
        Map<String, Object> response,
        AuditEvidence auditEvidence) {
    public AdminMcpExecutionResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(code, "code");
        response = Map.copyOf(response);
        Objects.requireNonNull(auditEvidence, "auditEvidence");
    }

    public enum Status {
        RETURNED,
        APPLIED,
        REJECTED
    }

    public record AuditEvidence(
            UUID actorId,
            String registryVersion,
            String rbacCatalogVersion,
            String toolName,
            String targetDomain,
            String targetId,
            String decidedContentHash,
            String reasonCode,
            UUID correlationId,
            Instant evaluatedAt,
            Map<String, Object> before,
            Map<String, Object> after) {
        public AuditEvidence {
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(registryVersion, "registryVersion");
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(targetDomain, "targetDomain");
            Objects.requireNonNull(targetId, "targetId");
            Objects.requireNonNull(reasonCode, "reasonCode");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(evaluatedAt, "evaluatedAt");
            before = Map.copyOf(before);
            after = Map.copyOf(after);
        }
    }
}
