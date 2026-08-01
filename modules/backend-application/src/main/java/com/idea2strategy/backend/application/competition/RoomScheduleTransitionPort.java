package com.idea2strategy.backend.application.competition;

import java.time.Instant;

public interface RoomScheduleTransitionPort {
    RoomScheduleTransitionReport advanceDue(Instant observedAt, int limit);
}
