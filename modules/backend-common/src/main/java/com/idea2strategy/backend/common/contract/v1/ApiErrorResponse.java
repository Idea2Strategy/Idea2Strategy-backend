package com.idea2strategy.backend.common.contract.v1;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ApiErrorResponse(
        String schemaVersion,
        String code,
        String message,
        UUID correlationId,
        Instant occurredAt,
        List<FieldViolation> violations) {

    public ApiErrorResponse {
        schemaVersion = CommonContractVersions.require(CommonContractVersions.API_ERROR_V1, schemaVersion);
        code = requireText(code, "code");
        message = requireText(message, "message");
        Objects.requireNonNull(correlationId, "correlationId is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        violations = List.copyOf(Objects.requireNonNull(violations, "violations are required"));
    }

    public static ApiErrorResponse of(
            String code,
            String message,
            RequestContext requestContext,
            Instant occurredAt,
            List<FieldViolation> violations) {
        Objects.requireNonNull(requestContext, "requestContext is required");
        return new ApiErrorResponse(
                CommonContractVersions.API_ERROR_V1,
                code,
                message,
                requestContext.correlationId(),
                occurredAt,
                violations);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
