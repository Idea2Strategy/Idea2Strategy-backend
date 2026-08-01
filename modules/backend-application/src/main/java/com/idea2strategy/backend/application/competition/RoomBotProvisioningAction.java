package com.idea2strategy.backend.application.competition;

import java.util.UUID;

@FunctionalInterface
public interface RoomBotProvisioningAction {
    UUID provision(RoomParticipationAdmissionContext context);
}
