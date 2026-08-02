package com.idea2strategy.backend.application.accountsanction;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountSanctionExpiryWorkerTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

    @Test
    void turnsTheDatabaseDueReferenceIntoAStableIdempotentSystemExpiry() {
        UUID account = id(1);
        UUID sanction = id(2);
        var due = new AccountSanctionExpiryPort.DueSanction(account, sanction, NOW, 4);
        var commands = new RecordingCommands(account, sanction);
        var service = new AccountSanctionCommandService(
                commands,
                (context, permission, at) -> new AccountSanctionAuthorizationPort.Decision(
                        false, "UNUSED", null, Set.of(), Set.of(), false, false),
                effect -> {}, messages -> {}, id(3), id(4), Clock.fixed(NOW, ZoneOffset.UTC));

        var result = new AccountSanctionExpiryWorker(limit -> List.of(due), service)
                .expireDue(20, id(5));

        assertThat(result).singleElement().extracting(AccountSanctionResult::code)
                .isEqualTo("SANCTION_EXPIRED");
        assertThat(commands.command.type()).isEqualTo(AccountSanctionCommand.Type.EXPIRE);
        assertThat(commands.command.expectedVersion()).isEqualTo(4);
        assertThat(commands.command.idempotencyKey()).contains(sanction.toString(), NOW.toString());
        assertThat(commands.command.requestHash()).matches("[0-9a-f]{64}");
    }

    private static final class RecordingCommands implements AccountSanctionCommandPort {
        private final AccountSanctionState state;
        private AccountSanctionCommand command;

        private RecordingCommands(UUID account, UUID sanction) {
            state = new AccountSanctionState(account, 4, List.of(new AccountSanctionState.Sanction(
                    sanction, AccountSanctionState.Type.SUSPENSION, AccountSanctionState.Status.ACTIVE,
                    "POLICY", NOW.minusSeconds(100), NOW.minusSeconds(100), NOW, null)));
        }

        @Override
        public AccountSanctionResult executeAtomically(
                AccountSanctionCommand command,
                Instant evaluatedAt,
                AccountSanctionAuthorizationPort.Decision authorization,
                AccountSanctionDecision decision,
                TransactionalEffects effects) {
            this.command = command;
            AccountSanctionResult result = decision.decide(state, authorization);
            effects.publish(result);
            return result;
        }
    }

    private static UUID id(int suffix) {
        return UUID.fromString("a1400000-0000-4000-8000-%012d".formatted(suffix));
    }
}
