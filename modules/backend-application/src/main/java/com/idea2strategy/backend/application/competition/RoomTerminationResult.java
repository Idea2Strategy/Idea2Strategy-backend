package com.idea2strategy.backend.application.competition;

import java.time.Instant;
import java.util.UUID;

public record RoomTerminationResult(UUID roomId, int participationsTerminated, Instant occurredAt) {}
