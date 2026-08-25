package com.idea2strategy.backend.application.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.domain.strategy.ElementCatalogVersion;
import com.idea2strategy.backend.domain.strategy.StrategyDocument;
import com.idea2strategy.backend.domain.strategy.StrategyElementDefinition;
import com.idea2strategy.backend.domain.strategy.SupportedInstrument;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BasicExecutionPlanCompletionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID CATALOG_ID = UUID.fromString("0f5a0000-0000-4000-8000-000000000001");
    private static final UUID AAPL = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID MSFT = UUID.fromString("20000000-0000-4000-8000-000000000001");

    @Test
    void sortsInstrumentSpecificFlowsAndCarriesAllocationGroupAndCap() throws Exception {
        String semantic = """
                {"catalogId":"0f5a0000-0000-4000-8000-000000000001","groups":[
                  {"id":"buy:msft","allocationGroupId":"buy","container":"BUY","evaluationMode":"INDEPENDENT","allocationMode":"EQUAL",
                   "instrumentIds":["20000000-0000-4000-8000-000000000001"],
                   "blocks":[{"id":"condition","elementCode":"TEST_CONDITION","parameters":{}},{"id":"order","elementCode":"BASIC_EQUAL_ALLOCATION_ORDER","parameters":{"orderPercent":"25","maxPositionPercent":"40","executionMode":"1회만","waitMode":"조건 재충족","waitInterval":"1","maxExecutions":"1"}}],
                   "connections":[{"fromBlockId":"condition","outputPort":"passed","toBlockId":"order","inputPort":"passed"}]},
                  {"id":"buy:aapl","allocationGroupId":"buy","container":"BUY","evaluationMode":"INDEPENDENT","allocationMode":"EQUAL",
                   "instrumentIds":["10000000-0000-4000-8000-000000000001"],
                   "blocks":[{"id":"condition","elementCode":"TEST_CONDITION","parameters":{}},{"id":"order","elementCode":"BASIC_EQUAL_ALLOCATION_ORDER","parameters":{"orderPercent":"25","maxPositionPercent":"25","executionMode":"1회만","waitMode":"조건 재충족","waitInterval":"1","maxExecutions":"1"}}],
                   "connections":[{"fromBlockId":"condition","outputPort":"passed","toBlockId":"order","inputPort":"passed"}]}
                ]}
                """;
        String canonical = StrategyDocumentJson.canonicalize(semantic);
        var document = new StrategyDocument(
                UUID.randomUUID(), canonical, "{}", "basic-semantic/v1", "basic-presentation/v1",
                StrategyDocumentJson.sha256(canonical), StrategyDocumentJson.sha256("{}"), 1,
                Instant.parse("2026-08-25T00:00:00Z"), Instant.parse("2026-08-25T00:00:00Z"));

        var plan = new BasicExecutionPlanCompiler().compile(
                UUID.randomUUID(), document, catalog(), Instant.parse("2026-08-25T00:00:01Z"));
        JsonNode flows = JSON.readTree(plan.planDocument()).path("flows");

        assertThat(flows).extracting(flow -> flow.path("key").asText())
                .containsExactly("buy:aapl", "buy:msft");
        assertThat(flows).allSatisfy(flow -> assertThat(flow.path("allocationGroupId").asText()).isEqualTo("buy"));
        assertThat(flows.get(0).path("steps").get(1).path("parameters").path("maxPositionPercent").asText())
                .isEqualTo("25");
        assertThat(flows.get(1).path("steps").get(1).path("parameters").path("maxPositionPercent").asText())
                .isEqualTo("40");
    }

    private static BasicStrategyCatalog catalog() {
        String conditionContract = "{\"terminal\":false,\"containers\":[\"BUY\",\"SELL\"],\"runtime\":{\"operation\":\"TEST\",\"arguments\":{}},\"backtest\":{\"supported\":true,\"feeds\":[],\"features\":[]}}";
        String orderSchema = "{\"type\":\"object\",\"required\":[\"orderPercent\",\"maxPositionPercent\",\"executionMode\",\"waitMode\",\"waitInterval\",\"maxExecutions\"],\"properties\":{\"orderPercent\":{\"type\":\"string\"},\"maxPositionPercent\":{\"type\":\"string\"},\"executionMode\":{\"type\":\"string\"},\"waitMode\":{\"type\":\"string\"},\"waitInterval\":{\"type\":\"string\"},\"maxExecutions\":{\"type\":\"string\"}}}";
        String orderContract = "{\"terminal\":true,\"containers\":[\"BUY\",\"SELL\"],\"runtime\":{\"operation\":\"EMIT_ORDER_CANDIDATE\",\"arguments\":{\"side\":\"$container\",\"orderPercent\":\"$orderPercent\",\"maxPositionPercent\":\"$maxPositionPercent\",\"executionMode\":\"$executionMode\",\"waitMode\":\"$waitMode\",\"waitInterval\":\"$waitInterval\",\"maxExecutions\":\"$maxExecutions\"}},\"backtest\":{\"supported\":true,\"feeds\":[],\"features\":[]}}";
        return new BasicStrategyCatalog(
                new ElementCatalogVersion(CATALOG_ID, "basic/v1", "basic-semantic/v1",
                        "basic-elements:2026-08-25", "alpaca-sip/v1", "a".repeat(64),
                        Instant.parse("2026-08-25T00:00:00Z"), null),
                List.of(
                        element("TEST_CONDITION", "CONDITION", "{}", conditionContract),
                        element("BASIC_EQUAL_ALLOCATION_ORDER", "ACTION", orderSchema, orderContract)),
                List.of(),
                List.of(
                        new SupportedInstrument(AAPL, "STOCK", "XNAS", "USD", "AAPL"),
                        new SupportedInstrument(MSFT, "STOCK", "XNAS", "USD", "MSFT")));
    }

    private static StrategyElementDefinition element(String code, String kind, String schema, String contract) {
        return new StrategyElementDefinition(
                UUID.nameUUIDFromBytes(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)), CATALOG_ID,
                code, kind, schema, "{\"passed\":{\"type\":\"boolean\"}}",
                kind.equals("ACTION") ? "{}" : "{\"passed\":{\"type\":\"boolean\"}}",
                contract, "b".repeat(64));
    }
}
