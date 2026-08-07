package com.idea2strategy.backend.api.usercase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.usercase.UserCaseCommand;
import com.idea2strategy.backend.api.identity.CustomerAccessPrincipal;
import com.idea2strategy.backend.application.identity.CustomerAccessScope;
import com.idea2strategy.backend.application.identity.SanctionedAccountAccessException;
import com.idea2strategy.backend.application.usercase.UserCaseService;
import com.idea2strategy.backend.application.usercase.UserCaseStatus;
import com.idea2strategy.backend.application.usercase.UserCaseStore;
import com.idea2strategy.backend.application.usercase.UserCaseSupplementCommand;
import com.idea2strategy.backend.application.usercase.UserCaseType;
import com.idea2strategy.backend.application.usercase.UserCaseView;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UserCaseControllerTest {
    private static final UUID ACCOUNT = id(1);
    private static final UUID CASE = id(2);
    private static final UUID OBJECT = id(3);
    private static final UUID SOURCE = id(4);
    private static final Instant NOW = Instant.parse("2026-08-02T15:00:00Z");

    @Test
    void bindsTheAuthenticatedAccountAndNeverAcceptsAccountIdentityFromTheBody() throws Exception {
        var store = new RecordingStore();
        MockMvc mvc = mvc(store);

        mvc.perform(post("/api/v1/cases")
                        .header("Idempotency-Key", "submit-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"REPORT","subject":"Problem","description":"Details",
                                 "evidence":[{"storageObjectId":"%s","sourceDomain":"BACKTEST_RUN",
                                 "sourceResourceId":"%s"}]}
                                """.formatted(OBJECT, SOURCE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountId").value(ACCOUNT.toString()));

        assertThat(store.submitted.accountId()).isEqualTo(ACCOUNT);
        assertThat(store.submitted.requestHash()).matches("[0-9a-f]{64}");
        assertThat(store.submitted.evidenceReferences().getFirst().sourceResourceId()).isEqualTo(SOURCE);
    }

    @Test
    void returnsTheSameNotFoundShapeForEveryUnavailableOwnedLookup() throws Exception {
        MockMvc mvc = mvc(new RecordingStore());

        mvc.perform(get("/api/v1/cases/{caseId}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("RESOURCE_NOT_AVAILABLE"));
    }

    @Test
    void sanctionedPrincipalCannotUseAppealDetailScopeToReadAnotherCaseType() {
        var store = new RecordingStore();
        store.found = Optional.of(store.view());
        var service = new UserCaseService(store, Clock.fixed(NOW, ZoneOffset.UTC));
        CustomerAccessPrincipal principal = new CustomerAccessPrincipal() {
            @Override public UUID accountId() { return accountId(CustomerAccessScope.STANDARD); }
            @Override public UUID accountId(CustomerAccessScope accessScope) {
                if (accessScope == CustomerAccessScope.STANDARD) {
                    throw new SanctionedAccountAccessException();
                }
                return ACCOUNT;
            }
            @Override public boolean activeSanction() { return true; }
        };

        assertThatThrownBy(() -> new UserCaseController(service, principal).detail(CASE))
                .isInstanceOf(SanctionedAccountAccessException.class);
    }

    private static MockMvc mvc(RecordingStore store) {
        var service = new UserCaseService(store, Clock.fixed(NOW, ZoneOffset.UTC));
        CustomerAccessPrincipal principal = new CustomerAccessPrincipal() {
            @Override public UUID accountId() { return ACCOUNT; }
            @Override public UUID accountId(CustomerAccessScope accessScope) { return ACCOUNT; }
            @Override public boolean activeSanction() { return false; }
        };
        return MockMvcBuilders.standaloneSetup(new UserCaseController(service, principal))
                .setControllerAdvice(new UserCaseExceptionHandler())
                .build();
    }

    private static final class RecordingStore implements UserCaseStore {
        private UserCaseCommand submitted;
        private Optional<UserCaseView> found = Optional.empty();

        @Override
        public CommandResult submit(UserCaseCommand command, Instant now) {
            submitted = command;
            return new CommandResult(CommandResult.Outcome.APPLIED, view());
        }

        @Override
        public CommandResult supplement(UserCaseSupplementCommand command, Instant now) {
            return new CommandResult(CommandResult.Outcome.APPLIED, view());
        }

        @Override
        public Optional<UserCaseView> findOwned(UUID accountId, UUID caseId) {
            return found;
        }

        private UserCaseView view() {
            return new UserCaseView(CASE, ACCOUNT, UserCaseType.REPORT, UserCaseStatus.OPEN,
                    1, List.of(OBJECT), NOW);
        }
    }

    private static UUID id(int suffix) {
        return UUID.fromString("a1900000-0000-4000-8000-" + String.format("%012d", suffix));
    }
}
