package com.idea2strategy.backend.application.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.testing.FixedIdGenerator;
import com.idea2strategy.backend.application.testing.RecordingDomainEventPublisher;
import com.idea2strategy.backend.application.testing.TestSessionPrincipal;
import com.idea2strategy.backend.domain.strategy.Strategy;
import com.idea2strategy.backend.domain.strategy.StrategyCreated;
import com.idea2strategy.backend.domain.strategy.StrategyDocument;
import com.idea2strategy.backend.domain.strategy.StrategyMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BasicStrategyDraftCommandServiceTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID STRATEGY_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final Instant CREATED_AT = Instant.parse("2026-08-01T03:00:00Z");
    private static final String LEASE_TOKEN = "lease-token";

    @Test
    void createsBasicStrategyWithParseableEmptyDraftWithoutStartingBacktest() {
        var repository = new InMemoryDraftRepository();
        var events = new RecordingDomainEventPublisher();
        var service = service(repository, events);

        UUID strategyId = service.createBasic("Momentum", "Draft");

        assertThat(repository.strategies.get(strategyId).mode()).isEqualTo(StrategyMode.BASIC);
        assertThat(repository.documents.get(strategyId).semanticDocument())
                .isEqualTo("{\"groups\":[],\"mode\":\"BASIC\"}");
        assertThat(repository.documents.get(strategyId).presentationDocument())
                .isEqualTo("{\"positions\":{},\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}");
        assertThat(repository.documents.get(strategyId).editSequence()).isZero();
        assertThat(events.publishedEvents()).containsExactly(
                new StrategyCreated(STRATEGY_ID, OWNER_ID, StrategyMode.BASIC, CREATED_AT));
    }

    @Test
    void autosaveAndExplicitSaveAdvanceTheExpectedEditSequence() {
        var repository = new InMemoryDraftRepository();
        var service = service(repository, new RecordingDomainEventPublisher());
        UUID strategyId = service.createBasic("Momentum", null);
        repository.activateLease(LEASE_TOKEN);

        StrategyDocument autosaved = service.autosave(
                strategyId,
                0,
                LEASE_TOKEN,
                "{\"mode\":\"BASIC\",\"groups\":[{\"id\":\"buy\"}]}",
                "{\"positions\":{},\"viewport\":{\"x\":10,\"y\":20,\"zoom\":1}}",
                "basic-semantic/v1",
                "basic-presentation/v1");
        StrategyDocument explicitlySaved = service.saveExplicitly(
                strategyId,
                1,
                LEASE_TOKEN,
                "{\"mode\":\"BASIC\",\"groups\":[{\"id\":\"buy\"},{\"id\":\"sell\"}]}",
                "{\"positions\":{},\"viewport\":{\"x\":30,\"y\":40,\"zoom\":1}}",
                "basic-semantic/v1",
                "basic-presentation/v1");

        assertThat(autosaved.editSequence()).isEqualTo(1);
        assertThat(explicitlySaved.editSequence()).isEqualTo(2);
        assertThat(repository.documents.get(strategyId)).isEqualTo(explicitlySaved);
    }

    @Test
    void staleSaveIsRejectedWithoutOverwritingTheLatestDraft() {
        var repository = new InMemoryDraftRepository();
        var service = service(repository, new RecordingDomainEventPublisher());
        UUID strategyId = service.createBasic("Momentum", null);
        repository.activateLease(LEASE_TOKEN);
        StrategyDocument latest = service.autosave(
                strategyId,
                0,
                LEASE_TOKEN,
                "{\"mode\":\"BASIC\",\"groups\":[{\"id\":\"latest\"}]}",
                "{\"positions\":{},\"viewport\":{\"x\":10,\"y\":20,\"zoom\":1}}",
                "basic-semantic/v1",
                "basic-presentation/v1");

        assertThatThrownBy(() -> service.saveExplicitly(
                        strategyId,
                        0,
                        LEASE_TOKEN,
                        "{\"mode\":\"BASIC\",\"groups\":[{\"id\":\"stale\"}]}",
                        "{\"positions\":{},\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}",
                        "basic-semantic/v1",
                        "basic-presentation/v1"))
                .isInstanceOf(StrategyDraftConflictException.class)
                .hasMessage("Strategy draft changed; reload before saving");
        assertThat(repository.documents.get(strategyId)).isEqualTo(latest);
    }

    @Test
    void endedSessionCannotSaveLateChanges() {
        var repository = new InMemoryDraftRepository();
        var service = service(repository, new RecordingDomainEventPublisher());
        UUID strategyId = service.createBasic("Momentum", null);
        repository.activateLease(LEASE_TOKEN);
        repository.releaseLease();

        assertThatThrownBy(() -> service.autosave(
                        strategyId,
                        0,
                        LEASE_TOKEN,
                        "{\"mode\":\"BASIC\",\"groups\":[{\"id\":\"late\"}]}",
                        "{\"positions\":{},\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}",
                        "basic-semantic/v1",
                        "basic-presentation/v1"))
                .isInstanceOf(StrategyEditLeaseInvalidException.class);
        assertThat(repository.documents.get(strategyId).editSequence()).isZero();
    }

    private static BasicStrategyDraftCommandService service(
            InMemoryDraftRepository repository, RecordingDomainEventPublisher events) {
        return new BasicStrategyDraftCommandService(
                repository,
                repository,
                repository,
                new TestSessionPrincipal(OWNER_ID),
                new FixedIdGenerator(STRATEGY_ID),
                java.time.Clock.fixed(CREATED_AT, ZoneOffset.UTC),
                events);
    }

    private static final class InMemoryDraftRepository
            implements BasicStrategyDraftCommandPort, StrategyQueryPort, StrategyDocumentQueryPort {
        private final Map<UUID, Strategy> strategies = new HashMap<>();
        private final Map<UUID, StrategyDocument> documents = new HashMap<>();
        private UUID leaseSessionId;
        private String leaseTokenDigest;
        private Instant leaseExpiresAt;

        @Override
        public void create(Strategy strategy, StrategyDocument document) {
            strategies.put(strategy.id(), strategy);
            documents.put(document.strategyId(), document);
        }

        @Override
        public StrategyDraftReplaceResult replaceDocument(
                StrategyDocument document,
                long expectedEditSequence,
                UUID sessionId,
                String tokenDigest,
                Instant now) {
            StrategyDocument current = documents.get(document.strategyId());
            if (current == null || current.editSequence() != expectedEditSequence) {
                return StrategyDraftReplaceResult.STALE_EDIT_SEQUENCE;
            }
            if (!sessionId.equals(leaseSessionId)
                    || !tokenDigest.equals(leaseTokenDigest)
                    || leaseExpiresAt == null
                    || !leaseExpiresAt.isAfter(now)) {
                return StrategyDraftReplaceResult.INVALID_LEASE;
            }
            documents.put(document.strategyId(), document);
            return StrategyDraftReplaceResult.UPDATED;
        }

        @Override
        public Optional<Strategy> findOwnedById(UUID strategyId, UUID ownerAccountId) {
            return Optional.ofNullable(strategies.get(strategyId))
                    .filter(strategy -> strategy.ownerAccountId().equals(ownerAccountId));
        }

        @Override
        public Optional<StrategyDocument> findOwnedByStrategyId(UUID strategyId, UUID ownerAccountId) {
            return findOwnedById(strategyId, ownerAccountId).map(strategy -> documents.get(strategy.id()));
        }

        private void activateLease(String token) {
            leaseSessionId = SESSION_ID;
            leaseTokenDigest = StrategyEditLeaseTokens.sha256(token);
            leaseExpiresAt = CREATED_AT.plusSeconds(300);
        }

        private void releaseLease() {
            leaseSessionId = null;
            leaseTokenDigest = null;
            leaseExpiresAt = null;
        }
    }
}
