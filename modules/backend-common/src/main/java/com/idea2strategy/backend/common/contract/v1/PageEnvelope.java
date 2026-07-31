package com.idea2strategy.backend.common.contract.v1;

import java.util.List;
import java.util.Objects;

public record PageEnvelope<T>(String schemaVersion, List<T> items, String nextCursor, boolean hasMore) {
    public PageEnvelope {
        schemaVersion = CommonContractVersions.require(CommonContractVersions.PAGE_V1, schemaVersion);
        items = List.copyOf(Objects.requireNonNull(items, "items are required"));
        if (hasMore && (nextCursor == null || nextCursor.isBlank())) {
            throw new IllegalArgumentException("nextCursor is required when hasMore is true");
        }
        if (!hasMore) {
            nextCursor = null;
        }
    }
}
