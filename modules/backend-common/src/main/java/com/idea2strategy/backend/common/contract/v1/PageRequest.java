package com.idea2strategy.backend.common.contract.v1;

public record PageRequest(String cursor, int limit) {
    public PageRequest {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        if (cursor != null && cursor.isBlank()) {
            cursor = null;
        }
    }
}
