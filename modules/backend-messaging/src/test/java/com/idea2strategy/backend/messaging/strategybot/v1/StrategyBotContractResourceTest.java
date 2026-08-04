package com.idea2strategy.backend.messaging.strategybot.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StrategyBotContractResourceTest {

    private static final String ROOT = "/contracts/strategy-bot/v1/";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "proNodes",
            "userCode",
            "externalData",
            "directOrder");

    @Test
    void validConsumerFixturesMatchTheDeterministicJavaFixture() throws IOException {
        var fixtures = StrategyBotContractFixtures.standard();
        var plan = read("basic-compiled-plan.valid.json");
        var run = read("bot-run-command.valid.json");
        var stop = read("bot-stop-command.valid.json");
        var backtest = read("official-backtest-request.valid.json");

        var resourcePlan = OBJECT_MAPPER.treeToValue(
                plan,
                StrategyBotContractFixtures.BasicCompiledPlan.class);
        var resourceRun = OBJECT_MAPPER.treeToValue(
                run,
                StrategyBotContractFixtures.BotRunCommand.class);
        var resourceStop = OBJECT_MAPPER.treeToValue(
                stop,
                StrategyBotContractFixtures.BotStopCommand.class);
        var resourceBacktest = OBJECT_MAPPER.treeToValue(
                backtest,
                StrategyBotContractFixtures.OfficialBacktestRequest.class);

        assertEquals(fixtures.compiledPlan(), resourcePlan);
        assertEquals(fixtures.runCommand(), resourceRun);
        assertEquals(fixtures.stopCommand(), resourceStop);
        assertEquals(fixtures.officialBacktestRequest(), resourceBacktest);
        assertEquals(resourcePlan.planChecksum(), StrategyBotContractFixtures.calculatePlanChecksum(resourcePlan));

        assertNoForbiddenFields(plan);
        assertNoForbiddenFields(run);
        assertNoForbiddenFields(stop);
        assertNoForbiddenFields(backtest);
        assertEquals("BASIC", plan.required("executionSnapshot").required("mode").textValue());
        var requiredFeature = plan.required("requiredFeatures").required(0);
        assertEquals("00000000-0000-4000-8000-000000000401", requiredFeature.required("featureId").textValue());
        assertEquals("1.0.0", requiredFeature.required("featureVersion").textValue());
        assertEquals("PT1M", requiredFeature.required("resolution").textValue());
        assertEquals(14, requiredFeature.required("requiredObservations").intValue());
        assertEquals(
                "00000000-0000-4000-8000-000000000301",
                requiredFeature.required("instruments").required(0).textValue());
        assertTrue(plan.toString().contains("EMIT_ORDER_CANDIDATE"));
        assertFalse(plan.toString().contains("\"operation\":\"EMIT_ORDER\""));
    }

    /**
     * C93: a bot a room schedule bounds publishes both ends of its evaluation window.
     *
     * <p>Pinned as its own fixture because both shapes are on the wire — a personal bot has no end to
     * publish, since its window closes when its owner stops it. The end is part of the operation key,
     * so a room whose schedule moved produces a different command rather than one the consumer would
     * recognise as already handled.
     */
    @Test
    void suppliesTheRoomBoundedRunCommandWithBothEndsOfItsWindow() throws IOException {
        var room = read("bot-run-command.room.valid.json");

        var resourceRoomRun = OBJECT_MAPPER.treeToValue(
                room, StrategyBotContractFixtures.BotRunCommand.class);

        assertEquals(StrategyBotContractFixtures.roomRunCommand(), resourceRoomRun);
        assertEquals("2026-08-10T20:00:00Z", resourceRoomRun.executionEligibleUntil());
        assertNotEquals(
                StrategyBotContractFixtures.standard().runCommand().metadata().idempotencyKey(),
                resourceRoomRun.metadata().idempotencyKey(),
                "the window's end is part of the operation the key identifies");
        assertNoForbiddenFields(room);
    }

    @Test
    void suppliesVersionMismatchExamplesForTradingAndBacktestConsumers() throws IOException {
        var tradingMismatch = read("basic-compiled-plan.unsupported-version.json");
        var backtestMismatch = read("official-backtest-request.unsupported-version.json");

        assertEquals("strategy-bot.v999", tradingMismatch.required("contractVersion").textValue());
        assertEquals(
                "strategy-bot.v999",
                backtestMismatch.required("metadata").required("contractVersion").textValue());
        assertEquals(
                "UNSUPPORTED_CONTRACT_VERSION",
                tradingMismatch.required("rejectionReason").textValue());
        assertEquals(
                "UNSUPPORTED_CONTRACT_VERSION",
                backtestMismatch.required("rejectionReason").textValue());
    }

    private JsonNode read(String resourceName) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(ROOT + resourceName)) {
            assertNotNull(input, "Missing contract resource " + resourceName);
            return OBJECT_MAPPER.readTree(input);
        }
    }

    private void assertNoForbiddenFields(JsonNode node) {
        Iterator<String> fieldNames = node.fieldNames();
        while (fieldNames.hasNext()) {
            assertFalse(FORBIDDEN_FIELDS.contains(fieldNames.next()));
        }
        node.elements().forEachRemaining(this::assertNoForbiddenFields);
    }
}
