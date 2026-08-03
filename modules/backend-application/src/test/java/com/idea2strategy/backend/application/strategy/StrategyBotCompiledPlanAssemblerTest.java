package com.idea2strategy.backend.application.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.domain.strategy.ElementCatalogVersion;
import com.idea2strategy.backend.domain.strategy.ImmutableStrategyRelease.ContractPlan;
import com.idea2strategy.backend.domain.strategy.ImmutableStrategyRelease.Flow;
import com.idea2strategy.backend.domain.strategy.StrategyElementDefinition;
import com.idea2strategy.backend.domain.strategy.StrategyFeatureDefinition;
import com.idea2strategy.backend.domain.strategy.SupportedInstrument;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The translations the published contract depends on, and the cases it must refuse rather than
 * publish something a consumer would act on.
 */
class StrategyBotCompiledPlanAssemblerTest {
    private static final UUID CATALOG_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID PARTITION_ID = UUID.fromString("41000000-0000-4000-8000-000000000001");
    private static final UUID FEATURE_ID = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final UUID AAPL = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID MSFT = UUID.fromString("60000000-0000-4000-8000-000000000002");
    private static final Instant RELEASED_AT = Instant.parse("2026-08-04T13:30:00Z");
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StrategyBotCompiledPlanAssembler assembler = new StrategyBotCompiledPlanAssembler();

    @Test
    void publishesTheElementCatalogRuntimeOperationsRatherThanItsElementCodes() {
        ContractPlan plan = assemble(planWith(buyFlow("buy", List.of(AAPL))));

        JsonNode steps = parse(plan.planDocument()).path("steps");
        assertThat(steps).hasSize(3);
        assertThat(steps.get(0).path("operation").asText()).isEqualTo("LOAD_FEATURE");
        assertThat(steps.get(0).path("arguments").path("resolution").asText()).isEqualTo("1m");
        assertThat(steps.get(1).path("arguments").path("threshold").asText()).isEqualTo("30");
        assertThat(steps.get(2).path("operation").asText()).isEqualTo("EMIT_ORDER_CANDIDATE");
        assertThat(steps.get(2).path("arguments").path("side").asText())
                .as("the trade container is what decides the order side")
                .isEqualTo("BUY");
    }

    /**
     * Two flows over different instruments requiring the same feature become one requirement, because
     * the contract forbids a duplicate feature-and-instruments key and the consumer warms one window
     * per instrument either way.
     */
    @Test
    void collectsOneWarmupRequirementPerFeatureAcrossEveryFlowThatNeedsIt() {
        ContractPlan plan = assemble(planWith(
                buyFlow("first", List.of(MSFT)), buyFlow("second", List.of(AAPL))));

        JsonNode features = parse(plan.planDocument()).path("requiredFeatures");
        assertThat(features).hasSize(1);
        assertThat(features.get(0).path("requirementId").asText()).isEqualTo("rsi-14-pt1m");
        assertThat(features.get(0).path("featureVersion").asText())
                .as("rsi:1.0.0 names the calculator; the contract carries only the version")
                .isEqualTo("1.0.0");
        assertThat(features.get(0).path("resolution").asText()).isEqualTo("PT1M");
        assertThat(features.get(0).path("requiredObservations").asInt())
                .as("a fifteen point window needs fourteen warm-up bars; the live bar is the fifteenth")
                .isEqualTo(14);
        List<String> instruments = features.get(0).path("instruments").valueStream()
                .map(JsonNode::asText).toList();
        assertThat(instruments).containsExactly(AAPL.toString(), MSFT.toString());
    }

    /**
     * The contract publishes one step sequence and one side for the whole plan, and both consumers
     * apply it to every flow. A buy rule and a sell rule flattened into that shape would trade the
     * wrong side on one of them, so the release is refused instead.
     */
    @Test
    void refusesAStrategyWhoseGroupsDoNotCompileToOneSequence() {
        assertThatThrownBy(() -> assemble(planWith(
                        buyFlow("buy", List.of(AAPL)), sellFlow("sell", List.of(AAPL)))))
                .isInstanceOf(ImmutableStrategyReleaseRejectedException.class)
                .hasMessageContaining("one execution sequence");
    }

    @Test
    void refusesAPlanThatDoesNotEndByEmittingAnOrderCandidate() {
        JsonNode plan = planWith(objectMapper.createObjectNode()
                .put("key", "buy")
                .put("container", "BUY")
                .<com.fasterxml.jackson.databind.node.ObjectNode>set(
                        "instrumentIds", objectMapper.createArrayNode().add(AAPL.toString()))
                .set("steps", objectMapper.createArrayNode()
                        .add(step(1, "BASIC_RSI_READ", "{\"resolution\":\"1m\"}"))));

        assertThatThrownBy(() -> assemble(plan))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must end with EMIT_ORDER_CANDIDATE");
    }

    /**
     * A placeholder the block never filled would otherwise be published as the literal
     * {@code $threshold}, and the consumer would fail to parse it as a decimal at evaluation time —
     * long after the user was told their bot started.
     */
    @Test
    void refusesABlockThatOmitsAParameterItsRuntimeArgumentNeeds() {
        JsonNode plan = planWith(objectMapper.createObjectNode()
                .put("key", "buy")
                .put("container", "BUY")
                .<com.fasterxml.jackson.databind.node.ObjectNode>set(
                        "instrumentIds", objectMapper.createArrayNode().add(AAPL.toString()))
                .set("steps", objectMapper.createArrayNode()
                        .add(step(1, "BASIC_RSI_READ", "{\"resolution\":\"1m\"}"))
                        .add(step(2, "BASIC_VALUE_COMPARE", "{\"operator\":\"LT\"}"))
                        .add(step(3, "BASIC_EQUAL_ALLOCATION_ORDER", "{}"))));

        assertThatThrownBy(() -> assemble(plan))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("needs parameter threshold");
    }

    /** A feature whose window is one point needs no history, which the contract cannot express. */
    @Test
    void refusesAFeatureThatDeclaresNoWarmupHistory() {
        assertThatThrownBy(() -> assemble(
                        planWith(buyFlow("buy", List.of(AAPL))),
                        feature("RSI_14", "rsi:1.0.0", "1m", 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("declares no warm-up history");
    }

    /**
     * The catalog's shorthand becomes the normalised ISO-8601 duration the consumer validates, and a
     * catalog that already publishes ISO-8601 passes through unchanged.
     */
    @Test
    void normalisesEveryResolutionTheCatalogCanPublish() {
        assertThat(resolutionOf("1m")).isEqualTo("PT1M");
        assertThat(resolutionOf("5m")).isEqualTo("PT5M");
        assertThat(resolutionOf("1h")).isEqualTo("PT1H");
        assertThat(resolutionOf("1d")).isEqualTo("PT24H");
        assertThat(resolutionOf("PT15M")).isEqualTo("PT15M");
    }

    /**
     * The checksum is a function of the fields, not of how they were serialised, so a consumer that
     * decodes the document and recomputes it agrees. Changing any published field must change it.
     */
    @Test
    void checksumsThePlanByItsFieldsSoAConsumerCanRecomputeIt() {
        ContractPlan one = assemble(planWith(buyFlow("buy", List.of(AAPL))));
        ContractPlan same = assemble(planWith(buyFlow("buy", List.of(AAPL))));
        ContractPlan other = assemble(planWith(buyFlow("buy", List.of(AAPL, MSFT))));

        assertThat(one.planChecksum()).isEqualTo(same.planChecksum());
        assertThat(one.planChecksum()).isNotEqualTo(other.planChecksum());
        assertThat(parse(one.planDocument()).path("planChecksum").asText()).isEqualTo(one.planChecksum());
    }

    private String resolutionOf(String catalogResolution) {
        ContractPlan plan = assemble(
                planWith(buyFlow("buy", List.of(AAPL))),
                feature("RSI_14", "rsi:1.0.0", catalogResolution, 15));
        return parse(plan.planDocument()).path("requiredFeatures").get(0).path("resolution").asText();
    }

    private ContractPlan assemble(JsonNode planRoot) {
        return assemble(planRoot, feature("RSI_14", "rsi:1.0.0", "1m", 15));
    }

    private ContractPlan assemble(JsonNode planRoot, StrategyFeatureDefinition feature) {
        List<Flow> flows = new java.util.ArrayList<>();
        int order = 0;
        for (JsonNode flowNode : planRoot.path("flows")) {
            List<UUID> instruments = new java.util.ArrayList<>();
            flowNode.path("instrumentIds").forEach(node -> instruments.add(UUID.fromString(node.asText())));
            flows.add(new Flow(
                    UUID.nameUUIDFromBytes(flowNode.path("key").asText().getBytes(
                            java.nio.charset.StandardCharsets.UTF_8)),
                    flowNode.path("key").asText(), CATALOG_ID, UUID.randomUUID(), "{}", "{}",
                    HASH_A, HASH_B, HASH_A, instruments, List.of(), order++));
        }
        return assembler.assemble(
                planRoot, catalog(feature), PARTITION_ID, 10_000, new BigDecimal("100000.00"), flows,
                HASH_A, HASH_B, "basic-launch-snapshot.v1", RELEASED_AT);
    }

    private JsonNode planWith(JsonNode... flows) {
        var root = objectMapper.createObjectNode();
        root.put("schemaVersion", "basic-compiled-plan.v1");
        root.put("compilerVersion", "basic-compiler:1.0.0");
        root.put("requiredFeatureSetHash", HASH_A);
        var array = root.putArray("flows");
        for (JsonNode flow : flows) {
            array.add(flow);
        }
        return root;
    }

    private JsonNode buyFlow(String key, List<UUID> instruments) {
        return flow(key, "BUY", instruments);
    }

    private JsonNode sellFlow(String key, List<UUID> instruments) {
        return flow(key, "SELL", instruments);
    }

    private JsonNode flow(String key, String container, List<UUID> instruments) {
        var node = objectMapper.createObjectNode();
        node.put("key", key);
        node.put("container", container);
        var ids = node.putArray("instrumentIds");
        instruments.stream().map(UUID::toString).sorted().forEach(ids::add);
        node.set("steps", objectMapper.createArrayNode()
                .add(step(1, "BASIC_RSI_READ", "{\"resolution\":\"1m\"}"))
                .add(step(2, "BASIC_VALUE_COMPARE", "{\"operator\":\"LT\",\"threshold\":\"30\"}"))
                .add(step(3, "BASIC_EQUAL_ALLOCATION_ORDER", "{}")));
        return node;
    }

    private JsonNode step(int sequence, String elementCode, String parameters) {
        var node = objectMapper.createObjectNode();
        node.put("sequence", sequence);
        node.put("elementCode", elementCode);
        node.set("parameters", parse(parameters));
        return node;
    }

    private BasicStrategyCatalog catalog(StrategyFeatureDefinition feature) {
        return new BasicStrategyCatalog(
                new ElementCatalogVersion(
                        CATALOG_ID, "basic/v1", "basic-semantic/v1", "basic-elements:2026-08-04",
                        "alpaca-sip/v1", HASH_A, RELEASED_AT.minusSeconds(3600), null),
                List.of(
                        element("BASIC_RSI_READ", "LOAD_FEATURE",
                                "{\"feature\":\"RSI_14\",\"resolution\":\"$resolution\"}", "[\"RSI_14\"]"),
                        element("BASIC_VALUE_COMPARE", "COMPARE",
                                "{\"operator\":\"$operator\",\"threshold\":\"$threshold\"}", "[]"),
                        element("BASIC_EQUAL_ALLOCATION_ORDER", "EMIT_ORDER_CANDIDATE",
                                "{\"allocation\":\"EQUAL\",\"orderType\":\"MARKET\",\"side\":\"$container\"}",
                                "[]")),
                List.of(feature),
                List.of(
                        new SupportedInstrument(AAPL, "STOCK", "XNAS", "USD", "AAPL"),
                        new SupportedInstrument(MSFT, "STOCK", "XNAS", "USD", "MSFT")));
    }

    private static StrategyFeatureDefinition feature(
            String code, String calculatorVersion, String resolution, int historyPoints) {
        return new StrategyFeatureDefinition(
                FEATURE_ID, CATALOG_ID, code, calculatorVersion, resolution, "{\"period\":14}",
                "NUMBER", historyPoints, HASH_B);
    }

    private static StrategyElementDefinition element(
            String code, String operation, String arguments, String features) {
        return new StrategyElementDefinition(
                UUID.nameUUIDFromBytes(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)), CATALOG_ID,
                code, "BLOCK", "{}", "{}", "{}",
                "{\"containers\":[\"BUY\",\"SELL\"],\"runtime\":{\"operation\":\"" + operation + "\","
                        + "\"arguments\":" + arguments + "},"
                        + "\"backtest\":{\"supported\":true,\"feeds\":[],\"features\":" + features + "}}",
                HASH_A);
    }

    private JsonNode parse(String document) {
        try {
            return objectMapper.readTree(document);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
