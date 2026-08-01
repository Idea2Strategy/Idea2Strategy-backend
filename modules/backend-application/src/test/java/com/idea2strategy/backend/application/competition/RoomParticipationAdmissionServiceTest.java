package com.idea2strategy.backend.application.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoomParticipationAdmissionServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");
    private static final UUID ROOM_ID = UUID.fromString("75000000-0000-4000-8000-000000000001");
    private static final UUID OWNER_ID = UUID.fromString("75000000-0000-4000-8000-000000000002");
    private static final UUID PARTICIPATION_ID = UUID.fromString("75000000-0000-4000-8000-000000000003");
    private static final UUID EVENT_ID = UUID.fromString("75000000-0000-4000-8000-000000000004");
    private static final UUID BOT_ID = UUID.fromString("75000000-0000-4000-8000-000000000005");

    @Test
    void admitsAProvisionedIndependentBotForTheCurrentAccount() {
        var port = new StubAdmissionPort();
        var service = service(port);

        var admitted = service.admit(ROOM_ID, "bot-orchid-07", context -> {
            assertThat(context.roomId()).isEqualTo(ROOM_ID);
            assertThat(context.ownerAccountId()).isEqualTo(OWNER_ID);
            return BOT_ID;
        });

        assertThat(admitted.participationId()).isEqualTo(PARTICIPATION_ID);
        assertThat(admitted.botId()).isEqualTo(BOT_ID);
        assertThat(port.request.ownerAccountId()).isEqualTo(OWNER_ID);
        assertThat(port.request.admittedAt()).isEqualTo(NOW);
    }

    @Test
    void preservesTheAtomicGateRejectionReason() {
        var port = new StubAdmissionPort();
        port.failure = RoomParticipationAdmissionFailure.ACCOUNT_EXECUTION_LIMIT_REACHED;

        assertThatThrownBy(() -> service(port).admit(ROOM_ID, "bot-orchid-08", context -> BOT_ID))
                .isInstanceOf(RoomParticipationAdmissionException.class)
                .extracting(exception -> ((RoomParticipationAdmissionException) exception).failure())
                .isEqualTo(RoomParticipationAdmissionFailure.ACCOUNT_EXECUTION_LIMIT_REACHED);
    }

    private static RoomParticipationAdmissionService service(StubAdmissionPort port) {
        return new RoomParticipationAdmissionService(
                port,
                () -> OWNER_ID,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> PARTICIPATION_ID,
                () -> EVENT_ID);
    }

    private static final class StubAdmissionPort implements RoomParticipationAdmissionPort {
        private RoomParticipationAdmissionRequest request;
        private RoomParticipationAdmissionFailure failure;

        @Override
        public RoomParticipationAdmissionOutcome admit(
                RoomParticipationAdmissionRequest request, RoomBotProvisioningAction provisioningAction) {
            this.request = request;
            if (failure != null) {
                return RoomParticipationAdmissionOutcome.rejected(failure);
            }
            UUID botId = provisioningAction.provision(new RoomParticipationAdmissionContext(
                    request.roomId(), request.ownerAccountId(), request.admittedAt(), NOW.plusSeconds(3600),
                    RoomSubmissionTiming.WAIT_UNTIL_EVALUATION,
                    new RoomBotLaunchRules(
                            new java.math.BigDecimal("100000.00"),
                            UUID.fromString("70000000-0000-4000-8000-000000000001"),
                            UUID.fromString("80000000-0000-4000-8000-000000000001"),
                            "precision/v1")));
            return RoomParticipationAdmissionOutcome.accepted(new RoomParticipationAdmission(
                    request.participationId(), request.roomId(), botId, request.ownerAccountId(),
                    request.anonymousAlias(), request.admittedAt()));
        }
    }
}
