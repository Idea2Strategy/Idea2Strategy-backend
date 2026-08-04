package com.idea2strategy.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.adminmcp.AdminMcpProviderPort;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CorporateActionAdminMcpProviderTest {
    private static final String CANDIDATE = "10000000-0000-4000-8000-000000000001";

    @Test
    void acceptsOnlyTheContentBoundApprovalEnvelope() {
        var provider = new CorporateActionAdminMcpProvider();
        var result = provider.invoke(new AdminMcpProviderPort.Request(
                "corporate_action_candidate.approve",
                "CORPORATE_ACTION",
                CANDIDATE,
                "a".repeat(64),
                Map.of(
                        "candidateId", CANDIDATE,
                        "decision", "APPROVE",
                        "evidenceBindings", List.of("b".repeat(64))),
                "approve-1"));

        assertThat(result.status()).isEqualTo(AdminMcpProviderPort.Result.Status.SUCCEEDED);
        assertThat(result.after())
                .containsEntry("candidateId", CANDIDATE)
                .containsEntry("decidedContentHash", "a".repeat(64))
                .containsEntry("decision", "APPROVE")
                .doesNotContainKey("aggregateSequence");
    }

    @Test
    void rejectsCandidateMismatchAndUnboundEvidenceShape() {
        var provider = new CorporateActionAdminMcpProvider();
        var mismatch = provider.invoke(new AdminMcpProviderPort.Request(
                "corporate_action_candidate.approve", "CORPORATE_ACTION", CANDIDATE,
                "a".repeat(64),
                Map.of("candidateId", "10000000-0000-4000-8000-000000000002",
                        "decision", "APPROVE", "evidenceBindings", List.of("b".repeat(64))),
                "approve-2"));
        var missingEvidence = provider.invoke(new AdminMcpProviderPort.Request(
                "corporate_action_candidate.approve", "CORPORATE_ACTION", CANDIDATE,
                "a".repeat(64),
                Map.of("candidateId", CANDIDATE, "decision", "APPROVE",
                        "evidenceBindings", List.of()),
                "approve-3"));

        assertThat(mismatch.code()).isEqualTo("CORPORATE_ACTION_CANDIDATE_MISMATCH");
        assertThat(missingEvidence.code()).isEqualTo("CORPORATE_ACTION_EVIDENCE_INVALID");
    }
}
