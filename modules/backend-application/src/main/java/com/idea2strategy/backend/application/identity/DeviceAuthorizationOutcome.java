package com.idea2strategy.backend.application.identity;

import java.util.Optional;
import java.util.UUID;

/**
 * What a polling client learns.
 *
 * <p>PENDING and the terminal states are deliberately distinct: a client that cannot tell "not yet"
 * from "denied" either gives up on a live request or polls a dead one forever.
 */
public record DeviceAuthorizationOutcome(Status status, Optional<UUID> accountId) {
    public enum Status {
        PENDING,
        APPROVED,
        DENIED,
        EXPIRED,
        UNKNOWN
    }

    public static DeviceAuthorizationOutcome pending() {
        return new DeviceAuthorizationOutcome(Status.PENDING, Optional.empty());
    }

    public static DeviceAuthorizationOutcome approved(UUID accountId) {
        return new DeviceAuthorizationOutcome(Status.APPROVED, Optional.of(accountId));
    }

    public static DeviceAuthorizationOutcome of(Status status) {
        return new DeviceAuthorizationOutcome(status, Optional.empty());
    }
}
