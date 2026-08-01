package com.idea2strategy.backend.api.competition;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.competition.RoomInvitationSecrets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecureRoomInvitationSecretIssuerTest {
    @Test
    void issuesDistinct256BitSecretsWithMatchingDigests() {
        var issuer = new SecureRoomInvitationSecretIssuer();

        var first = issuer.issue();
        var second = issuer.issue();

        assertThat(Base64.getUrlDecoder().decode(first.rawValue())).hasSize(32);
        assertThat(first.digest()).isEqualTo(RoomInvitationSecrets.digest(first.rawValue()));
        assertThat(second.rawValue()).isNotEqualTo(first.rawValue());
    }
}
