package com.idea2strategy.backend.application.competition;

import java.util.Objects;

public record RoomParticipationAdmissionOutcome(
        RoomParticipationAdmission admission, RoomParticipationAdmissionFailure failure) {
    public RoomParticipationAdmissionOutcome {
        if ((admission == null) == (failure == null)) {
            throw new IllegalArgumentException("Outcome must contain either an admission or a failure");
        }
    }

    public static RoomParticipationAdmissionOutcome accepted(RoomParticipationAdmission admission) {
        return new RoomParticipationAdmissionOutcome(Objects.requireNonNull(admission, "admission"), null);
    }

    public static RoomParticipationAdmissionOutcome rejected(RoomParticipationAdmissionFailure failure) {
        return new RoomParticipationAdmissionOutcome(null, Objects.requireNonNull(failure, "failure"));
    }

    public boolean accepted() {
        return admission != null;
    }
}
