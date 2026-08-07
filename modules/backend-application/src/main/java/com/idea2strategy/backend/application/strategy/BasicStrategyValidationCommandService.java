package com.idea2strategy.backend.application.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.application.common.IdGenerator;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.BasicBlockGroup;
import com.idea2strategy.backend.domain.strategy.StrategyMode;
import com.idea2strategy.backend.domain.strategy.StrategyValidationFinding;
import com.idea2strategy.backend.domain.strategy.StrategyValidationFinding.Severity;
import com.idea2strategy.backend.domain.strategy.StrategyValidationRun;
import com.idea2strategy.backend.domain.strategy.StrategyValidationStatus;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class BasicStrategyValidationCommandService {
    private final StrategyValidationRunCommandPort validationCommandPort;
    private final StrategyQueryPort strategyQueryPort;
    private final StrategyDocumentQueryPort documentQueryPort;
    private final CurrentPrincipal principal;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final BasicBlockAssemblyValidator assemblyValidator;
    private final BasicBacktestCapabilityValidator backtestValidator;
    private final ObjectMapper objectMapper;

    public BasicStrategyValidationCommandService(
            StrategyValidationRunCommandPort validationCommandPort,
            StrategyQueryPort strategyQueryPort,
            StrategyDocumentQueryPort documentQueryPort,
            CurrentPrincipal principal,
            IdGenerator idGenerator,
            Clock clock) {
        this(
                validationCommandPort,
                strategyQueryPort,
                documentQueryPort,
                principal,
                idGenerator,
                clock,
                new BasicBlockAssemblyValidator(),
                new BasicBacktestCapabilityValidator(),
                new ObjectMapper());
    }

    BasicStrategyValidationCommandService(
            StrategyValidationRunCommandPort validationCommandPort,
            StrategyQueryPort strategyQueryPort,
            StrategyDocumentQueryPort documentQueryPort,
            CurrentPrincipal principal,
            IdGenerator idGenerator,
            Clock clock,
            BasicBlockAssemblyValidator assemblyValidator,
            BasicBacktestCapabilityValidator backtestValidator,
            ObjectMapper objectMapper) {
        this.validationCommandPort = Objects.requireNonNull(validationCommandPort, "validationCommandPort");
        this.strategyQueryPort = Objects.requireNonNull(strategyQueryPort, "strategyQueryPort");
        this.documentQueryPort = Objects.requireNonNull(documentQueryPort, "documentQueryPort");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.assemblyValidator = Objects.requireNonNull(assemblyValidator, "assemblyValidator");
        this.backtestValidator = Objects.requireNonNull(backtestValidator, "backtestValidator");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public StrategyValidationRun validate(
            UUID strategyId,
            BasicStrategyCatalog catalog,
            BacktestDataCoverage coverage) {
        UUID accountId = principal.accountId();
        var strategy = strategyQueryPort.findOwnedById(strategyId, accountId)
                .orElseThrow(() -> new NoSuchElementException("Strategy not found"));
        if (strategy.mode() != StrategyMode.BASIC) {
            throw new IllegalArgumentException("Strategy must use BASIC mode");
        }
        var document = documentQueryPort.findOwnedByStrategyId(strategyId, accountId)
                .orElseThrow(() -> new NoSuchElementException("Strategy document not found"));

        return validateDocument(
                strategyId,
                accountId,
                document.semanticDocument(),
                document.semanticHash(),
                document.editSequence(),
                catalog,
                coverage,
                true);
    }

    /**
     * Validates the editor's current unsaved document without creating a durable validation run.
     * The client revision is echoed as {@code requestedEditSequence}, allowing the UI to discard a
     * response that arrives after a newer block edit.
     */
    public StrategyValidationRun preview(
            UUID strategyId,
            BasicStrategyCatalog catalog,
            BacktestDataCoverage coverage,
            String semanticDocument,
            long clientRevision) {
        if (clientRevision < 0) {
            throw new IllegalArgumentException("clientRevision must not be negative");
        }
        UUID accountId = principal.accountId();
        var strategy = strategyQueryPort.findOwnedById(strategyId, accountId)
                .orElseThrow(() -> new NoSuchElementException("Strategy not found"));
        if (strategy.mode() != StrategyMode.BASIC) {
            throw new IllegalArgumentException("Strategy must use BASIC mode");
        }
        String canonicalSemantic = StrategyDocumentJson.canonicalize(semanticDocument);
        return validateDocument(
                strategyId,
                accountId,
                canonicalSemantic,
                StrategyDocumentJson.sha256(canonicalSemantic),
                clientRevision,
                catalog,
                coverage,
                false);
    }

    private StrategyValidationRun validateDocument(
            UUID strategyId,
            UUID accountId,
            String semanticDocument,
            String semanticHash,
            long requestedEditSequence,
            BasicStrategyCatalog catalog,
            BacktestDataCoverage coverage,
            boolean persist) {

        var findings = new ArrayList<StrategyValidationFinding>();
        BasicBlockAssembly assembly = parseAssembly(semanticDocument, findings);
        if (assembly != null) {
            var assemblyResult = assemblyValidator.validate(assembly, catalog);
            assemblyResult.issues().forEach(issue -> findings.add(new StrategyValidationFinding(
                    Severity.BLOCKING_ERROR,
                    issue.code(),
                    issue.location(),
                    issue.message(),
                    List.of())));
            if (assemblyResult.valid()) {
                var backtestResult = backtestValidator.validate(assembly, catalog, coverage);
                backtestResult.issues().forEach(issue -> findings.add(new StrategyValidationFinding(
                        backtestSeverity(issue.code()),
                        issue.code(),
                        issue.location(),
                        issue.message(),
                        issue.requirements())));
                addRequirementInformation(assembly, catalog, findings);
            }
        }

        StrategyValidationStatus status = findings.stream()
                        .anyMatch(finding -> finding.severity() == Severity.BLOCKING_ERROR)
                ? StrategyValidationStatus.INVALID
                : StrategyValidationStatus.VALID;
        var now = clock.instant();
        var run = new StrategyValidationRun(
                idGenerator.nextId(),
                strategyId,
                accountId,
                null,
                requestedEditSequence,
                semanticHash,
                catalog.version().id(),
                status,
                findings,
                now,
                now);
        if (persist) {
            validationCommandPort.save(run);
        }
        return run;
    }

    private BasicBlockAssembly parseAssembly(
            String semanticDocument,
            List<StrategyValidationFinding> findings) {
        try {
            JsonNode root = objectMapper.readTree(semanticDocument);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("Semantic document must be a JSON object");
            }
            UUID catalogId = UUID.fromString(root.path("catalogId").asText());
            List<BasicBlockGroup> groups = objectMapper.convertValue(
                    root.path("groups"), new TypeReference<List<BasicBlockGroup>>() {});
            if (groups.isEmpty()) {
                findings.add(new StrategyValidationFinding(
                        Severity.BLOCKING_ERROR,
                        "STRATEGY_GROUP_REQUIRED",
                        "groups",
                        "Basic strategy must contain at least one block group",
                        List.of()));
            }
            return new BasicBlockAssembly(catalogId, groups);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            findings.add(new StrategyValidationFinding(
                    Severity.BLOCKING_ERROR,
                    "STRATEGY_DOCUMENT_INVALID",
                    "$",
                    "Saved semantic document does not match the Basic strategy schema",
                    List.of()));
            return null;
        }
    }

    private void addRequirementInformation(
            BasicBlockAssembly assembly,
            BasicStrategyCatalog catalog,
            List<StrategyValidationFinding> findings) {
        Map<String, String> contracts = new HashMap<>();
        catalog.elements().forEach(element -> contracts.put(element.elementCode(), element.executionContract()));
        Set<String> reportedFeeds = new HashSet<>();
        Set<String> reportedFeatures = new HashSet<>();
        for (int groupIndex = 0; groupIndex < assembly.groups().size(); groupIndex++) {
            var group = assembly.groups().get(groupIndex);
            for (int blockIndex = 0; blockIndex < group.blocks().size(); blockIndex++) {
                var block = group.blocks().get(blockIndex);
                String location = "groups[" + groupIndex + "].blocks[" + blockIndex + "].elementCode";
                JsonNode backtest = readBacktest(contracts.get(block.elementCode()));
                if (backtest == null || !backtest.path("supported").asBoolean()) {
                    continue;
                }
                for (JsonNode feedNode : backtest.path("feeds")) {
                    String feed = feedNode.path("feed").asText();
                    String resolution = feedNode.path("resolution").asText();
                    String requirement = "feed:" + feed + "@" + resolution;
                    if (reportedFeeds.add(requirement)) {
                        findings.add(new StrategyValidationFinding(
                                Severity.INFORMATION,
                                "BACKTEST_FEED_REQUIRED",
                                location,
                                "Backtest requires this exact historical feed and resolution",
                                List.of(requirement)));
                    }
                }
                for (JsonNode featureNode : backtest.path("features")) {
                    String requirement = "feature:" + featureNode.asText();
                    if (reportedFeatures.add(requirement)) {
                        findings.add(new StrategyValidationFinding(
                                Severity.INFORMATION,
                                "BACKTEST_FEATURE_REQUIRED",
                                location,
                                "Backtest requires this exact historical feature",
                                List.of(requirement)));
                    }
                }
            }
        }
    }

    private JsonNode readBacktest(String executionContract) {
        if (executionContract == null) {
            return null;
        }
        try {
            JsonNode contract = objectMapper.readTree(executionContract);
            return contract == null ? null : contract.get("backtest");
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private static Severity backtestSeverity(String code) {
        return switch (code) {
            case "BACKTEST_CONTRACT_INVALID", "BACKTEST_CONTRACT_MISSING", "BACKTEST_FEATURE_UNKNOWN" ->
                Severity.BLOCKING_ERROR;
            default -> Severity.WARNING;
        };
    }
}
