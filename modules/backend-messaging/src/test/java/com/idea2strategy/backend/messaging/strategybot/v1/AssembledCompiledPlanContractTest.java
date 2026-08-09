package com.idea2strategy.backend.messaging.strategybot.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.strategy.BasicStrategyCatalog;
import com.idea2strategy.backend.application.strategy.StrategyBotCompiledPlanAssembler;
import com.idea2strategy.backend.domain.strategy.ElementCatalogVersion;
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
 * The document the release assembler publishes, checked against the contract types themselves.
 *
 * <p>Root #190 gave the {@code strategy-bot.v1} compiled plan a producer. This is the test that keeps
 * the producer honest: the assembled document is bound through the same records every field rule lives
 * in, and its checksum is recomputed by an implementation that shares no code with the assembler's. A
 * disagreement between the two is exactly the class of defect that would otherwise surface as a bot
 * refusing to start in production, because the consumer recomputes the checksum the same way.
 */
class AssembledCompiledPlanContractTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final UUID CATALOG_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID PARTITION_ID = UUID.fromString("41000000-0000-4000-8000-000000000001");
    private static final UUID FEATURE_ID = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final UUID FLOW_ID = UUID.fromString("42000000-0000-4000-8000-000000000001");
    private static final UUID PLAN_ID = UUID.fromString("43000000-0000-4000-8000-000000000001");
    private static final UUID AAPL = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Test
    void theAssembledPlanSatisfiesEveryContractFieldRule() throws Exception {
        var plan = OBJECT_MAPPER.treeToValue(
                OBJECT_MAPPER.readTree(assembled()), StrategyBotContractFixtures.BasicCompiledPlan.class);

        assertThat(plan.contractVersion()).isEqualTo(StrategyBotContractFixtures.CONTRACT_VERSION);
        assertThat(plan.schemaVersion())
                .isEqualTo(StrategyBotContractFixtures.MULTI_CONTAINER_COMPILED_PLAN_SCHEMA_VERSION);
        assertThat(plan.executionSnapshot().immutableStrategyVersion().snapshotHash())
                .isEqualTo("sha256:" + HASH_B);
        // The chain lives on the container now, and the plan states none of its own.
        assertThat(plan.steps()).isNull();
        assertThat(plan.executionSnapshot().partitions().getFirst().flows())
                .singleElement()
                .satisfies(flow -> assertThat(flow.steps()).hasSize(3));
        assertThat(plan.requiredFeatures()).singleElement().satisfies(feature -> {
            assertThat(feature.featureId()).isEqualTo(FEATURE_ID.toString());
            assertThat(feature.resolution()).isEqualTo("PT30M");
            assertThat(feature.requiredObservations()).isEqualTo(14);
        });
    }

    @Test
    void theContractTypesRederiveTheChecksumTheAssemblerPublished() throws Exception {
        var plan = OBJECT_MAPPER.treeToValue(
                OBJECT_MAPPER.readTree(assembled()), StrategyBotContractFixtures.BasicCompiledPlan.class);

        assertThat(StrategyBotContractFixtures.calculatePlanChecksum(plan))
                .isEqualTo(plan.planChecksum());
    }

    /** No plan may carry anything the Pro or direct-order surface would imply. */
    @Test
    void theAssembledPlanCarriesNoForbiddenField() throws Exception {
        assertThat(assembled())
                .doesNotContain("proNodes")
                .doesNotContain("userCode")
                .doesNotContain("externalData")
                .doesNotContain("directOrder");
    }

    /**
     * The amount as the public release API accepts it, not as the contract spells it.
     *
     * <p>This test suite used to pass {@code 100000.00000000} and so could never have caught backend
     * #255: the release surface takes a JSON number, {@code 100000} arrived scale-less, the plan
     * carried it verbatim, and the deployed Backtest runtime rejected it against
     * {@code ^-?[0-9]{1,16}\.[0-9]{8}$} before simulation. Every case below now starts from the
     * spelling a caller actually sends.
     */
    @Test
    void aScaleLessReleaseAmountIsPublishedAtTheContractsFixedScale() throws Exception {
        String document = assembled();

        assertThat(document).contains("\"initialCashAmount\":\"100000.00000000\"");
        assertThat(document).doesNotContain("\"initialCashAmount\":\"100000\"");

        var plan = OBJECT_MAPPER.treeToValue(
                OBJECT_MAPPER.readTree(document), StrategyBotContractFixtures.BasicCompiledPlan.class);
        assertThat(plan.executionSnapshot().initialCashAmount()).isEqualTo("100000.00000000");
        // The checksum reads the field back out of the document, so it has to be a function of the
        // normalized spelling rather than of whatever the caller wrote.
        assertThat(StrategyBotContractFixtures.calculatePlanChecksum(plan)).isEqualTo(plan.planChecksum());
    }

    /** Trailing zeros a caller writes are the same amount, so they must produce the same plan. */
    @Test
    void everySpellingOfOneAmountProducesOneChecksum() throws Exception {
        String scaleLess = assembledWith(new BigDecimal("100000"));
        String alreadyScaled = assembledWith(new BigDecimal("100000.00000000"));
        String overScaled = assembledWith(new BigDecimal("100000.000000000000"));

        assertThat(alreadyScaled).isEqualTo(scaleLess);
        assertThat(overScaled).isEqualTo(scaleLess);
    }

    /** Precision the contract cannot carry is a caller error, never something to round away. */
    @Test
    void anAmountFinerThanTheContractScaleIsRefused() {
        assertThatThrownBy(() -> assembledWith(new BigDecimal("100000.000000005")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more precision than");
    }

    private static String assembled() throws Exception {
        return assembledWith(new BigDecimal("100000"));
    }

    private static String assembledWith(BigDecimal initialCashAmount) throws Exception {
        var flow = new Flow(
                FLOW_ID, "buy", CATALOG_ID, PLAN_ID, "{}", "{}", HASH_A, HASH_B, HASH_A,
                List.of(AAPL), List.of(), 0);
        return new StrategyBotCompiledPlanAssembler()
                .assemble(
                        OBJECT_MAPPER.readTree(compiledPlan()),
                        catalog(),
                        PARTITION_ID,
                        10_000,
                        initialCashAmount,
                        List.of(flow),
                        HASH_A,
                        HASH_B,
                        "basic-launch-snapshot.v1",
                        Instant.parse("2026-08-04T13:30:00Z"))
                .planDocument();
    }

    private static String compiledPlan() {
        return "{\"schemaVersion\":\"basic-compiled-plan.v1\",\"compilerVersion\":\"basic-compiler:1.0.0\","
                + "\"requiredFeatureSetHash\":\"" + HASH_A + "\",\"flows\":[{"
                + "\"key\":\"buy\",\"container\":\"BUY\",\"instrumentIds\":[\"" + AAPL + "\"],\"steps\":["
                + "{\"sequence\":1,\"elementCode\":\"BASIC_RSI_READ\",\"parameters\":{\"resolution\":\"30m\"}},"
                + "{\"sequence\":2,\"elementCode\":\"BASIC_VALUE_COMPARE\","
                + "\"parameters\":{\"operator\":\"LT\",\"threshold\":\"30\"}},"
                + "{\"sequence\":3,\"elementCode\":\"BASIC_EQUAL_ALLOCATION_ORDER\",\"parameters\":{}}]}]}";
    }

    private static BasicStrategyCatalog catalog() {
        return new BasicStrategyCatalog(
                new ElementCatalogVersion(
                        CATALOG_ID, "basic/v1", "basic-semantic/v1", "basic-elements:2026-08-04",
                        "alpaca-sip/v1", HASH_A, Instant.parse("2026-08-04T00:00:00Z"), null),
                List.of(
                        element("BASIC_RSI_READ", "LOAD_FEATURE",
                                "{\"feature\":\"RSI_14\",\"resolution\":\"$resolution\"}", "[\"RSI_14\"]"),
                        element("BASIC_VALUE_COMPARE", "COMPARE",
                                "{\"operator\":\"$operator\",\"threshold\":\"$threshold\"}", "[]"),
                        element("BASIC_EQUAL_ALLOCATION_ORDER", "EMIT_ORDER_CANDIDATE",
                                "{\"allocation\":\"EQUAL\",\"orderType\":\"MARKET\",\"side\":\"$container\"}",
                                "[]")),
                List.of(new StrategyFeatureDefinition(
                        FEATURE_ID, CATALOG_ID, "RSI_14", "rsi:1.0.0", "30m", "{\"period\":14}",
                        "NUMBER", 15, HASH_B)),
                List.of(new SupportedInstrument(AAPL, "STOCK", "XNAS", "USD", "AAPL")));
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
}
