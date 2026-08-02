package com.idea2strategy.backend.application.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OperatorRoomManagementServiceTest {
    private static final UUID ROOM_ID = id(1);
    private static final UUID OPERATOR_ID = id(2);
    private static final Instant NOW = Instant.parse("2026-08-02T05:00:00Z");

    @Test
    void separatesReadAndManagePermissionsAndNeverUsesACustomerPrincipal() {
        var authorizedPermission = new AtomicReference<String>();
        OperatorRoomAuthorizationPort authorization = (operator, permission, action, room, at) -> {
            assertThat(operator).isEqualTo(OPERATOR_ID);
            assertThat(room).isEqualTo(ROOM_ID);
            assertThat(at).isEqualTo(NOW);
            authorizedPermission.set(permission);
            return true;
        };
        var termination = new StubTerminationPort();
        var service = new OperatorRoomManagementService(
                authorization,
                roomId -> Optional.of(view()),
                termination,
                () -> Optional.of(OPERATOR_ID),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.view(ROOM_ID).room().roomId()).isEqualTo(ROOM_ID);
        assertThat(authorizedPermission.get()).isEqualTo(OperatorRoomPermissions.READ);

        service.cancel(ROOM_ID, "OPERATOR_CANCELLED");
        assertThat(authorizedPermission.get()).isEqualTo(OperatorRoomPermissions.MANAGE);
        assertThat(termination.operatorId).isEqualTo(OPERATOR_ID);
    }

    @Test
    void deniesMissingPrincipalPermissionAndNonOfficialRooms() {
        var noPrincipal = new OperatorRoomManagementService(
                (operator, permission, action, room, at) -> true,
                roomId -> Optional.of(view()),
                new StubTerminationPort(),
                Optional::empty,
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> noPrincipal.view(ROOM_ID))
                .isInstanceOf(OperatorAuthorizationException.class);

        var denied = new OperatorRoomManagementService(
                (operator, permission, action, room, at) -> false,
                roomId -> Optional.of(view()),
                new StubTerminationPort(),
                () -> Optional.of(OPERATOR_ID),
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> denied.view(ROOM_ID))
                .isInstanceOf(OperatorAuthorizationException.class);

        var absent = new OperatorRoomManagementService(
                (operator, permission, action, room, at) -> true,
                roomId -> Optional.empty(),
                new StubTerminationPort(),
                () -> Optional.of(OPERATOR_ID),
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> absent.view(ROOM_ID))
                .isInstanceOf(RoomTerminationAccessException.class);
    }

    private static OperatorRoomView view() {
        return new OperatorRoomView(
                new OperatorRoomView.RoomSummary(
                        ROOM_ID, "official", "LIVE_PAPER", "PUBLIC", "ENDED",
                        NOW.minusSeconds(7200), NOW.minusSeconds(3600), NOW,
                        NOW, null, null, id(3), "rules"),
                List.of(), List.of(), null);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("95000000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    private static final class StubTerminationPort implements RoomTerminationPort {
        private UUID operatorId;

        @Override
        public RoomTerminationResult withdrawOwned(
                UUID roomId, UUID participationId, UUID ownerAccountId, ParticipationExitAction action,
                String reasonCode, Instant occurredAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RoomTerminationResult cancelOwned(
                UUID roomId, UUID creatorAccountId, String reasonCode, Instant occurredAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RoomTerminationResult cancelOfficial(
                UUID roomId, UUID operatorId, String reasonCode, Instant occurredAt) {
            this.operatorId = operatorId;
            return new RoomTerminationResult(roomId, 0, occurredAt);
        }

        @Override
        public RoomTerminationResult expelOwned(
                UUID roomId, UUID participationId, UUID creatorAccountId, Instant occurredAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RoomTerminationResult invalidate(
                UUID roomId, UUID operatorId, String reasonCode, Instant occurredAt) {
            throw new UnsupportedOperationException();
        }
    }
}
