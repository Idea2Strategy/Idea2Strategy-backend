package com.idea2strategy.backend.api.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.strategy.BasicStrategyDraftCommandService;
import com.idea2strategy.backend.application.strategy.StrategyDocumentQueryService;
import com.idea2strategy.backend.application.strategy.StrategyEditLeaseService;
import com.idea2strategy.backend.domain.strategy.StrategyDocument;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/strategies/{strategyId}")
@ConditionalOnProperty(name = {"spring.datasource.url", "identity.crypto.customer-jwt-signing-key"})
public class StrategyDocumentController {
    private final StrategyDocumentQueryService queryService;
    private final BasicStrategyDraftCommandService commandService;
    private final StrategyEditLeaseService leaseService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StrategyDocumentController(
            StrategyDocumentQueryService queryService,
            BasicStrategyDraftCommandService commandService,
            StrategyEditLeaseService leaseService) {
        this.queryService = queryService;
        this.commandService = commandService;
        this.leaseService = leaseService;
    }

    @GetMapping("/document")
    public StrategyDocumentResponse getDocument(@PathVariable UUID strategyId) {
        return response(queryService.getOwned(strategyId));
    }

    @PutMapping("/document")
    public StrategyDocumentResponse saveDocument(
            @PathVariable UUID strategyId,
            @RequestBody SaveStrategyDocumentRequest request) {
        if (request.semanticDocument() == null || request.presentationDocument() == null) {
            throw new IllegalArgumentException("Semantic and presentation documents are required");
        }
        return response(commandService.saveExplicitly(
                strategyId,
                request.expectedEditSequence(),
                request.leaseToken(),
                writeJson(request.semanticDocument()),
                writeJson(request.presentationDocument()),
                BasicStrategyDraftCommandService.SEMANTIC_SCHEMA_VERSION,
                BasicStrategyDraftCommandService.PRESENTATION_SCHEMA_VERSION));
    }

    @PostMapping("/edit-lease")
    public ResponseEntity<EditLeaseResponse> acquireLease(@PathVariable UUID strategyId) {
        var grant = leaseService.acquire(strategyId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new EditLeaseResponse(grant.token(), grant.expiresAt()));
    }

    @PutMapping("/edit-lease")
    public EditLeaseResponse heartbeatLease(
            @PathVariable UUID strategyId,
            @RequestBody EditLeaseTokenRequest request) {
        requireLeaseToken(request.leaseToken());
        return new EditLeaseResponse(null, leaseService.heartbeat(strategyId, request.leaseToken()));
    }

    @DeleteMapping("/edit-lease")
    public ResponseEntity<Void> releaseLease(
            @PathVariable UUID strategyId,
            @RequestBody EditLeaseTokenRequest request) {
        requireLeaseToken(request.leaseToken());
        leaseService.release(strategyId, request.leaseToken());
        return ResponseEntity.noContent().build();
    }

    private StrategyDocumentResponse response(StrategyDocument document) {
        return new StrategyDocumentResponse(
                document.strategyId(),
                readJson(document.semanticDocument()),
                readJson(document.presentationDocument()),
                document.semanticSchemaVersion(),
                document.presentationSchemaVersion(),
                document.semanticHash(),
                document.presentationHash(),
                document.editSequence(),
                document.updatedAt());
    }

    private Map<String, Object> readJson(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored strategy document is invalid", exception);
        }
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Strategy document could not be serialized", exception);
        }
    }

    private static void requireLeaseToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Strategy edit lease token is required");
        }
    }

    public record SaveStrategyDocumentRequest(
            long expectedEditSequence,
            String leaseToken,
            Map<String, Object> semanticDocument,
            Map<String, Object> presentationDocument) {
        @Override
        public String toString() {
            return "SaveStrategyDocumentRequest[content=REDACTED]";
        }
    }

    public record EditLeaseTokenRequest(String leaseToken) {
        @Override
        public String toString() {
            return "EditLeaseTokenRequest[token=REDACTED]";
        }
    }

    public record EditLeaseResponse(String leaseToken, Instant expiresAt) {}

    public record StrategyDocumentResponse(
            UUID strategyId,
            Map<String, Object> semanticDocument,
            Map<String, Object> presentationDocument,
            String semanticSchemaVersion,
            String presentationSchemaVersion,
            String semanticHash,
            String presentationHash,
            long editSequence,
            Instant updatedAt) {}
}
