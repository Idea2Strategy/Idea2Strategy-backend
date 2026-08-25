package com.idea2strategy.backend.api.competition;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.competition.ConsumedRoomInvitation;
import com.idea2strategy.backend.application.competition.RoomInvitationIssueRequest;
import com.idea2strategy.backend.application.competition.RoomInvitationPort;
import com.idea2strategy.backend.application.competition.RoomInvitationRecord;
import com.idea2strategy.backend.application.competition.RoomInvitationSecret;
import com.idea2strategy.backend.application.competition.RoomInvitationSecrets;
import com.idea2strategy.backend.application.competition.RoomInvitationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RoomInvitationControllerTest {
    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");
    private static final UUID ROOM_ID = UUID.fromString("74000000-0000-4000-8000-000000000001");
    private static final UUID OWNER_ID = UUID.fromString("74000000-0000-4000-8000-000000000002");
    private static final UUID INVITATION_ID = UUID.fromString("74000000-0000-4000-8000-000000000003");

    @Test
    void issuesRevokesAndConsumesOneTimeInvitations() throws Exception {
        var port = new StubInvitationPort();
        MockMvc mvc = mvc(port);

        mvc.perform(post("/api/v1/competition/rooms/{roomId}/invitations", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"credentialType":"LINK","validitySeconds":3600}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(INVITATION_ID.toString()))
                .andExpect(jsonPath("$.secret").value("one-time-secret"))
                .andExpect(jsonPath("$.credentialType").value("LINK"));

        mvc.perform(delete(
                        "/api/v1/competition/rooms/{roomId}/invitations/{invitationId}",
                        ROOM_ID,
                        INVITATION_ID))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/competition/rooms/invitations/consume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"secret":"one-time-secret"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invitationId").value(INVITATION_ID.toString()))
                .andExpect(jsonPath("$.roomId").value(ROOM_ID.toString()));
    }

    @Test
    void mapsMissingOrReusedInvitationToGone() throws Exception {
        var port = new StubInvitationPort();
        port.consumeAllowed = false;

        mvc(port).perform(post("/api/v1/competition/rooms/invitations/consume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"secret":"missing"}
                        """))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.title").value("Room invitation unavailable"));
    }

    private static MockMvc mvc(StubInvitationPort port) {
        var service = new RoomInvitationService(
                port,
                () -> OWNER_ID,
                () -> new RoomInvitationSecret(
                        "one-time-secret", RoomInvitationSecrets.digest("one-time-secret")),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> INVITATION_ID);
        return MockMvcBuilders.standaloneSetup(new RoomInvitationController(service))
                .setControllerAdvice(new CompetitionRoomExceptionHandler())
                .build();
    }

    private static final class StubInvitationPort implements RoomInvitationPort {
        private boolean consumeAllowed = true;

        @Override
        public Optional<RoomInvitationRecord> issue(RoomInvitationIssueRequest request) {
            return Optional.of(new RoomInvitationRecord(
                    request.id(), request.roomId(), request.credentialType(), request.requestedExpiresAt()));
        }

        @Override
        public boolean revoke(UUID roomId, UUID invitationId, UUID actorAccountId, Instant revokedAt) {
            return true;
        }

        @Override
        public Optional<ConsumedRoomInvitation> consume(
                String credentialDigest, UUID consumerAccountId, Instant consumedAt) {
            if (!consumeAllowed || !credentialDigest.equals(RoomInvitationSecrets.digest("one-time-secret"))) {
                return Optional.empty();
            }
            return Optional.of(new ConsumedRoomInvitation(INVITATION_ID, ROOM_ID));
        }
    }
}
