package com.idea2strategy.backend.application.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.AllocationMode;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.BasicBlock;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.BasicBlockConnection;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.BasicBlockGroup;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.EvaluationMode;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.TradeContainer;
import com.idea2strategy.backend.domain.strategy.ElementCatalogVersion;
import com.idea2strategy.backend.domain.strategy.StrategyElementDefinition;
import com.idea2strategy.backend.domain.strategy.SupportedInstrument;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BasicNaturalLanguageTranslatorTest {
    private static final UUID CATALOG_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID AAPL_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");

    private final BasicNaturalLanguageTranslator translator = new BasicNaturalLanguageTranslator();

    @Test
    void translatesEachBlockAndJoinsTheGroupWithoutChangingValues() {
        var review = translator.translate(validAssembly(), catalog(
                element("MARKET_OPEN", "{}", "{}", "{\"signal\":{\"type\":\"BOOLEAN\"}}",
                        contract("정규장이 열리면")),
                element("RSI", rsiParameters(), "{\"input\":{\"type\":\"BOOLEAN\"}}",
                        "{\"result\":{\"type\":\"BOOLEAN\"}}", contract("기간 {period}의 RSI가 {threshold} 이상이면")),
                element("BUY_ORDER", budgetParameters(), "{\"input\":{\"type\":\"BOOLEAN\"}}", "{}",
                        contract("가용 예산의 {budgetPercent}%로 매수 주문을 제출합니다."))));

        assertThat(review.translatable()).isTrue();
        assertThat(review.issues()).isEmpty();
        assertThat(review.groups()).singleElement().satisfies(group -> {
            assertThat(group.blocks())
                    .extracting(BasicNaturalLanguageReview.BlockReview::text)
                    .containsExactly(
                            "정규장이 열리면",
                            "기간 14의 RSI가 30.50 이상이면",
                            "가용 예산의 25%로 매수 주문을 제출합니다.");
            assertThat(group.sentence()).isEqualTo(
                    "정규장이 열리면 기간 14의 RSI가 30.50 이상이면 가용 예산의 25%로 매수 주문을 제출합니다.");
        });
        assertThat(translator.translate(validAssembly(), catalog(
                        element("MARKET_OPEN", "{}", "{}", "{\"signal\":{\"type\":\"BOOLEAN\"}}",
                                contract("정규장이 열리면")),
                        element("RSI", rsiParameters(), "{\"input\":{\"type\":\"BOOLEAN\"}}",
                                "{\"result\":{\"type\":\"BOOLEAN\"}}", contract("기간 {period}의 RSI가 {threshold} 이상이면")),
                        element("BUY_ORDER", budgetParameters(), "{\"input\":{\"type\":\"BOOLEAN\"}}", "{}",
                                contract("가용 예산의 {budgetPercent}%로 매수 주문을 제출합니다."))))
                .groups().getFirst().sentence()).isEqualTo(review.groups().getFirst().sentence());
    }

    @Test
    void returnsAssemblyIssuesWithoutProducingPartialReviewText() {
        var invalid = new BasicBlockAssembly(UUID.randomUUID(), validAssembly().groups());

        var review = translator.translate(invalid, catalog(
                element("MARKET_OPEN", "{}", "{}", "{\"signal\":{\"type\":\"BOOLEAN\"}}",
                        contract("정규장이 열리면")),
                element("RSI", rsiParameters(), "{\"input\":{\"type\":\"BOOLEAN\"}}",
                        "{\"result\":{\"type\":\"BOOLEAN\"}}", contract("기간 {period}의 RSI가 {threshold} 이상이면")),
                element("BUY_ORDER", budgetParameters(), "{\"input\":{\"type\":\"BOOLEAN\"}}", "{}",
                        contract("가용 예산의 {budgetPercent}%로 매수 주문을 제출합니다."))));

        assertThat(review.translatable()).isFalse();
        assertThat(review.groups()).isEmpty();
        assertThat(review.issues())
                .extracting(BasicBlockAssemblyIssue::code, BasicBlockAssemblyIssue::location)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("CATALOG_VERSION_MISMATCH", "catalogId"));
    }

    @Test
    void rejectsMissingUnknownOrValueDroppingTemplatesAtExactBlockLocations() {
        var review = translator.translate(validAssembly(), catalog(
                element("MARKET_OPEN", "{}", "{}", "{\"signal\":{\"type\":\"BOOLEAN\"}}",
                        "{\"containers\":[\"BUY\"]}"),
                element("RSI", rsiParameters(), "{\"input\":{\"type\":\"BOOLEAN\"}}",
                        "{\"result\":{\"type\":\"BOOLEAN\"}}", contract("RSI {unknown}")),
                element("BUY_ORDER", budgetParameters(), "{\"input\":{\"type\":\"BOOLEAN\"}}", "{}",
                        contract("매수 주문을 제출합니다."))));

        assertThat(review.translatable()).isFalse();
        assertThat(review.groups()).isEmpty();
        assertThat(review.issues())
                .extracting(BasicBlockAssemblyIssue::code, BasicBlockAssemblyIssue::location)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("REVIEW_TEMPLATE_MISSING", "groups[0].blocks[0].elementCode"),
                        org.assertj.core.groups.Tuple.tuple("REVIEW_PLACEHOLDER_UNKNOWN", "groups[0].blocks[1].parameters.unknown"),
                        org.assertj.core.groups.Tuple.tuple("REVIEW_VALUE_OMITTED", "groups[0].blocks[1].parameters.period"),
                        org.assertj.core.groups.Tuple.tuple("REVIEW_VALUE_OMITTED", "groups[0].blocks[1].parameters.threshold"),
                        org.assertj.core.groups.Tuple.tuple("REVIEW_VALUE_OMITTED", "groups[0].blocks[2].parameters.budgetPercent"));
    }

    @Test
    void rendersListsAndObjectsInStableReviewOrder() {
        var assembly = new BasicBlockAssembly(CATALOG_ID, List.of(new BasicBlockGroup(
                "buy",
                TradeContainer.BUY,
                EvaluationMode.INDEPENDENT,
                AllocationMode.EQUAL,
                List.of(AAPL_ID),
                List.of(new BasicBlock(
                        "target",
                        "TARGETS",
                        Map.of("symbols", List.of("AAPL", "SPY"), "settings", Map.of("z", true, "a", 2)))),
                List.of())));
        var target = element(
                "TARGETS",
                "{\"required\":[\"symbols\",\"settings\"],\"properties\":{\"symbols\":{\"type\":\"array\"},\"settings\":{\"type\":\"object\"}}}",
                "{}",
                "{}",
                contract("대상은 {symbols}, 설정은 {settings}입니다."));

        var review = translator.translate(assembly, catalog(target));

        assertThat(review.groups().getFirst().sentence())
                .isEqualTo("대상은 [AAPL, SPY], 설정은 {a=2, z=true}입니다.");
    }

    private static BasicBlockAssembly validAssembly() {
        return new BasicBlockAssembly(CATALOG_ID, List.of(new BasicBlockGroup(
                "buy",
                TradeContainer.BUY,
                EvaluationMode.INDEPENDENT,
                AllocationMode.EQUAL,
                List.of(AAPL_ID),
                List.of(
                        new BasicBlock("trigger", "MARKET_OPEN", Map.of()),
                        new BasicBlock("condition", "RSI", Map.of("period", 14, "threshold", new BigDecimal("30.50"))),
                        new BasicBlock("order", "BUY_ORDER", Map.of("budgetPercent", 25))),
                List.of(
                        new BasicBlockConnection("trigger", "signal", "condition", "input"),
                        new BasicBlockConnection("condition", "result", "order", "input")))));
    }

    private static BasicStrategyCatalog catalog(StrategyElementDefinition... elements) {
        return new BasicStrategyCatalog(
                new ElementCatalogVersion(
                        CATALOG_ID,
                        "basic/v1",
                        "schema/v1",
                        "catalog/v1",
                        "data/v1",
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        Instant.parse("2026-08-01T00:00:00Z"),
                        null),
                List.of(elements),
                List.of(),
                List.of(new SupportedInstrument(AAPL_ID, "STOCK", "XNAS", "USD", "AAPL")));
    }

    private static StrategyElementDefinition element(
            String code, String parameters, String inputs, String outputs, String contract) {
        return new StrategyElementDefinition(
                UUID.nameUUIDFromBytes(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                CATALOG_ID,
                code,
                "BLOCK",
                parameters,
                inputs,
                outputs,
                contract,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    }

    private static String contract(String template) {
        return "{\"containers\":[\"BUY\"],\"reviewTemplates\":{\"ko-KR\":\"" + template + "\"}}";
    }

    private static String rsiParameters() {
        return "{\"required\":[\"period\",\"threshold\"],\"properties\":{\"period\":{\"type\":\"integer\"},\"threshold\":{\"type\":\"number\"}}}";
    }

    private static String budgetParameters() {
        return "{\"required\":[\"budgetPercent\"],\"properties\":{\"budgetPercent\":{\"type\":\"integer\"}}}";
    }
}
