package com.idea2strategy.backend.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.idea2strategy.backend.application.adminmcp.AdminMcpAuthenticationRejectedException;
import com.idea2strategy.backend.application.adminmcp.AdminMcpExecutionResult;
import com.idea2strategy.backend.application.adminmcp.AdminMcpInvocation;
import com.idea2strategy.backend.application.adminmcp.AdminMcpService;
import com.idea2strategy.backend.application.operatorrbac.CurrentOperatorRbacContext;
import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/mcp/v1/tools")
@ConditionalOnBean({AdminMcpService.class, CurrentOperatorRbacContext.class})
public class AdminMcpController {
    private final AdminMcpService service;
    private final CurrentOperatorRbacContext securityContext;
    private final ObjectMapper canonicalJson;

    public AdminMcpController(
            AdminMcpService service,
            CurrentOperatorRbacContext securityContext,
            ObjectMapper objectMapper) {
        this.service = service;
        this.securityContext = securityContext;
        this.canonicalJson = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    @PostMapping("/{toolName}:invoke")
    public ResponseEntity<InvokeResponse> invoke(
            @PathVariable String toolName,
            @RequestBody InvokeRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationHeader) {
        OperatorRequestContext actor = securityContext.current()
                .filter(OperatorRequestContext::trustedExternalSubject)
                .orElseThrow(AdminMcpAuthenticationRejectedException::new);
        UUID correlationId = correlation(correlationHeader);
        AdminMcpInvocation invocation = new AdminMcpInvocation(
                actor,
                request.registryVersion(),
                toolName,
                request.requestSchemaVersion(),
                request.targetId(),
                request.targetVersion(),
                request.input(),
                correlationId,
                idempotencyKey,
                requestHash(toolName, request));
        AdminMcpExecutionResult result = service.invoke(invocation);
        return ResponseEntity.status(status(result)).body(new InvokeResponse(
                result.status(), result.code(), result.response(), correlationId));
    }

    private String requestHash(String toolName, InvokeRequest request) {
        try {
            byte[] document = canonicalJson.writeValueAsBytes(Map.of(
                    "toolName", toolName,
                    "registryVersion", request.registryVersion(),
                    "requestSchemaVersion", request.requestSchemaVersion(),
                    "targetId", request.targetId(),
                    "targetVersion", request.targetVersion() == null ? "" : request.targetVersion(),
                    "input", request.input()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(document));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("MCP request cannot be canonicalized", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static UUID correlation(String value) {
        return value == null || value.isBlank()
                ? UUID.randomUUID()
                : UUID.fromString(value.trim());
    }

    private static HttpStatus status(AdminMcpExecutionResult result) {
        if (result.status() != AdminMcpExecutionResult.Status.REJECTED) {
            return HttpStatus.OK;
        }
        if (result.code().contains("TIMEOUT") || result.code().contains("UNAVAILABLE")) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (result.code().contains("SCHEMA") || result.code().contains("VERSION_REQUIRED")) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.FORBIDDEN;
    }

    public record InvokeRequest(
            String registryVersion,
            String requestSchemaVersion,
            String targetId,
            Long targetVersion,
            Map<String, Object> input) {}

    public record InvokeResponse(
            AdminMcpExecutionResult.Status status,
            String code,
            Map<String, Object> result,
            UUID correlationId) {}
}
