package com.idea2strategy.backend.api.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.application.strategy.BasicStrategyCatalogQueryService;
import com.idea2strategy.backend.application.strategy.DelegatedBasicEditOperation;
import com.idea2strategy.backend.application.strategy.DelegatedBasicEditPreview;
import com.idea2strategy.backend.application.strategy.DelegatedBasicStrategyEditService;
import com.idea2strategy.backend.application.strategy.DelegatedStrategyEditor;
import com.idea2strategy.backend.application.strategy.StrategyDocumentQueryService;
import com.idea2strategy.backend.domain.strategy.StrategyDocument;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operation-level Basic editing for delegated external tools.
 *
 * <p>The owner-facing editor writes whole documents through {@link StrategyDocumentController}. A
 * delegated tool may only submit the four official operations, and only through a reviewed preview:
 * {@code apply} recomputes the preview and refuses anything whose hash differs from the one the
 * caller reviewed. The edit sequence travels the same round trip so a delegated apply cannot
 * silently overwrite a concurrent owner edit.
 */
@RestController
@RequestMapping("/api/v1/strategies/{strategyId}/basic-edits")
@ConditionalOnProperty(name = {"spring.datasource.url", "identity.crypto.customer-jwt-signing-key"})
public class DelegatedBasicEditController {
    private final DelegatedBasicStrategyEditService editService;
    private final BasicStrategyCatalogQueryService catalogService;
    private final StrategyDocumentQueryService documentQueryService;
    private final CurrentPrincipal principal;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DelegatedBasicEditController(
            DelegatedBasicStrategyEditService editService,
            BasicStrategyCatalogQueryService catalogService,
            StrategyDocumentQueryService documentQueryService,
            CurrentPrincipal principal) {
        this.editService = editService;
        this.catalogService = catalogService;
        this.documentQueryService = documentQueryService;
        this.principal = principal;
    }

    @PostMapping("/preview")
    public PreviewResponse preview(
            @PathVariable UUID strategyId,
            @RequestBody DelegatedBasicEditRequest request) {
        long expectedEditSequence = resolveExpectedEditSequence(strategyId, request);
        DelegatedBasicEditPreview preview = editService.preview(
                editor(request),
                strategyId,
                expectedEditSequence,
                catalogService.getLatestPublished(),
                operations(request));
        return new PreviewResponse(
                preview.beforeHash(),
                preview.previewHash(),
                readJson(preview.proposedSemanticDocument()),
                preview.changes(),
                preview.valid(),
                expectedEditSequence);
    }

    @PostMapping("/apply")
    public AppliedResponse apply(
            @PathVariable UUID strategyId,
            @RequestBody DelegatedBasicEditRequest request) {
        if (request.previewHash() == null || request.previewHash().isBlank()) {
            throw new IllegalArgumentException("A reviewed preview hash is required to apply an edit");
        }
        StrategyDocument applied = editService.apply(
                editor(request),
                strategyId,
                resolveExpectedEditSequence(strategyId, request),
                catalogService.getLatestPublished(),
                operations(request),
                request.previewHash());
        return new AppliedResponse(
                applied.strategyId(),
                applied.semanticHash(),
                applied.editSequence(),
                applied.updatedAt());
    }

    /**
     * The owner-facing client always knows the sequence it read. The CLI learns it from the preview
     * response and returns it on apply, so an omitted value is only ever the first preview of a
     * round trip; reading it here would defeat the optimistic lock on apply.
     */
    private long resolveExpectedEditSequence(UUID strategyId, DelegatedBasicEditRequest request) {
        if (request.expectedEditSequence() != null) {
            return request.expectedEditSequence();
        }
        if (request.previewHash() != null && !request.previewHash().isBlank()) {
            throw new IllegalArgumentException(
                    "An applied edit must carry the edit sequence returned by its preview");
        }
        return documentQueryService.getOwned(strategyId).editSequence();
    }

    private DelegatedStrategyEditor editor(DelegatedBasicEditRequest request) {
        if (request.authorizationId() == null || request.credentialId() == null) {
            throw new IllegalArgumentException("Delegated authorization and credential are required");
        }
        return new DelegatedStrategyEditor(
                principal.accountId(), request.authorizationId(), request.credentialId());
    }

    private List<DelegatedBasicEditOperation> operations(DelegatedBasicEditRequest request) {
        if (request.operations() == null || request.operations().isEmpty()) {
            throw new IllegalArgumentException("At least one edit operation is required");
        }
        return request.operations().stream()
                .map(operation -> new DelegatedBasicEditOperation(
                        operation.action(),
                        operation.arguments() == null ? Map.of() : operation.arguments()))
                .toList();
    }

    private Map<String, Object> readJson(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Proposed strategy document is invalid", exception);
        }
    }

    public record DelegatedBasicEditRequest(
            UUID authorizationId,
            UUID credentialId,
            Long expectedEditSequence,
            String previewHash,
            List<OperationRequest> operations) {
        @Override
        public String toString() {
            return "DelegatedBasicEditRequest[credential=REDACTED]";
        }
    }

    public record OperationRequest(String action, Map<String, Object> arguments) {}

    public record PreviewResponse(
            String beforeHash,
            String previewHash,
            Map<String, Object> diff,
            List<String> changes,
            boolean valid,
            long expectedEditSequence) {}

    public record AppliedResponse(
            UUID strategyId, String semanticHash, long editSequence, java.time.Instant updatedAt) {}
}
