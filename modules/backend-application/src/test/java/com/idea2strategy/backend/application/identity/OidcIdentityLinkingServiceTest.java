package com.idea2strategy.backend.application.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OidcIdentityLinkingServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");

    @Test
    void startsPendingLinkWithOnlyProtectedSubjectAndExplicitReauthenticationProof() {
        UUID accountId = UUID.randomUUID();
        UUID currentLoginId = UUID.randomUUID();
        var queries = new OidcIdentityQueryPort() {
            @Override
            public Optional<OidcProvider> findProvider(String providerCode) {
                return Optional.of(new OidcProvider((short) 2, "EXAMPLE", "https://issuer.example", true));
            }

            @Override
            public Optional<OidcLoginAccount> findActiveLogin(short providerId, String subjectHmac) {
                return Optional.empty();
            }
        };
        var commands = new RecordingLinkCommands();
        var service = new OidcIdentityLinkingService(
                queries,
                commands,
                ignored -> new ProtectedOidcSubject("subject-hmac", (short) 3),
                Clock.fixed(NOW, ZoneOffset.UTC));

        UUID pendingId = service.start(new StartOidcLinkCommand(
                accountId,
                currentLoginId,
                "EXAMPLE",
                "https://issuer.example",
                "raw-subject",
                "existing@example.com",
                UUID.randomUUID()));

        assertThat(commands.pendingLinks).singleElement().satisfies(link -> {
            assertThat(link.id()).isEqualTo(pendingId);
            assertThat(link.accountId()).isEqualTo(accountId);
            assertThat(link.reauthenticatedLoginIdentityId()).isEqualTo(currentLoginId);
            assertThat(link.subjectHmac()).isEqualTo("subject-hmac");
            assertThat(link.toString()).doesNotContain("raw-subject", "existing@example.com");
        });
    }

    private static final class RecordingLinkCommands implements OidcIdentityCommandPort {
        private final List<PendingOidcLink> pendingLinks = new ArrayList<>();

        @Override
        public void createPendingLink(PendingOidcLink link) {
            pendingLinks.add(link);
        }

        @Override
        public long activatePendingLink(ActivateOidcLink command) {
            return 2;
        }
    }
}
