package com.idea2strategy.backend.api.sanction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.accountsanction.AccountSanctionAuthorizationPort;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionCommand;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionCommandPort;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionCommandService;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionDecision;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionResult;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionState;
import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AccountSanctionControllerTest {
    private static final UUID ACCOUNT = id(1);
    private static final UUID SANCTION = id(2);
    private static final UUID OPERATOR = id(3);
    private static final UUID CORRELATION = id(4);
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

    @Test
    void bindsTheAuthenticatedOperatorAndReturnsTheStableSanctionReference() throws Exception {
        RecordingPort port = new RecordingPort();
        AccountSanctionAuthorizationPort authorization = (context, permission, at) ->
                new AccountSanctionAuthorizationPort.Decision(
                        true, "AUTHORIZED", "rbac-v1", Set.of(id(7)), Set.of(permission), true, true);
        AccountSanctionCommandService service = new AccountSanctionCommandService(
                port, authorization, effect -> {}, messages -> {}, id(5), id(6),
                Clock.fixed(NOW, ZoneOffset.UTC));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AccountSanctionController(
                        service, () -> Optional.of(new OperatorRequestContext(OPERATOR, true, true))))
                .setControllerAdvice(new AccountSanctionExceptionHandler())
                .build();

        mvc.perform(post("/api/v1/operations/accounts/{accountId}/sanctions", ACCOUNT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sanctionId":"%s","type":"SUSPENSION","reasonCode":"POLICY",
                                 "expiresAt":"2026-08-03T01:00:00Z","correlationId":"%s",
                                 "idempotencyKey":"apply-1","requestHash":"%s","expectedVersion":0}
                                """.formatted(SANCTION, CORRELATION, "a".repeat(64))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SANCTION_APPLIED"))
                .andExpect(jsonPath("$.sanctionReference").value(SANCTION.toString()))
                .andExpect(jsonPath("$.aggregateVersion").value(1));

        assertThat(port.command.requestContext().operatorId()).isEqualTo(OPERATOR);
        assertThat(port.command.accountId()).isEqualTo(ACCOUNT);
    }

    private static final class RecordingPort implements AccountSanctionCommandPort {
        private AccountSanctionCommand command;

        @Override
        public AccountSanctionResult executeAtomically(
                AccountSanctionCommand command,
                Instant evaluatedAt,
                AccountSanctionAuthorizationPort.Decision authorization,
                AccountSanctionDecision decision,
                TransactionalEffects effects) {
            this.command = command;
            AccountSanctionResult result = decision.decide(
                    AccountSanctionState.empty(command.accountId()), authorization);
            effects.publish(result);
            return result;
        }
    }

    private static UUID id(int suffix) {
        return UUID.fromString("a1420000-0000-4000-8000-%012d".formatted(suffix));
    }
}
