package com.idea2strategy.backend.application.competition;

public interface RoomParticipationAdmissionPort {
    RoomParticipationAdmissionOutcome admit(
            RoomParticipationAdmissionRequest request, RoomBotProvisioningAction provisioningAction);
}
