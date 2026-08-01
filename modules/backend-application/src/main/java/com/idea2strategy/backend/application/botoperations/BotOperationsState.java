package com.idea2strategy.backend.application.botoperations;

import com.fasterxml.jackson.annotation.JsonValue;

public enum BotOperationsState {
    WAITING("waiting"),
    RUNNING("running"),
    ACTION_REQUIRED("action-required"),
    STOPPING("stopping"),
    STOPPED("stopped"),
    DATA_DEGRADED("data-degraded"),
    SETTLEMENT_FAILED("settlement-failed");

    private final String wireValue;

    BotOperationsState(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
