package com.idea2strategy.backend.application.adminmcp;

import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record AdminMcpInvocation(
        OperatorRequestContext requestContext,
        String registryVersion,
        String toolName,
        String requestSchemaVersion,
        String targetId,
        String decidedContentHash,
        Map<String, Object> input,
        UUID correlationId,
        String idempotencyKey,
        String requestHash) {
    public AdminMcpInvocation {
        Objects.requireNonNull(requestContext, "requestContext");
        requireText(registryVersion, "registryVersion");
        requireText(toolName, "toolName");
        requireText(requestSchemaVersion, "requestSchemaVersion");
        requireText(targetId, "targetId");
        input = Map.copyOf(input);
        Objects.requireNonNull(correlationId, "correlationId");
        requireText(idempotencyKey, "idempotencyKey");
        if (requestHash == null || !requestHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestHash must be lowercase SHA-256 hex");
        }
        if (decidedContentHash != null && !decidedContentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("decidedContentHash must be lowercase SHA-256 hex");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
