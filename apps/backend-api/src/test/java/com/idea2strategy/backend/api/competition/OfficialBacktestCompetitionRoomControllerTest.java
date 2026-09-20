package com.idea2strategy.backend.api.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.competition.BacktestEvaluationPlanDefinition;
import com.idea2strategy.backend.application.competition.OfficialBacktestCompetitionRoomCreationService;
import com.idea2strategy.backend.application.competition.ScoringTemplateCatalogQueryPort;
import com.idea2strategy.backend.application.competition.ScoringTemplateCatalogRecord;
import com.idea2strategy.backend.application.competition.ScoringTemplateCatalogService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OfficialBacktestCompetitionRoomControllerTest {
    private static final Instant NOW = Instant.parse("2026-08-10T04:00:00Z");
    private static final UUID ROOM = id(1);
    private static final UUID OPERATOR = id(2);
    private static final UUID TEMPLATE = id(3);
    private static final AtomicReference<BacktestEvaluationPlanDefinition> SAVED_PLAN = new AtomicReference<>();

    @Test
    void acceptsHiddenOfficialInputsOnlyOnTheOperatorControlPlane() throws Exception {
        SAVED_PLAN.set(null);
        var mvc = MockMvcBuilders.standaloneSetup(
                        new OfficialBacktestCompetitionRoomController(service()))
                .setControllerAdvice(new CompetitionRoomExceptionHandler())
                .build();

        mvc.perform(post("/api/v1/operations/competition/backtest-rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ROOM.toString()))
                .andExpect(jsonPath("$.accessType").value("PUBLIC"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.lockedAt").value(NOW.toString()));

        assertThat(SAVED_PLAN.get()).isNotNull();
        assertThat(SAVED_PLAN.get().periods()).hasSize(2);
        assertThat(SAVED_PLAN.get().periods()).allSatisfy(period ->
                assertThat(period.datasets()).singleElement());
    }

    private static OfficialBacktestCompetitionRoomCreationService service() {
        var catalog = new ScoringTemplateCatalogService(
                new Catalog(), Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
        return new OfficialBacktestCompetitionRoomCreationService(
                (room, plan) -> SAVED_PLAN.set(plan),
                catalog,
                () -> Optional.of(OPERATOR),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> ROOM);
    }

    private static String request() {
        return """
                {
                  "name":"INT04-A official backtest room",
                  "accessType":"PUBLIC",
                  "scoringTemplateVersionId":"a0420000-0000-4000-8000-000000000003",
                  "initialCashAmount":100000.00000000,
                  "botParticipationLimit":10,
                  "perAccountBotLimit":2,
                  "feePolicyId":"a0420000-0000-4000-8000-000000000004",
                  "buyingPowerBufferPolicyId":"a0420000-0000-4000-8000-000000000005",
                  "eligibilityCriteria":{"minimumAccountState":"ACTIVE"},
                  "marketScope":{"market":"US"},
                  "precisionRulesVersion":"v1",
                  "recruitmentOpensAt":"2026-08-10T04:00:10Z",
                  "participationOpensAt":"2026-08-10T04:00:20Z",
                  "evaluationStartsAt":"2026-08-10T04:00:30Z",
                  "participationClosesAt":"2026-08-10T04:02:00Z",
                  "evaluationEndsAt":"2026-08-10T04:03:00Z",
                  "finalizationDeadlineAt":"2026-08-10T04:04:00Z",
                  "timezoneName":"UTC",
                  "planVersion":"int04-a.v1",
                  "planHash":"sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                  "commitmentHash":"sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                  "commitmentNonceCiphertext":"kms:ciphertext:int04-a",
                  "nonceKeyVersion":1,
                  "periods":[
                    {
                      "evaluationStart":"2024-01-01",
                      "evaluationEnd":"2024-06-30",
                      "importanceWeight":0.5,
                      "inputSetHash":"sha256:1111111111111111111111111111111111111111111111111111111111111111",
                      "datasets":[{
                        "manifestId":"a0420000-0000-4000-8000-000000000006",
                        "purposeCode":"MARKET_BARS",
                        "lockedDatasetHash":"sha256:2222222222222222222222222222222222222222222222222222222222222222"
                      }],
                      "featureMaterializations":[]
                    },
                    {
                      "evaluationStart":"2024-07-01",
                      "evaluationEnd":"2024-12-31",
                      "importanceWeight":0.5,
                      "inputSetHash":"sha256:3333333333333333333333333333333333333333333333333333333333333333",
                      "datasets":[{
                        "manifestId":"a0420000-0000-4000-8000-000000000007",
                        "purposeCode":"MARKET_BARS",
                        "lockedDatasetHash":"sha256:4444444444444444444444444444444444444444444444444444444444444444"
                      }],
                      "featureMaterializations":[]
                    }
                  ]
                }
                """;
    }

    private static UUID id(int suffix) {
        return UUID.fromString("a0420000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    private static final class Catalog implements ScoringTemplateCatalogQueryPort {
        private final ScoringTemplateCatalogRecord record = new ScoringTemplateCatalogRecord(
                TEMPLATE,
                "SINGLE_TOTAL_RETURN_V1",
                "int04-a",
                """
                {
                  "kind":"SINGLE",
                  "calculationRulesVersion":"official-room-scoring.v1",
                  "components":[
                    {"metric":"TOTAL_RETURN","direction":"HIGHER_IS_BETTER","coefficient":1}
                  ],
                  "adjustments":[]
                }
                """,
                "sha256:" + "a".repeat(64),
                NOW.minusSeconds(60),
                null);

        @Override
        public List<ScoringTemplateCatalogRecord> findSelectableAt(Instant at) {
            return List.of(record);
        }

        @Override
        public Optional<ScoringTemplateCatalogRecord> findSelectableById(UUID id, Instant at) {
            return record.id().equals(id) ? Optional.of(record) : Optional.empty();
        }
    }
}
