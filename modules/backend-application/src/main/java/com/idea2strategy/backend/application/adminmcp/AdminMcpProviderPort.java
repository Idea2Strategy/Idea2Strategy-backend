package com.idea2strategy.backend.application.adminmcp;

import java.util.Map;
import java.util.Objects;

public interface AdminMcpProviderPort {
    Result invoke(Request request);

    record Request(
            String toolName,
            String targetDomain,
            String targetId,
            Long expectedTargetVersion,
            Map<String, Object> input,
            String idempotencyKey) {
        public Request {
            input = Map.copyOf(input);
        }
    }

    record Result(Status status, String code, Map<String, Object> before, Map<String, Object> after) {
        public Result {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(code, "code");
            before = Map.copyOf(before);
            after = Map.copyOf(after);
        }

        public enum Status {
            SUCCEEDED,
            REJECTED,
            TIMEOUT,
            UNKNOWN
        }
    }
}
