package com.idea2strategy.backend.messaging.performance.contract;

public enum LivePerformanceInputDecision {
    ACCEPTED,
    ROOM_MISMATCH,
    SEGMENT_MISMATCH,
    SCHEDULE_VERSION_MISMATCH,
    BACKTEST_SOURCE_NOT_ALLOWED,
    BEFORE_EVALUATION_START,
    AT_OR_AFTER_EVALUATION_END
}
