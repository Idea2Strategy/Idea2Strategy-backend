package com.idea2strategy.backend.application.adminmcp;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record AdminMcpToolRegistry(String version, Status status, Map<String, Tool> tools) {
    public AdminMcpToolRegistry {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("registry version is required");
        }
        Objects.requireNonNull(status, "status");
        tools = Map.copyOf(tools);
        boolean invalidEntry = tools.entrySet().stream().anyMatch(entry ->
                !entry.getKey().equals(entry.getValue().name()) || entry.getValue().capability().forbidden());
        if (invalidEntry) {
            throw new IllegalArgumentException("registry contains a mismatched or forbidden tool");
        }
    }

    public record Tool(
            String name,
            Capability capability,
            Mode mode,
            UUID permissionId,
            String targetDomain,
            String requestSchemaVersion,
            Set<String> requiredInputFields,
            Set<String> allowedInputFields,
            Set<String> allowedOutputFields) {
        public Tool {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("tool name is required");
            }
            Objects.requireNonNull(capability, "capability");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(permissionId, "permissionId");
            if (targetDomain == null || targetDomain.isBlank()) {
                throw new IllegalArgumentException("targetDomain is required");
            }
            if (requestSchemaVersion == null || requestSchemaVersion.isBlank()) {
                throw new IllegalArgumentException("requestSchemaVersion is required");
            }
            requiredInputFields = Set.copyOf(requiredInputFields);
            allowedInputFields = Set.copyOf(allowedInputFields);
            allowedOutputFields = Set.copyOf(allowedOutputFields);
            if (!allowedInputFields.containsAll(requiredInputFields)) {
                throw new IllegalArgumentException("required input fields must be allowed");
            }
        }
    }

    public enum Status {
        DRAFT,
        ACTIVE,
        RETIRED
    }

    public enum Mode {
        QUERY,
        APPROVAL
    }

    public enum Capability {
        CORPORATE_ACTION_CANDIDATE_QUERY(false),
        CORPORATE_ACTION_CANDIDATE_APPROVE(false),
        DATA_INCIDENT_QUERY(false),
        DATA_INCIDENT_APPROVE(false),
        ROOM_CASE_QUERY(false),
        ROOM_CASE_APPROVE(false),
        ACCOUNT_CASE_QUERY(false),
        ACCOUNT_CASE_APPROVE(false),
        STRATEGY_CREATE(true),
        PRIVATE_STRATEGY_SOURCE_READ(true),
        USER_ORDER_MUTATION(true);

        private final boolean forbidden;

        Capability(boolean forbidden) {
            this.forbidden = forbidden;
        }

        public boolean forbidden() {
            return forbidden;
        }
    }
}
