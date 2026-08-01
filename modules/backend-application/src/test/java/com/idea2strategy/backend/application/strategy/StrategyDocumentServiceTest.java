package com.idea2strategy.backend.application.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.testing.TestPrincipal;
import com.idea2strategy.backend.domain.strategy.Strategy;
import com.idea2strategy.backend.domain.strategy.StrategyDocument;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StrategyDocumentServiceTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID STRATEGY_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-01T01:00:00Z");

    @Test
    void savesAndLosslesslyRestoresSeparateSemanticAndPresentationDocumentsForTheOwner() {
        var repository = new InMemoryStrategyDocumentRepository();
        repository.strategies.put(
                STRATEGY_ID,
                Strategy.createBasic(STRATEGY_ID, OWNER_ID, "Momentum", null, NOW));
        var principal = new TestPrincipal(OWNER_ID);
        var commands = new StrategyDocumentCommandService(
                repository, repository, repository, principal, Clock.fixed(NOW, ZoneOffset.UTC));
        var queries = new StrategyDocumentQueryService(repository, principal);

        StrategyDocument saved = commands.save(
                STRATEGY_ID,
                """
                {
                  "mode": "BASIC",
                  "groups": [{"id":"buy","blocks":[{"id":"rsi","period":14}]}]
                }
                """,
                """
                {
                  "viewport": {"x": 12, "y": 34},
                  "positions": {"rsi": {"x": 100, "y": 220}}
                }
                """,
                "basic-semantic/v1",
                "basic-presentation/v1");
        StrategyDocument loaded = queries.getOwned(STRATEGY_ID);

        assertThat(loaded).isEqualTo(saved);
        assertThat(loaded.semanticDocument())
                .isEqualTo("{\"mode\":\"BASIC\",\"groups\":[{\"id\":\"buy\",\"blocks\":[{\"id\":\"rsi\",\"period\":14}]}]}");
        assertThat(loaded.presentationDocument())
                .isEqualTo("{\"viewport\":{\"x\":12,\"y\":34},\"positions\":{\"rsi\":{\"x\":100,\"y\":220}}}");
        assertThat(loaded.semanticHash()).isEqualTo(sha256(loaded.semanticDocument()));
        assertThat(loaded.presentationHash()).isEqualTo(sha256(loaded.presentationDocument()));
        assertThat(loaded.editSequence()).isZero();
        assertThat(loaded.createdAt()).isEqualTo(NOW);
        assertThat(loaded.updatedAt()).isEqualTo(NOW);

        assertThatThrownBy(() -> new StrategyDocumentQueryService(
                                repository, new TestPrincipal(OTHER_OWNER_ID))
                        .getOwned(STRATEGY_ID))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("Strategy document not found");
    }

    @Test
    void rejectsMalformedJsonBeforeSavingPrivateStrategyContent() {
        var repository = new InMemoryStrategyDocumentRepository();
        repository.strategies.put(
                STRATEGY_ID,
                Strategy.createBasic(STRATEGY_ID, OWNER_ID, "Momentum", null, NOW));
        var commands = new StrategyDocumentCommandService(
                repository,
                repository,
                repository,
                new TestPrincipal(OWNER_ID),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> commands.save(
                        STRATEGY_ID,
                        "{not-json}",
                        "{}",
                        "basic-semantic/v1",
                        "basic-presentation/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Strategy document must be valid JSON");
        assertThat(repository.documents).isEmpty();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class InMemoryStrategyDocumentRepository
            implements StrategyQueryPort, StrategyDocumentCommandPort, StrategyDocumentQueryPort {
        private final Map<UUID, Strategy> strategies = new HashMap<>();
        private final Map<UUID, StrategyDocument> documents = new HashMap<>();

        @Override
        public Optional<Strategy> findOwnedById(UUID strategyId, UUID ownerAccountId) {
            return Optional.ofNullable(strategies.get(strategyId))
                    .filter(strategy -> strategy.ownerAccountId().equals(ownerAccountId));
        }

        @Override
        public void save(StrategyDocument document) {
            documents.put(document.strategyId(), document);
        }

        @Override
        public Optional<StrategyDocument> findOwnedByStrategyId(UUID strategyId, UUID ownerAccountId) {
            return Optional.ofNullable(documents.get(strategyId))
                    .filter(document -> strategies.get(document.strategyId())
                            .ownerAccountId()
                            .equals(ownerAccountId));
        }
    }
}
