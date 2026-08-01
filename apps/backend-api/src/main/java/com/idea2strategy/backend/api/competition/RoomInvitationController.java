package com.idea2strategy.backend.api.competition;

import com.idea2strategy.backend.application.competition.ConsumedRoomInvitation;
import com.idea2strategy.backend.application.competition.IssuedRoomInvitation;
import com.idea2strategy.backend.application.competition.RoomInvitationService;
import com.idea2strategy.backend.domain.competition.RoomInvitationCredentialType;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/competition/rooms")
@ConditionalOnBean(RoomInvitationService.class)
public class RoomInvitationController {
    private final RoomInvitationService invitationService;

    public RoomInvitationController(RoomInvitationService invitationService) {
        this.invitationService = invitationService;
    }

    @PostMapping("/{roomId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedRoomInvitation issue(
            @PathVariable UUID roomId, @RequestBody IssueInvitationRequest request) {
        return invitationService.issue(
                roomId, request.credentialType(), Duration.ofSeconds(request.validitySeconds()));
    }

    @DeleteMapping("/{roomId}/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID roomId, @PathVariable UUID invitationId) {
        invitationService.revoke(roomId, invitationId);
    }

    @PostMapping("/invitations/consume")
    public ConsumedRoomInvitation consume(@RequestBody ConsumeInvitationRequest request) {
        return invitationService.consume(request.secret());
    }

    public record IssueInvitationRequest(
            RoomInvitationCredentialType credentialType, long validitySeconds) {}

    public record ConsumeInvitationRequest(String secret) {}
}
