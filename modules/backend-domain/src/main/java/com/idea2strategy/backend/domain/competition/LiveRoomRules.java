package com.idea2strategy.backend.domain.competition;

import java.util.Objects;

public record LiveRoomRules(
        String stoppedBotSlotPolicy, long minimumOperationSeconds, int minimumFillCount) {
    public LiveRoomRules {
        Objects.requireNonNull(stoppedBotSlotPolicy, "stoppedBotSlotPolicy");
        if (!stoppedBotSlotPolicy.matches("[A-Z][A-Z0-9_]{0,29}")) {
            throw new IllegalArgumentException("stoppedBotSlotPolicy must be an uppercase policy code");
        }
        if (minimumOperationSeconds < 0) {
            throw new IllegalArgumentException("minimumOperationSeconds must be nonnegative");
        }
        if (minimumFillCount < 0) {
            throw new IllegalArgumentException("minimumFillCount must be nonnegative");
        }
    }
}
