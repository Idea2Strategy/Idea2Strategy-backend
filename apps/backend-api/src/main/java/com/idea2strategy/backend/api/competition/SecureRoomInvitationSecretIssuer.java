package com.idea2strategy.backend.api.competition;

import com.idea2strategy.backend.application.competition.RoomInvitationSecret;
import com.idea2strategy.backend.application.competition.RoomInvitationSecretIssuer;
import com.idea2strategy.backend.application.competition.RoomInvitationSecrets;
import java.security.SecureRandom;
import java.util.Base64;

final class SecureRoomInvitationSecretIssuer implements RoomInvitationSecretIssuer {
    private static final int SECRET_SIZE_BYTES = 32;

    private final SecureRandom secureRandom;

    SecureRoomInvitationSecretIssuer() {
        this(new SecureRandom());
    }

    SecureRoomInvitationSecretIssuer(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    @Override
    public RoomInvitationSecret issue() {
        byte[] bytes = new byte[SECRET_SIZE_BYTES];
        secureRandom.nextBytes(bytes);
        String rawValue = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new RoomInvitationSecret(rawValue, RoomInvitationSecrets.digest(rawValue));
    }
}
