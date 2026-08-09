package com.idea2strategy.backend.application.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Browser approval for a command-line client.
 *
 * <p>The CLI asks for a pair of codes, shows the short one to a person, and polls with the long
 * one. The person approves in a browser session that already holds their credential, so the CLI —
 * and anything driving it — never handles a password.
 *
 * <p>The two codes are not interchangeable. The user code is short enough to read aloud, so it is
 * guessable by construction and only ever identifies a request for approval; the device code is
 * the secret that collects the token. Swapping their roles would make a shoulder-surfed code
 * enough to steal a session.
 */
public final class DeviceAuthorizationService {
    private final DeviceAuthorizationCommandPort commands;
    private final DeviceCodeMaterialPort codes;
    private final Clock clock;
    private final Duration lifetime;
    private final short pollIntervalSeconds;

    public DeviceAuthorizationService(
            DeviceAuthorizationCommandPort commands,
            DeviceCodeMaterialPort codes,
            Clock clock,
            Duration lifetime,
            short pollIntervalSeconds) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.codes = Objects.requireNonNull(codes, "codes");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lifetime = Objects.requireNonNull(lifetime, "lifetime");
        if (pollIntervalSeconds < 1) {
            throw new IllegalArgumentException("poll interval must be positive");
        }
        this.pollIntervalSeconds = pollIntervalSeconds;
    }

    public DeviceAuthorizationGrant request(String clientLabel) {
        String label = requireText(clientLabel, "clientLabel");
        DeviceCodeMaterial material = codes.issue();
        Instant now = clock.instant();
        UUID id = commands.create(
                material.deviceCodeDigest(),
                material.userCodeDigest(),
                material.digestKeyVersion(),
                label,
                pollIntervalSeconds,
                now,
                now.plus(lifetime));
        return new DeviceAuthorizationGrant(
                id,
                material.deviceCode(),
                material.userCode(),
                now.plus(lifetime),
                pollIntervalSeconds);
    }

    /**
     * Approval is an authenticated browser action. The account comes from that session and never
     * from the request body, so a person can only ever approve a device onto their own account.
     */
    public void approve(String userCode, UUID accountId) {
        Objects.requireNonNull(accountId, "accountId");
        String digest = codes.digestUserCode(requireText(userCode, "userCode"));
        if (!commands.approve(digest, accountId, clock.instant())) {
            throw new DeviceAuthorizationRejectedException("No pending device request matches that code");
        }
    }

    public void deny(String userCode) {
        String digest = codes.digestUserCode(requireText(userCode, "userCode"));
        if (!commands.deny(digest, clock.instant())) {
            throw new DeviceAuthorizationRejectedException("No pending device request matches that code");
        }
    }

    /**
     * Collecting the token consumes the request. Returning it more than once would leave a
     * long-lived code that mints sessions, which is the thing a short expiry is meant to prevent.
     */
    public DeviceAuthorizationOutcome collect(String deviceCode) {
        String digest = codes.digestDeviceCode(requireText(deviceCode, "deviceCode"));
        return commands.consume(digest, clock.instant());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
