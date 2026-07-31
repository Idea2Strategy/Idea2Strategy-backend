package com.idea2strategy.backend.messaging.competition.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.messaging.performance.contract.LivePerformanceContractFixtures;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RoomPerformanceContractResourceTest {

    private static final String ROOT = "/contracts/room-performance/v1/";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void scheduleResourcesCoverPublicPrivateAndOfficialRooms() throws IOException {
        JsonNode publicRoom = read("public-live-room-schedule.valid.json");
        JsonNode privateRoom = read("private-live-room-schedule.valid.json");
        JsonNode officialRoom = read("official-backtest-room-schedule.valid.json");

        assertEquals("PUBLIC", publicRoom.required("accessType").textValue());
        assertEquals("PRIVATE", privateRoom.required("accessType").textValue());
        assertEquals("PLATFORM", officialRoom.required("organizerType").textValue());
        assertEquals("BACKTEST", officialRoom.required("competitionType").textValue());
        assertEquals(RoomContractFixtures.CONTRACT_VERSION, publicRoom.required("contractVersion").textValue());
        assertTrue(publicRoom.required("scheduleVersion").textValue().startsWith("room-schedule."));
    }

    @Test
    void commandResourceContainsEveryLifecycleAndPostRoomCommand() throws IOException {
        JsonNode commands = read("evaluation-commands.valid.json");
        Set<String> types = new HashSet<>();
        commands.forEach(command -> types.add(command.required("type").textValue()));

        assertEquals(Set.of(
            "INITIALIZE_EVALUATION",
            "START_EVALUATION",
            "END_EVALUATION",
            "CONTINUE_AS_PRIVATE_BOT",
            "STOP_BOT"
        ), types);
        commands.forEach(command -> {
            assertEquals(RoomContractFixtures.CONTRACT_VERSION, command.required("contractVersion").textValue());
            assertTrue(command.required("idempotencyKey").textValue().matches("sha256:[0-9a-f]{64}"));
        });
    }

    @Test
    void performanceResourcesAreAnonymousAndStateObjectiveRejections() throws IOException {
        JsonNode valid = read("live-performance-input.valid.json");
        JsonNode afterEnd = read("live-performance-input.after-end.json");
        JsonNode backtest = read("live-performance-input.backtest-rejected.json");

        var expected = LivePerformanceContractFixtures.liveFillAt(
            RoomContractFixtures.publicLiveRoomSchedule(),
            RoomContractFixtures.publicLiveRoomSchedule().evaluationStartsAt().plusSeconds(300)
        ).toWireMap();
        assertEquals(expected.get("anonymousBotId"), valid.required("anonymousBotId").textValue());
        assertEquals(expected.get("evidenceHash"), valid.required("evidenceHash").textValue());
        assertEquals("AT_OR_AFTER_EVALUATION_END", afterEnd.required("expectedDecision").textValue());
        assertEquals("BACKTEST_SOURCE_NOT_ALLOWED", backtest.required("expectedDecision").textValue());

        assertNoUserIdentity(valid);
        assertNoUserIdentity(afterEnd);
        assertNoUserIdentity(backtest);
    }

    private JsonNode read(String resourceName) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(ROOT + resourceName)) {
            assertNotNull(input, "Missing contract resource " + resourceName);
            return OBJECT_MAPPER.readTree(input);
        }
    }

    private void assertNoUserIdentity(JsonNode node) {
        Iterator<String> fieldNames = node.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next().toLowerCase(Locale.ROOT);
            assertFalse(field.contains("user") || field.contains("account") || field.contains("owner"));
        }
        node.elements().forEachRemaining(this::assertNoUserIdentity);
    }
}
