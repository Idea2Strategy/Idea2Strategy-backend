package com.idea2strategy.backend.application.competition;

public enum RoomParticipationAdmissionFailure {
    ACCOUNT_INELIGIBLE,
    ROOM_NOT_JOINABLE,
    ROOM_CAPACITY_REACHED,
    ACCOUNT_ROOM_LIMIT_REACHED,
    ACCOUNT_EXECUTION_LIMIT_REACHED,
    MARKET_SCOPE_MISMATCH,
    PROVISIONED_BOT_INVALID,
    ANONYMOUS_ALIAS_CONFLICT
}
