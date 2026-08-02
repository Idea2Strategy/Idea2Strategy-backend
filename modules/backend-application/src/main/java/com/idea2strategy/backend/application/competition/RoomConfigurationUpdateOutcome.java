package com.idea2strategy.backend.application.competition;

public enum RoomConfigurationUpdateOutcome {
    UPDATED,
    NOT_FOUND_OR_NOT_OWNED,
    ACCESS_TYPE_IMMUTABLE,
    RECRUITMENT_LOCKED
}
