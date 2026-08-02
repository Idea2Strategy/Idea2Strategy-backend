package com.idea2strategy.backend.application.accountclosure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RetentionDispositionWorkerTest {
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void legalHoldAlwaysWinsAndDestructiveActionsFailClosedWithoutExecutor() {
        var port = new FakePort();
        port.due.add(obligation("CONTACT_IDENTIFIER", RetentionDisposition.DELETE));
        port.due.add(obligation("PROFILE", RetentionDisposition.ANONYMIZE));
        port.due.add(obligation("ACCOUNT_LIFECYCLE_AUDIT", RetentionDisposition.RETAIN));
        port.heldCategory = "CONTACT_IDENTIFIER";

        assertThat(new RetentionDispositionWorker(port, Clock.fixed(NOW, ZoneOffset.UTC)).run(10)).isEqualTo(3);

        assertThat(port.held).hasSize(1);
        assertThat(port.completed).hasSize(1);
        assertThat(port.failed).containsExactly(RetentionDispositionWorker.MISSING_EXECUTOR);
        assertThat(port.resumed).isTrue();
    }

    private static RetentionObligation obligation(String category, RetentionDisposition disposition) {
        return new RetentionObligation(UUID.randomUUID(), UUID.randomUUID(), category, disposition, NOW);
    }

    private static final class FakePort implements RetentionObligationPort {
        final List<RetentionObligation> due = new ArrayList<>();
        final List<UUID> held = new ArrayList<>();
        final List<UUID> completed = new ArrayList<>();
        final List<String> failed = new ArrayList<>();
        String heldCategory;
        boolean resumed;
        public List<RetentionObligation> findDueObligations(int limit, Instant now) { return due; }
        public boolean hasActiveLegalHold(UUID accountId, String category) { return category.equals(heldCategory); }
        public void markHeld(UUID id, Instant at) { held.add(id); }
        public void markCompleted(UUID id, Instant at) { completed.add(id); }
        public void markFailed(UUID id, String code, Instant at) { failed.add(code); }
        public int resumeReleasedHolds(Instant at) { resumed = true; return 0; }
    }
}
