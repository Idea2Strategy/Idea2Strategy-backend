package com.idea2strategy.backend.admin;

import com.idea2strategy.backend.application.adminmcp.AdminMcpProviderPort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Strict provider-side validation for the corporate-action approval relay. */
final class CorporateActionAdminMcpProvider implements AdminMcpProviderPort {
    @Override
    public Result invoke(Request request) {
        if (!"corporate_action_candidate.approve".equals(request.toolName())) {
            return rejected("CORPORATE_ACTION_TOOL_UNSUPPORTED");
        }
        try {
            UUID.fromString(request.targetId());
            if (!request.targetId().equals(request.input().get("candidateId"))) {
                return rejected("CORPORATE_ACTION_CANDIDATE_MISMATCH");
            }
            if (request.decidedContentHash() == null
                    || !request.decidedContentHash().matches("[0-9a-f]{64}")) {
                return rejected("CORPORATE_ACTION_CONTENT_HASH_INVALID");
            }
            String decision = String.valueOf(request.input().get("decision"));
            if (!decision.equals("APPROVE") && !decision.equals("WITHDRAW")) {
                return rejected("CORPORATE_ACTION_DECISION_UNSUPPORTED");
            }
            Object evidenceValue = request.input().get("evidenceBindings");
            if (!(evidenceValue instanceof List<?> evidence) || evidence.isEmpty()
                    || evidence.stream().anyMatch(item -> !String.valueOf(item).matches("[0-9a-f]{64}"))) {
                return rejected("CORPORATE_ACTION_EVIDENCE_INVALID");
            }
            Object sequenceValue = request.input().get("aggregateSequence");
            if (!(sequenceValue instanceof Number sequence) || sequence.longValue() < 1) {
                return rejected("CORPORATE_ACTION_SEQUENCE_INVALID");
            }
            Object supersedes = request.input().get("supersedesCandidateId");
            if (supersedes != null) {
                UUID.fromString(String.valueOf(supersedes));
            }

            Map<String, Object> after = new LinkedHashMap<>();
            after.put("candidateId", request.targetId());
            after.put("decision", decision);
            after.put("decidedContentHash", request.decidedContentHash());
            after.put("evidenceBindings", List.copyOf(evidence));
            after.put("aggregateSequence", sequence.longValue());
            after.put("status", decision.equals("APPROVE") ? "APPROVED" : "WITHDRAWN");
            if (supersedes != null) {
                after.put("supersedesCandidateId", String.valueOf(supersedes));
            }
            Object rationale = request.input().get("rationale");
            if (rationale != null && !String.valueOf(rationale).isBlank()) {
                after.put("rationale", String.valueOf(rationale).trim());
            }
            return new Result(
                    Result.Status.SUCCEEDED,
                    "CORPORATE_ACTION_DECISION_ACCEPTED",
                    Map.of("candidateId", request.targetId(), "status", "REVIEW_REQUIRED"),
                    after);
        } catch (IllegalArgumentException exception) {
            return rejected("CORPORATE_ACTION_ENVELOPE_INVALID");
        }
    }

    private static Result rejected(String code) {
        return new Result(Result.Status.REJECTED, code, Map.of(), Map.of());
    }
}
