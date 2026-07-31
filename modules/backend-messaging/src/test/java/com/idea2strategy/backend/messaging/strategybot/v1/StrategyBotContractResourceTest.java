package com.idea2strategy.backend.messaging.strategybot.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(plan.toString().contains("EMIT_ORDER_CANDIDATE"));
        assertFalse(plan.toString().contains("\"operation\":\"EMIT_ORDER\""));
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
