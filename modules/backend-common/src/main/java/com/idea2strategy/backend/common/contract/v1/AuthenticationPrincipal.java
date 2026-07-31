package com.idea2strategy.backend.common.contract.v1;

import java.util.List;
import java.util.Objects;

public record AuthenticationPrincipal(
        String schemaVersion,
        ActorType actorType,
        String actorId,
        long authEpoch,
        List<String> authorities) {

    public AuthenticationPrincipal {
        schemaVersion = CommonContractVersions.require(CommonContractVersions.AUTH_PRINCIPAL_V1, schemaVersion);
        Objects.requireNonNull(actorType, "actorType is required");
        actorId = requireText(actorId, "actorId");
        if (authEpoch < 0) {
            throw new IllegalArgumentException("authEpoch must not be negative");
        }
        authorities = List.copyOf(Objects.requireNonNull(authorities, "authorities are required"));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
