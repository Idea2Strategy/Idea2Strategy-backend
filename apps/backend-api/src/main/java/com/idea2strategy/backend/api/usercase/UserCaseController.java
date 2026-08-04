package com.idea2strategy.backend.api.usercase;

import com.idea2strategy.backend.api.identity.CustomerAccessPrincipal;
import com.idea2strategy.backend.application.identity.CustomerAccessScope;
import com.idea2strategy.backend.application.identity.SanctionedAccountAccessException;
import com.idea2strategy.backend.application.usercase.UserCaseCommand;
import com.idea2strategy.backend.application.usercase.UserCaseEvidenceReference;
import com.idea2strategy.backend.application.usercase.UserCaseService;
import com.idea2strategy.backend.application.usercase.UserCaseSupplementCommand;
import com.idea2strategy.backend.application.usercase.UserCaseType;
import com.idea2strategy.backend.application.usercase.UserCaseView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cases")
@ConditionalOnBean({UserCaseService.class, CustomerAccessPrincipal.class})
public class UserCaseController {
    private final UserCaseService cases;
    private final CustomerAccessPrincipal principal;

    public UserCaseController(UserCaseService cases, CustomerAccessPrincipal principal) {
        this.cases = Objects.requireNonNull(cases, "cases");
        this.principal = Objects.requireNonNull(principal, "principal");
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserCaseView submit(
            @RequestBody SubmitRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        UUID correlation = correlation(correlationId);
        List<UserCaseEvidenceReference> evidence = references(request.evidence());
        return cases.submit(new UserCaseCommand(
                principal.accountId(request.type() == UserCaseType.APPEAL
                        ? CustomerAccessScope.APPEAL : CustomerAccessScope.STANDARD),
                request.type(), request.subject(), request.description(), evidence,
                idempotencyKey,
                hash("SUBMIT", request.type().name(), request.subject(), request.description(), evidence),
                correlation));
    }

    @PostMapping("/{caseId}/evidence")
    public UserCaseView supplement(
            @PathVariable UUID caseId,
            @RequestBody SupplementRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        List<UserCaseEvidenceReference> evidence = references(request.evidence());
        return cases.supplement(new UserCaseSupplementCommand(
                principal.accountId(CustomerAccessScope.STANDARD), caseId,
                request.expectedVersion(), evidence, idempotencyKey,
                hash("ADD_EVIDENCE", caseId.toString(), Long.toString(request.expectedVersion()), evidence),
                correlation(correlationId)));
    }

    @GetMapping("/{caseId}")
    public UserCaseView detail(@PathVariable UUID caseId) {
        UserCaseView view = cases.detail(principal.accountId(CustomerAccessScope.APPEAL), caseId);
        if (principal.activeSanction() && view.type() != UserCaseType.APPEAL) {
            // Re-enter the central standard-access gate so the denial is audited consistently.
            principal.accountId(CustomerAccessScope.STANDARD);
            throw new SanctionedAccountAccessException();
        }
        return view;
    }

    private static List<UserCaseEvidenceReference> references(List<EvidenceRequest> evidence) {
        return List.copyOf(Objects.requireNonNull(evidence, "evidence")).stream()
                .map(value -> new UserCaseEvidenceReference(
                        value.storageObjectId(), value.sourceDomain(), value.sourceResourceId()))
                .toList();
    }

    private static String hash(String operation, String first, String second, Object... remaining) {
        StringBuilder material = new StringBuilder(operation).append('\n').append(first).append('\n').append(second);
        for (Object value : remaining) {
            if (value instanceof List<?> values) {
                values.stream().map(Object::toString).sorted(Comparator.naturalOrder())
                        .forEach(item -> material.append('\n').append(item));
            } else {
                material.append('\n').append(value);
            }
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static UUID correlation(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID() : UUID.fromString(value);
    }

    public record SubmitRequest(
            UserCaseType type,
            String subject,
            String description,
            List<EvidenceRequest> evidence) {}

    public record SupplementRequest(long expectedVersion, List<EvidenceRequest> evidence) {}

    public record EvidenceRequest(UUID storageObjectId, String sourceDomain, UUID sourceResourceId) {}
}
