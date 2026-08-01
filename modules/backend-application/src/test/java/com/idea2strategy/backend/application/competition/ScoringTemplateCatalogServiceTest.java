package com.idea2strategy.backend.application.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.domain.competition.ScoringMetric;
import com.idea2strategy.backend.domain.competition.ScoringTemplateKind;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScoringTemplateCatalogServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-01T14:00:00Z");
    private static final UUID SINGLE_ID = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID COMPOSITE_ID = UUID.fromString("60000000-0000-4000-8000-000000000002");

    @Test
    void returnsPublishedSingleAndCompositeTemplatesInStableOrder() {
        var port = new StubCatalogPort(List.of(
                record(COMPOSITE_ID, "RISK_ADJUSTED", "2.0.0", compositeRules()),
                record(SINGLE_ID, "TOTAL_RETURN", "1.0.0", singleRules())));
        var service = service(port);

        var catalog = service.listSelectable();

        assertThat(catalog).extracting(template -> template.templateCode())
                .containsExactly("RISK_ADJUSTED", "TOTAL_RETURN");
        assertThat(catalog.getFirst().kind()).isEqualTo(ScoringTemplateKind.COMPOSITE);
        assertThat(catalog.getFirst().components()).extracting(component -> component.metric())
                .containsExactly(ScoringMetric.TOTAL_RETURN, ScoringMetric.SHARPE_RATIO, ScoringMetric.MAX_DRAWDOWN);
        assertThat(catalog).isUnmodifiable();
    }

    @Test
    void rejectsArbitraryFormulaAndExternalDataFields() {
        var formula = singleRules().replace(
                "\"components\"", "\"formula\":\"returnPct * 100\",\"components\"");
        var externalSource = singleRules().replace(
                "\"components\"", "\"externalSource\":\"https://example.com/index\",\"components\"");

        assertThatThrownBy(() -> service(new StubCatalogPort(List.of(record(SINGLE_ID, "FORMULA", "1", formula))))
                        .listSelectable())
                .isInstanceOf(InvalidScoringTemplateException.class)
                .hasMessageContaining("formula");
        assertThatThrownBy(() -> service(
                                new StubCatalogPort(List.of(record(SINGLE_ID, "EXTERNAL", "1", externalSource))))
                        .listSelectable())
                .isInstanceOf(InvalidScoringTemplateException.class)
                .hasMessageContaining("externalSource");
    }

    @Test
    void acceptsOnlyDeclaredAdjustmentsWithinRangeAndPrecision() {
        var service = service(new StubCatalogPort(List.of(record(SINGLE_ID, "TOTAL_RETURN", "1.0.0", singleRules()))));

        var selection = service.select(SINGLE_ID, Map.of("minimumTrades", new BigDecimal("5")));

        assertThat(selection.adjustments()).containsEntry("minimumTrades", new BigDecimal("5"));
        assertThatThrownBy(() -> service.select(SINGLE_ID, Map.of("weight", BigDecimal.ONE)))
                .isInstanceOf(InvalidScoringAdjustmentException.class)
                .hasMessageContaining("weight");
        assertThatThrownBy(() -> service.select(SINGLE_ID, Map.of("minimumTrades", new BigDecimal("21"))))
                .isInstanceOf(InvalidScoringAdjustmentException.class)
                .hasMessageContaining("minimumTrades");
        assertThatThrownBy(() -> service.select(SINGLE_ID, Map.of("minimumTrades", new BigDecimal("5.5"))))
                .isInstanceOf(InvalidScoringAdjustmentException.class)
                .hasMessageContaining("precision");
    }

    private static ScoringTemplateCatalogService service(ScoringTemplateCatalogQueryPort port) {
        return new ScoringTemplateCatalogService(port, Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
    }

    private static ScoringTemplateCatalogRecord record(UUID id, String code, String version, String rules) {
        return new ScoringTemplateCatalogRecord(
                id,
                code,
                version,
                rules,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                NOW.minusSeconds(60),
                null);
    }

    private static String singleRules() {
        return """
                {
                  "kind":"SINGLE",
                  "calculationRulesVersion":"1.0.0",
                  "components":[
                    {"metric":"TOTAL_RETURN","direction":"HIGHER_IS_BETTER","coefficient":1}
                  ],
                  "adjustments":[
                    {"code":"minimumTrades","unit":"COUNT","minimum":2,"maximum":20,"scale":0}
                  ]
                }
                """;
    }

    private static String compositeRules() {
        return """
                {
                  "kind":"COMPOSITE",
                  "calculationRulesVersion":"2.0.0",
                  "components":[
                    {"metric":"TOTAL_RETURN","direction":"HIGHER_IS_BETTER","coefficient":0.5},
                    {"metric":"SHARPE_RATIO","direction":"HIGHER_IS_BETTER","coefficient":0.3},
                    {"metric":"MAX_DRAWDOWN","direction":"LOWER_IS_BETTER","coefficient":0.2}
                  ],
                  "adjustments":[]
                }
                """;
    }

    private static final class StubCatalogPort implements ScoringTemplateCatalogQueryPort {
        private final List<ScoringTemplateCatalogRecord> records;

        private StubCatalogPort(List<ScoringTemplateCatalogRecord> records) {
            this.records = records;
        }

        @Override
        public List<ScoringTemplateCatalogRecord> findSelectableAt(Instant at) {
            return records;
        }

        @Override
        public Optional<ScoringTemplateCatalogRecord> findSelectableById(UUID id, Instant at) {
            return records.stream().filter(record -> record.id().equals(id)).findFirst();
        }
    }
}
