package com.idea2strategy.backend.common.contract.v1;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CommonContractFixtures(
        ObjectMapper json,
        AuthenticationPrincipal principal,
        RequestContext requestContext,
        EventEnvelope authenticationEvent,
        MutableFakeClock clock,
        FakePrincipalProvider authentication) {

    public static CommonContractFixtures standard() {
        var json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        var principal = new AuthenticationPrincipal(
                CommonContractVersions.AUTH_PRINCIPAL_V1,
                ActorType.ACCOUNT,
                "00000000-0000-4000-8000-000000000101",
                7,
                List.of("ROLE_USER"));
        var context = new RequestContext(
                UUID.fromString("00000000-0000-4000-8000-000000000102"),
                "idem-common-auth-0001");
        var clock = new MutableFakeClock(Instant.parse("2026-07-31T12:00:00Z"), ZoneOffset.UTC);
        var event = new EventEnvelope(
                CommonContractVersions.EVENT_ENVELOPE_V1,
                UUID.fromString("00000000-0000-4000-8000-000000000103"),
                "identity.authentication.succeeded",
                principal,
                clock.instant(),
                context.correlationId(),
                context.idempotencyKey(),
                Map.of("authenticationEventId", "00000000-0000-4000-8000-000000000104", "result", "SUCCESS"));
        return new CommonContractFixtures(json, principal, context, event, clock, new FakePrincipalProvider(principal));
    }

    public String loadResource(String path) {
        try (var stream = CommonContractFixtures.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalArgumentException("Fixture resource not found: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to load fixture resource: " + path, exception);
        }
    }
}
