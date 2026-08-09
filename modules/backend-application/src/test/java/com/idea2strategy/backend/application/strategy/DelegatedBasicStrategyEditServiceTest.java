package com.idea2strategy.backend.application.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.domain.strategy.ElementCatalogVersion;
import com.idea2strategy.backend.domain.strategy.Strategy;
import com.idea2strategy.backend.domain.strategy.StrategyDocument;
import com.idea2strategy.backend.domain.strategy.StrategyElementDefinition;
import com.idea2strategy.backend.domain.strategy.SupportedInstrument;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DelegatedBasicStrategyEditServiceTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID AUTHORIZATION_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID CREDENTIAL_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID STRATEGY_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID CATALOG_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID INSTRUMENT_ID = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");

    @Test
    void previewsAndAppliesOnlyTheExactReviewedOfficialBasicEdit() {
        var authorizer = new RecordingAuthorizer();
        var commandPort = new RecordingCommandPort();
        var service = service(authorizer, commandPort);
        var editor = editor();
        var operations = List.of(new DelegatedBasicEditOperation(
                "SET_VALUE",
                Map.of("groupId", "buy", "blockId", "condition", "parameter", "period", "value", 21)));

        var preview = service.preview(editor, STRATEGY_ID, 7, catalog(), operations);

        assertThat(preview.valid()).isTrue();
        assertThat(preview.proposedSemanticDocument()).contains("\"period\":21");
        assertThat(preview.changes()).containsExactly("SET_VALUE buy/condition/period");
        assertThat(authorizer.lastScope).isEqualTo(DelegatedStrategyScope.STRATEGY_EDIT);

        StrategyDocument applied = service.apply(
                editor, STRATEGY_ID, 7, catalog(), operations, preview.previewHash());

        assertThat(applied.editSequence()).isEqualTo(8);
        assertThat(applied.semanticHash()).isEqualTo(preview.previewHash());
        assertThat(commandPort.saved).isEqualTo(applied);
        assertThat(commandPort.editor).isEqualTo(editor);
    }

    @Test
    void addsRemovesAndReconnectsOnlyPublishedCatalogBlocks() {
        var service = service(new RecordingAuthorizer(), new RecordingCommandPort());
        var operations = List.of(
                new DelegatedBasicEditOperation(
                        "REMOVE_BLOCK", Map.of("groupId", "buy", "blockId", "condition")),
                new DelegatedBasicEditOperation(
                        "ADD_BLOCK", Map.of(
                                "groupId", "buy", "blockId", "condition-v2", "elementCode", "RSI",
                                "parameters", Map.of("period", 21), "index", 1)),
                new DelegatedBasicEditOperation(
                        "CONNECT_BLOCKS", Map.of(
                                "groupId", "buy", "fromBlockId", "trigger", "outputPort", "signal",
                                "toBlockId", "condition-v2", "inputPort", "input")),
                new DelegatedBasicEditOperation(
                        "CONNECT_BLOCKS", Map.of(
                                "groupId", "buy", "fromBlockId", "condition-v2", "outputPort", "result",
                                "toBlockId", "order", "inputPort", "input")));

        var preview = service.preview(editor(), STRATEGY_ID, 7, catalog(), operations);

        assertThat(preview.valid()).isTrue();
        assertThat(preview.proposedSemanticDocument()).contains("condition-v2", "\"period\":21");
        assertThat(preview.changes()).containsExactly(
                "REMOVE_BLOCK buy/condition",
                "ADD_BLOCK buy/condition-v2 RSI",
                "CONNECT_BLOCKS buy/trigger->condition-v2",
                "CONNECT_BLOCKS buy/condition-v2->order");
    }

    @Test
    void rejectsUnreviewedContentAndDangerousExternalActions() {
        var commandPort = new RecordingCommandPort();
        var service = service(new RecordingAuthorizer(), commandPort);

        for (String action : List.of("EXECUTE_CODE", "FETCH_EXTERNAL_DATA", "SUBMIT_ORDER")) {
            assertThatThrownBy(() -> service.preview(
                            editor(), STRATEGY_ID, 7, catalog(),
                            List.of(new DelegatedBasicEditOperation(action, Map.of("value", "dangerous")))))
                    .isInstanceOf(DelegatedBasicEditRejectedException.class)
                    .hasMessage("Delegated operation is not allowed: " + action);
        }

        var operations = List.of(new DelegatedBasicEditOperation(
                "SET_VALUE",
                Map.of("groupId", "buy", "blockId", "condition", "parameter", "script", "value", "alert(1)")));
        assertThatThrownBy(() -> service.preview(editor(), STRATEGY_ID, 7, catalog(), operations))
                .isInstanceOf(DelegatedBasicEditRejectedException.class)
                .hasMessage("Parameter is not declared by the official catalog: script");
        assertThat(commandPort.saved).isNull();
    }

    @Test
    void refusesApplyWhenTheReviewedPreviewHashDoesNotMatch() {
        var commandPort = new RecordingCommandPort();
        var service = service(new RecordingAuthorizer(), commandPort);
        var operations = List.of(new DelegatedBasicEditOperation(
                "SET_VALUE",
                Map.of("groupId", "buy", "blockId", "condition", "parameter", "period", "value", 21)));

        assertThatThrownBy(() -> service.apply(
                        editor(), STRATEGY_ID, 7, catalog(), operations,
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .isInstanceOf(DelegatedBasicEditRejectedException.class)
                .hasMessage("Reviewed preview does not match the requested edit");
        assertThat(commandPort.saved).isNull();
    }

    @Test
    void createsATradeContainerSoADelegatedToolCanStartFromNothing() {
        var service = service(new RecordingAuthorizer(), new RecordingCommandPort());
        var operations = List.of(new DelegatedBasicEditOperation(
                "ADD_GROUP",
                Map.of(
                        "groupId", "sell",
                        "container", "SELL",
                        "evaluationMode", "INDEPENDENT",
                        "allocationMode", "EQUAL",
                        "instrumentIds", List.of(INSTRUMENT_ID.toString()))));

        var preview = service.preview(editor(), STRATEGY_ID, 7, catalog(), operations);

        assertThat(preview.changes()).containsExactly("ADD_GROUP sell SELL");
        assertThat(preview.proposedSemanticDocument()).contains("\"container\":\"SELL\"");
    }

    /**
     * Rule 9.9: a strategy holds one container per side. A second BUY container has no defined
     * meaning, so it is refused at the operation where the tool can still react, rather than
     * surviving into a document that only fails later.
     */
    @Test
    void refusesASecondContainerOnASideThatAlreadyHasOne() {
        var commandPort = new RecordingCommandPort();
        var service = service(new RecordingAuthorizer(), commandPort);
        var operations = List.of(new DelegatedBasicEditOperation(
                "ADD_GROUP",
                Map.of(
                        "groupId", "buy-2",
                        "container", "BUY",
                        "evaluationMode", "INDEPENDENT",
                        "allocationMode", "EQUAL",
                        "instrumentIds", List.of(INSTRUMENT_ID.toString()))));

        assertThatThrownBy(() -> service.preview(editor(), STRATEGY_ID, 7, catalog(), operations))
                .isInstanceOf(DelegatedBasicEditRejectedException.class)
                .hasMessageContaining("one container per side");
        assertThat(commandPort.saved).isNull();
    }

    @Test
    void refusesAContainerThatNamesNoInstrumentsOrAnUnknownMode() {
        var service = service(new RecordingAuthorizer(), new RecordingCommandPort());

        assertThatThrownBy(() -> service.preview(editor(), STRATEGY_ID, 7, catalog(),
                        List.of(new DelegatedBasicEditOperation("ADD_GROUP", Map.of(
                                "groupId", "sell", "container", "SELL",
                                "evaluationMode", "INDEPENDENT", "allocationMode", "EQUAL",
                                "instrumentIds", List.of())))))
                .isInstanceOf(DelegatedBasicEditRejectedException.class)
                .hasMessageContaining("instrumentIds");

        assertThatThrownBy(() -> service.preview(editor(), STRATEGY_ID, 7, catalog(),
                        List.of(new DelegatedBasicEditOperation("ADD_GROUP", Map.of(
                                "groupId", "sell", "container", "SELL",
                                "evaluationMode", "SOMETIMES", "allocationMode", "EQUAL",
                                "instrumentIds", List.of(INSTRUMENT_ID.toString()))))))
                .isInstanceOf(DelegatedBasicEditRejectedException.class)
                .hasMessageContaining("evaluationMode");
    }

    @Test
    void validatesTheCurrentDraftOnlyWithTheDedicatedDelegatedScope() {
        var authorizer = new RecordingAuthorizer();
        var service = service(authorizer, new RecordingCommandPort());

        var result = service.validate(editor(), STRATEGY_ID, catalog());

        assertThat(result.valid()).isTrue();
        assertThat(authorizer.lastScope).isEqualTo(DelegatedStrategyScope.STRATEGY_VALIDATE);
    }

    private static DelegatedBasicStrategyEditService service(
            DelegatedStrategyAuthorizationPort authorizer,
            DelegatedBasicEditCommandPort commandPort) {
        Strategy strategy = Strategy.createBasic(STRATEGY_ID, ACCOUNT_ID, "Momentum", null, NOW.minusSeconds(60));
        StrategyDocument document = document();
        StrategyQueryPort strategies = (id, owner) -> Optional.of(strategy)
                .filter(value -> id.equals(STRATEGY_ID) && owner.equals(ACCOUNT_ID));
        StrategyDocumentQueryPort documents = (id, owner) -> Optional.of(document)
                .filter(value -> id.equals(STRATEGY_ID) && owner.equals(ACCOUNT_ID));
        return new DelegatedBasicStrategyEditService(
                strategies, documents, authorizer, commandPort, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static DelegatedStrategyEditor editor() {
        return new DelegatedStrategyEditor(ACCOUNT_ID, AUTHORIZATION_ID, CREDENTIAL_ID);
    }

    private static StrategyDocument document() {
        String semantic = StrategyDocumentJson.canonicalize("{\"catalogId\":\"" + CATALOG_ID + "\",\"groups\":[{"
                + "\"id\":\"buy\",\"container\":\"BUY\",\"evaluationMode\":\"INDEPENDENT\","
                + "\"allocationMode\":\"EQUAL\",\"instrumentIds\":[\"" + INSTRUMENT_ID + "\"],"
                + "\"blocks\":["
                + "{\"id\":\"trigger\",\"elementCode\":\"MARKET_OPEN\",\"parameters\":{}},"
                + "{\"id\":\"condition\",\"elementCode\":\"RSI\",\"parameters\":{\"period\":14}},"
                + "{\"id\":\"order\",\"elementCode\":\"BUY_ORDER\",\"parameters\":{}}],"
                + "\"connections\":["
                + "{\"fromBlockId\":\"trigger\",\"outputPort\":\"signal\",\"toBlockId\":\"condition\",\"inputPort\":\"input\"},"
                + "{\"fromBlockId\":\"condition\",\"outputPort\":\"result\",\"toBlockId\":\"order\",\"inputPort\":\"input\"}]}]}");
        String presentation = "{\"positions\":{}}";
        return new StrategyDocument(
                STRATEGY_ID, semantic, presentation, "basic-semantic/v1", "basic-presentation/v1",
                StrategyDocumentJson.sha256(semantic), StrategyDocumentJson.sha256(presentation), 7,
                NOW.minusSeconds(60), NOW.minusSeconds(1));
    }

    private static BasicStrategyCatalog catalog() {
        var version = new ElementCatalogVersion(
                CATALOG_ID, "basic/v1", "schema/v1", "catalog/v1", "data/v1",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                NOW.minusSeconds(3600), null);
        return new BasicStrategyCatalog(
                version,
                List.of(
                        element("MARKET_OPEN", "TRIGGER", "{}", "{}", "{\"signal\":{\"type\":\"BOOLEAN\"}}"),
                        element("RSI", "CONDITION", "{\"required\":[\"period\"],\"properties\":{\"period\":{\"type\":\"integer\"}}}",
                                "{\"input\":{\"type\":\"BOOLEAN\"}}", "{\"result\":{\"type\":\"BOOLEAN\"}}"),
                        element("BUY_ORDER", "ORDER", "{}", "{\"input\":{\"type\":\"BOOLEAN\"}}", "{}")),
                List.of(),
                List.of(new SupportedInstrument(INSTRUMENT_ID, "STOCK", "XNAS", "USD", "AAPL")));
    }

    private static StrategyElementDefinition element(
            String code, String kind, String parameters, String inputs, String outputs) {
        return new StrategyElementDefinition(
                UUID.nameUUIDFromBytes(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)), CATALOG_ID,
                code, kind, parameters, inputs, outputs,
                "{\"containers\":[\"BUY\"],\"reviewTemplates\":{\"ko-KR\":\"" + code + "\"}}",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    }

    private static final class RecordingAuthorizer implements DelegatedStrategyAuthorizationPort {
        private DelegatedStrategyScope lastScope;

        @Override
        public void requireAuthorized(
                DelegatedStrategyEditor editor,
                UUID strategyId,
                DelegatedStrategyScope scope,
                Instant at) {
            this.lastScope = scope;
        }
    }

    private static final class RecordingCommandPort implements DelegatedBasicEditCommandPort {
        private StrategyDocument saved;
        private DelegatedStrategyEditor editor;

        @Override
        public DelegatedBasicEditReplaceResult replace(
                StrategyDocument document,
                long expectedEditSequence,
                DelegatedStrategyEditor editor,
                Instant at) {
            this.saved = document;
            this.editor = editor;
            return DelegatedBasicEditReplaceResult.UPDATED;
        }
    }
}
