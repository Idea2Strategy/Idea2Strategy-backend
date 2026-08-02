package com.idea2strategy.backend.application.accountclosure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentifierQuarantineReleaseWorkerTest {
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void releasesOnlyDueIdentifiersWithoutAReuseBlockingHold() {
        var held = new IdentifierQuarantinePort.DueIdentifier(UUID.randomUUID(), UUID.randomUUID(), "EMAIL");
        var releasable = new IdentifierQuarantinePort.DueIdentifier(UUID.randomUUID(), UUID.randomUUID(), "OIDC_SUBJECT");
        var port = new FakePort(held, releasable);

        assertThat(new IdentifierQuarantineReleaseWorker(port, Clock.fixed(NOW, ZoneOffset.UTC)).run(10)).isOne();
        assertThat(port.released).isEqualTo(releasable);
    }

    private static final class FakePort implements IdentifierQuarantinePort {
        private final DueIdentifier held;
        private final DueIdentifier releasable;
        private DueIdentifier released;
        private FakePort(DueIdentifier held, DueIdentifier releasable) {
            this.held = held;
            this.releasable = releasable;
        }
        public List<DueIdentifier> findDueIdentifiers(int limit, Instant now) { return List.of(held, releasable); }
        public boolean hasReuseBlockingLegalHold(UUID accountId) { return held.accountId().equals(accountId); }
        public boolean releaseBindingAndQuarantine(DueIdentifier identifier, Instant at) {
            released = identifier;
            return true;
        }
    }
}
