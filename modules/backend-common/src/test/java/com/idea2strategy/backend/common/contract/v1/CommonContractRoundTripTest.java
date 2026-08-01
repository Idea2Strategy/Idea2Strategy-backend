package com.idea2strategy.backend.common.contract.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.type.TypeFactory;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommonContractRoundTripTest {

    @Test
    void loadsEveryPublishedValidFixture() throws Exception {
        var fixtures = CommonContractFixtures.standard();

        var principal = fixtures.json().readValue(
                fixtures.loadResource("contracts/common/v1/principal.valid.json"),
                AuthenticationPrincipal.class);
        var event = fixtures.json().readValue(
                fixtures.loadResource("contracts/common/v1/authentication-event.valid.json"),
                EventEnvelope.class);
        var error = fixtures.json().readValue(
                fixtures.loadResource("contracts/common/v1/api-error.valid.json"),
                ApiErrorResponse.class);
        var pageType = TypeFactory.defaultInstance().constructParametricType(PageEnvelope.class, JsonNode.class);
        var page = (PageEnvelope<?>) fixtures.json().readValue(
                fixtures.loadResource("contracts/common/v1/page.valid.json"),
                pageType);

        assertEquals(CommonContractVersions.AUTH_PRINCIPAL_V1, principal.schemaVersion());
        assertEquals(CommonContractVersions.EVENT_ENVELOPE_V1, event.schemaVersion());
        assertEquals(CommonContractVersions.API_ERROR_V1, error.schemaVersion());
        assertEquals(CommonContractVersions.PAGE_V1, page.schemaVersion());
    }

    @Test
    void preservesActorTimeCorrelationAndSchemaVersionAcrossRoundTrip() throws Exception {
        var fixtures = CommonContractFixtures.standard();

        var json = fixtures.json().writeValueAsString(fixtures.authenticationEvent());
        var restored = fixtures.json().readValue(json, EventEnvelope.class);

        assertEquals(fixtures.authenticationEvent().actor(), restored.actor());
        assertEquals(fixtures.authenticationEvent().occurredAt(), restored.occurredAt());
        assertEquals(fixtures.authenticationEvent().correlationId(), restored.correlationId());
        assertEquals(fixtures.authenticationEvent().idempotencyKey(), restored.idempotencyKey());
        assertEquals(fixtures.authenticationEvent().schemaVersion(), restored.schemaVersion());
    }

    @Test
    void acceptsUnknownAdditionalFieldsForForwardCompatibility() throws Exception {
        var fixtures = CommonContractFixtures.standard();
        var json = fixtures.loadResource("contracts/common/v1/authentication-event.valid.json")
                .replace("\"payload\"", "\"futureField\":{\"ignored\":true},\"payload\"");

        var restored = fixtures.json().readValue(json, EventEnvelope.class);

        assertEquals(CommonContractVersions.EVENT_ENVELOPE_V1, restored.schemaVersion());
    }

    @Test
    void rejectsMissingRequiredActor() throws Exception {
        var fixtures = CommonContractFixtures.standard();
        var json = fixtures.loadResource("contracts/common/v1/authentication-event.missing-actor.json");

        assertThrows(JsonMappingException.class, () -> fixtures.json().readValue(json, EventEnvelope.class));
    }

    @Test
    void rejectsUnsupportedEnvelopeVersionWithoutSubstitution() throws Exception {
        var fixtures = CommonContractFixtures.standard();
        var json = fixtures.loadResource("contracts/common/v1/authentication-event.unsupported-version.json");

        var exception = assertThrows(
                JsonMappingException.class,
                () -> fixtures.json().readValue(json, EventEnvelope.class));

        assertInstanceOf(UnsupportedContractVersionException.class, exception.getCause());
    }

    @Test
    void propagatesCorrelationAndIdempotencyFromRequestContext() {
        var fixtures = CommonContractFixtures.standard();
        var context = fixtures.requestContext();

        var event = context.event(
                "identity.authentication.succeeded",
                fixtures.principal(),
                fixtures.clock().instant(),
                Map.of("authenticationEventId", "00000000-0000-4000-8000-000000000104"));

        assertEquals(context.correlationId(), event.correlationId());
        assertEquals(context.idempotencyKey(), event.idempotencyKey());
    }

    @Test
    void sharedFixturesContainNoCredentialsOrStrategyContent() throws Exception {
        var fixtures = CommonContractFixtures.standard();
        var combined = String.join(
                        "\n",
                        fixtures.loadResource("contracts/common/v1/principal.valid.json"),
                        fixtures.loadResource("contracts/common/v1/authentication-event.valid.json"),
                        fixtures.loadResource("contracts/common/v1/api-error.valid.json"),
                        fixtures.loadResource("contracts/common/v1/page.valid.json"))
                .toLowerCase();

        for (var forbidden : new String[] {"password", "access_token", "refresh_token", "secret", "strategy"}) {
            assertTrue(!combined.contains(forbidden), () -> "fixture contains forbidden content: " + forbidden);
        }
    }
}
