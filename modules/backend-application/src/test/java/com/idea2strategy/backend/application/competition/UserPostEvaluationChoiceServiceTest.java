package com.idea2strategy.backend.application.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserPostEvaluationChoiceServiceTest {
    private static final UUID ROOM_ID = id(1);
    private static final UUID PARTICIPATION_ID = id(2);
    private static final UUID OWNER_ID = id(3);
    private static final Instant NOW = Instant.parse("2026-08-02T05:00:00Z");

    @Test
    void suppliesTheAuthenticatedOwnerAndExplicitChoice() {
        var port = new StubPort();
        var service = new UserPostEvaluationChoiceService(
                port, () -> OWNER_ID, Clock.fixed(NOW, ZoneOffset.UTC));

        service.update(ROOM_ID, PARTICIPATION_ID, PostEvaluationAction.CONTINUE_PRIVATE);

        assertThat(port.ownerId).isEqualTo(OWNER_ID);
        assertThat(port.action).isEqualTo(PostEvaluationAction.CONTINUE_PRIVATE);
        assertThat(port.recordedAt).isEqualTo(NOW);
    }

    @Test
    void doesNotInventAChoiceWhenTheOwnerHasNotSelectedOne() {
        var port = new StubPort();
        var service = new UserPostEvaluationChoiceService(
                port, () -> OWNER_ID, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.find(ROOM_ID, PARTICIPATION_ID).action()).isNull();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.update(ROOM_ID, PARTICIPATION_ID, null))
                .withMessage("action must be explicitly selected");
    }

    private static UUID id(int suffix) {
        return UUID.fromString("a5000000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    private static final class StubPort implements PostEvaluationChoicePort {
        UUID ownerId;
        PostEvaluationAction action;
        Instant recordedAt;

        @Override
        public PostEvaluationChoice findOwned(UUID roomId, UUID participationId, UUID ownerAccountId) {
            ownerId = ownerAccountId;
            return new PostEvaluationChoice(roomId, participationId, null, null, null);
        }

        @Override
        public PostEvaluationChoice updateOwned(
                UUID roomId,
                UUID participationId,
                UUID ownerAccountId,
                PostEvaluationAction action,
                Instant recordedAt) {
            this.ownerId = ownerAccountId;
            this.action = action;
            this.recordedAt = recordedAt;
            return new PostEvaluationChoice(roomId, participationId, action, recordedAt, null);
        }
    }
}
