package com.idea2strategy.backend.application.delegation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.strategy.DelegatedStrategyEditor;
import com.idea2strategy.backend.application.strategy.DelegatedStrategyScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DelegatedStrategyDerivationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-02T12:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("a1510000-0000-4000-8000-000000000001");
    private static final UUID AUTHORIZATION_ID = UUID.fromString("a1510000-0000-4000-8000-000000000002");
    private static final UUID CREDENTIAL_ID = UUID.fromString("a1510000-0000-4000-8000-000000000003");
    private static final UUID SOURCE_ID = UUID.fromString("a1510000-0000-4000-8000-000000000004");
    private static final UUID RESULT_ID = UUID.fromString("a1510000-0000-4000-8000-000000000005");
    private static final UUID CORRELATION_ID = UUID.fromString("a1510000-0000-4000-8000-000000000006");

    @Test
    void createRequiresCreateScopeAndRecordsAppendOnlyProvenance() {
        var authorizations = new RecordingAuthorizationPort();
        var commands = new RecordingDerivationPort();
        var service = service(authorizations, commands);

        var result = service.record(command(DelegatedStrategyDerivationType.CREATE, null));

        assertThat(authorizations.capabilityScopes).containsExactly(DelegatedStrategyScope.STRATEGY_CREATE);
        assertThat(authorizations.targetChecks).isEmpty();
        assertThat(result.resultStrategyId()).isEqualTo(RESULT_ID);
        assertThat(commands.mutation.sourceStrategyId()).isNull();
    }

    @Test
    void copyRequiresAnExplicitlyTargetedSourceAndCopyScope() {
        var authorizations = new RecordingAuthorizationPort();
        var commands = new RecordingDerivationPort();
        var service = service(authorizations, commands);

        service.record(command(DelegatedStrategyDerivationType.COPY, SOURCE_ID));

        assertThat(authorizations.targetChecks).containsExactly(SOURCE_ID);
        assertThat(authorizations.targetScopes).containsExactly(DelegatedStrategyScope.STRATEGY_COPY);
        assertThat(commands.mutation.sourceStrategyId()).isEqualTo(SOURCE_ID);
    }

    @Test
    void rejectsMalformedCreateAndCopyBoundariesBeforePersistence() {
        var service = service(new RecordingAuthorizationPort(), new RecordingDerivationPort());

        assertThatThrownBy(() -> service.record(command(DelegatedStrategyDerivationType.CREATE, SOURCE_ID)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.record(command(DelegatedStrategyDerivationType.COPY, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DelegatedStrategyDerivationService service(
            RecordingAuthorizationPort authorizations,
            RecordingDerivationPort commands) {
        return new DelegatedStrategyDerivationService(
                authorizations, authorizations, commands, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static DelegatedStrategyDerivationCommand command(
            DelegatedStrategyDerivationType type, UUID sourceStrategyId) {
        return new DelegatedStrategyDerivationCommand(
                type,
                new DelegatedStrategyEditor(ACCOUNT_ID, AUTHORIZATION_ID, CREDENTIAL_ID),
                4,
                sourceStrategyId,
                RESULT_ID,
                8,
                CORRELATION_ID,
                "derive-1",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    private static final class RecordingAuthorizationPort
            implements DelegatedStrategyCapabilityPort,
                    com.idea2strategy.backend.application.strategy.DelegatedStrategyAuthorizationPort {
        private final ArrayList<DelegatedStrategyScope> capabilityScopes = new ArrayList<>();
        private final ArrayList<DelegatedStrategyScope> targetScopes = new ArrayList<>();
        private final ArrayList<UUID> targetChecks = new ArrayList<>();

        @Override
        public void requireAuthorized(DelegatedStrategyEditor editor, DelegatedStrategyScope scope,
                long expectedAuthorizationVersion, Instant at) {
            capabilityScopes.add(scope);
        }

        @Override
        public void requireAuthorized(DelegatedStrategyEditor editor, UUID strategyId,
                DelegatedStrategyScope scope, Instant at) {
            targetChecks.add(strategyId);
            targetScopes.add(scope);
        }
    }

    private static final class RecordingDerivationPort implements DelegatedStrategyDerivationCommandPort {
        private DelegatedStrategyDerivationMutation mutation;

        @Override
        public DelegatedStrategyDerivationResult executeAtomically(
                DelegatedStrategyDerivationCommand command,
                Instant at,
                DelegatedStrategyDerivationDecision decision) {
            mutation = decision.decide();
            return mutation.toResult();
        }
    }
}
