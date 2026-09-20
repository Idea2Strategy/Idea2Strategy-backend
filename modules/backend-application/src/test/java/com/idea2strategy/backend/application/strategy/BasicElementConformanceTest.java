package com.idea2strategy.backend.application.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.AllocationMode;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.BasicBlock;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.BasicBlockConnection;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.BasicBlockGroup;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.EvaluationMode;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.TradeContainer;
import com.idea2strategy.backend.domain.strategy.ElementCatalogVersion;
import com.idea2strategy.backend.domain.strategy.StrategyElementDefinition;
import com.idea2strategy.backend.domain.strategy.SupportedInstrument;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class BasicElementConformanceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID CATALOG_ID = UUID.fromString("25082500-0000-4000-8000-000000000001");
    private static final UUID INSTRUMENT_ID = UUID.fromString("25082500-0000-4000-8000-000000000002");
    private static Corpus corpus;
    private static BasicStrategyCatalog catalog;

    @BeforeAll
    static void loadContract() throws Exception {
        try (InputStream stream = BasicElementConformanceTest.class.getResourceAsStream(
                "/contracts/basic-element-conformance.v1.json")) {
            corpus = JSON.readValue(stream, Corpus.class);
        }
        catalog = catalogFrom(corpus);
    }

    @Test
    void consumesThePublishedVersionAndAllFourteenElements() {
        assertThat(corpus.schemaVersion()).isEqualTo("basic-element-conformance/v1");
        assertThat(corpus.catalogVersion()).isEqualTo("basic-elements:2026-08-25");
        assertThat(corpus.cases()).hasSize(14);
    }

    @TestFactory
    Stream<DynamicTest> acceptsEveryLiteralValidParameterSet() {
        return corpus.cases().stream().map(testCase -> DynamicTest.dynamicTest(testCase.elementCode(), () -> {
            var result = new BasicBlockAssemblyValidator().validate(assembly(testCase, testCase.validParameters()), catalog);
            assertThat(result.issues()).as(testCase.elementCode()).isEmpty();
        }));
    }

    @TestFactory
    Stream<DynamicTest> rejectsEveryLiteralInvalidParameterSetAtTheField() {
        return corpus.cases().stream().flatMap(testCase -> testCase.invalidParameters().stream().map(invalid ->
                DynamicTest.dynamicTest(testCase.elementCode() + ":" + invalid.name(), () -> {
                    var result = new BasicBlockAssemblyValidator().validate(assembly(testCase, invalid.parameters()), catalog);
                    String blockPath = testCase.elementCode().equals("BASIC_EQUAL_ALLOCATION_ORDER")
                            ? "groups[0].blocks[1]" : "groups[0].blocks[0]";
                    String changedField = changedField(testCase.validParameters(), invalid.parameters());
                    assertThat(result.issues()).as(testCase.elementCode() + ":" + invalid.name())
                            .anySatisfy(issue -> {
                                assertThat(issue.code()).isEqualTo(invalid.code());
                                assertThat(issue.location()).startsWith(blockPath + ".parameters");
                                if (!issue.location().equals(blockPath + ".parameters")) {
                                    assertThat(issue.location()).endsWith("." + changedField);
                                }
                            });
                })));
    }

    private static String changedField(Map<String, Object> valid, Map<String, Object> invalid) {
        return valid.entrySet().stream()
                .filter(entry -> !invalid.containsKey(entry.getKey()) || !entry.getValue().equals(invalid.get(entry.getKey())))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
    }

    private static BasicBlockAssembly assembly(Case testCase, Map<String, Object> parameters) {
        TradeContainer container = TradeContainer.valueOf(testCase.containers().getFirst());
        List<BasicBlock> blocks = new ArrayList<>();
        if (testCase.elementCode().equals("BASIC_EQUAL_ALLOCATION_ORDER")) {
            blocks.add(new BasicBlock("condition", "TEST_CONDITION", Map.of()));
        }
        blocks.add(new BasicBlock("target", testCase.elementCode(), parameters));
        if (!testCase.elementCode().equals("BASIC_EQUAL_ALLOCATION_ORDER")) {
            blocks.add(new BasicBlock("order", "BASIC_EQUAL_ALLOCATION_ORDER", terminalParameters()));
        }
        List<BasicBlockConnection> connections = new ArrayList<>();
        for (int index = 0; index < blocks.size() - 1; index++) {
            connections.add(new BasicBlockConnection(
                    blocks.get(index).id(), "passed", blocks.get(index + 1).id(), "passed"));
        }
        return new BasicBlockAssembly(CATALOG_ID, List.of(new BasicBlockGroup(
                "group", container, EvaluationMode.INDEPENDENT, AllocationMode.EQUAL,
                List.of(INSTRUMENT_ID), blocks, connections)));
    }

    private static Map<String, Object> terminalParameters() {
        return corpus.cases().stream()
                .filter(testCase -> testCase.elementCode().equals("BASIC_EQUAL_ALLOCATION_ORDER"))
                .findFirst().orElseThrow().validParameters();
    }

    private static BasicStrategyCatalog catalogFrom(Corpus corpus) throws Exception {
        List<StrategyElementDefinition> elements = new ArrayList<>();
        elements.add(element("TEST_CONDITION", "CONDITION", Map.of(), List.of("BUY", "SELL"), "TEST"));
        for (Case testCase : corpus.cases()) {
            Map<String, Object> properties = new LinkedHashMap<>();
            testCase.validParameters().forEach((name, value) -> properties.put(name, Map.of(
                    "type", value instanceof String ? "string" : value instanceof Number ? "number" : "object",
                    "minLength", value instanceof String ? 1 : 0)));
            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "required", testCase.validParameters().keySet(),
                    "properties", properties);
            elements.add(element(
                    testCase.elementCode(),
                    testCase.elementCode().equals("BASIC_EQUAL_ALLOCATION_ORDER") ? "ACTION"
                            : testCase.elementCode().equals("BASIC_SCHEDULE") ? "TRIGGER" : "CONDITION",
                    schema,
                    testCase.containers(),
                    testCase.operation()));
        }
        return new BasicStrategyCatalog(
                new ElementCatalogVersion(CATALOG_ID, "basic/v1", "basic-semantic/v1",
                        corpus.catalogVersion(), "alpaca-sip/v1", "a".repeat(64),
                        Instant.parse("2026-08-25T00:00:00Z"), null),
                elements,
                List.of(),
                List.of(new SupportedInstrument(INSTRUMENT_ID, "STOCK", "XNAS", "USD", "AAPL")));
    }

    private static StrategyElementDefinition element(
            String code, String kind, Map<String, Object> parameters, List<String> containers, String operation)
            throws Exception {
        return new StrategyElementDefinition(
                UUID.nameUUIDFromBytes(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                CATALOG_ID,
                code,
                kind,
                JSON.writeValueAsString(parameters),
                "{\"passed\":{\"type\":\"boolean\"}}",
                kind.equals("ACTION") ? "{}" : "{\"passed\":{\"type\":\"boolean\"}}",
                JSON.writeValueAsString(Map.of(
                        "terminal", kind.equals("ACTION"),
                        "containers", containers,
                        "runtime", Map.of("operation", operation))),
                "b".repeat(64));
    }

    record Corpus(String schemaVersion, String catalogVersion, List<Case> cases) {}
    record Case(
            String elementCode,
            List<String> containers,
            Map<String, Object> validParameters,
            List<InvalidParameters> invalidParameters,
            String operation,
            Map<String, Object> arguments,
            JsonNode trueInputs,
            JsonNode falseInputs,
            String expectedReviewKo) {}
    record InvalidParameters(String name, String code, Map<String, Object> parameters) {}
}
