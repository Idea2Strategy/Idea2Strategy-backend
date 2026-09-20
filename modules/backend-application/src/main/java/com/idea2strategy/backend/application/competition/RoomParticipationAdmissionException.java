package com.idea2strategy.backend.application.competition;

import java.util.Objects;

public final class RoomParticipationAdmissionException extends RuntimeException {
    private final RoomParticipationAdmissionFailure failure;

    public RoomParticipationAdmissionException(RoomParticipationAdmissionFailure failure) {
        super(message(Objects.requireNonNull(failure, "failure")));
        this.failure = failure;
    }

    public RoomParticipationAdmissionFailure failure() {
        return failure;
    }

    private static String message(RoomParticipationAdmissionFailure failure) {
        return switch (failure) {
            case ACCOUNT_INELIGIBLE -> "Account is not eligible for room participation";
            case ROOM_NOT_JOINABLE -> "Room is not accepting participation";
            case ROOM_CAPACITY_REACHED -> "Room participation capacity has been reached";
            case ACCOUNT_ROOM_LIMIT_REACHED -> "Per-account room participation limit has been reached";
            case ACCOUNT_EXECUTION_LIMIT_REACHED -> "Account running and reserved bot limit has been reached";
            case MARKET_SCOPE_MISMATCH -> "Provisioned bot instruments are outside the room market scope";
            case PROVISIONED_BOT_INVALID -> "Provisioned room bot is invalid";
            case ANONYMOUS_ALIAS_CONFLICT -> "Anonymous room alias is already in use";
        };
    }
}
