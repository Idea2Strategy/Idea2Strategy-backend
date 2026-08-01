package com.idea2strategy.backend.application.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.strategy.BacktestDataCoverage.FeedResolution;
import com.idea2strategy.backend.domain.strategy.StrategyElementDefinition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class BasicBacktestCapabilityValidator {
    private static final Comparator<FeedResolution> FEED_ORDER = Comparator
            .comparing(FeedResolution::feed)
            .thenComparing(FeedResolution::resolution);

    private final BasicBlockAssemblyValidator assemblyValidator;
    private final ObjectMapper objectMapper;

    public BasicBacktestCapabilityValidator() {
        this(new BasicBlockAssemblyValidator(), new ObjectMapper());
    }

    BasicBacktestCapabilityValidator(BasicBlockAssemblyValidator assemblyValidator, ObjectMapper objectMapper) {
        this.assemblyValidator = assemblyValidator;
        this.objectMapper = objectMapper;
    }

    public BasicBacktestCapabilityResult validate(
            BasicBlockAssembly assembly,
            BasicStrategyCatalog catalog,
            BacktestDataCoverage coverage) {
        var assemblyResult = assemblyValidator.validate(assembly, catalog);
        if (!assemblyResult.valid()) {
            var issues = assemblyResult.issues().stream()
                    .map(issue -> new BasicBacktestCapabilityIssue(
                            issue.code(), issue.location(), issue.message(), List.of()))
                    .toList();
            return new BasicBacktestCapabilityResult(List.of(), List.of(), issues);
        }

        if (!catalog.version().dataRequirementVersion().equals(coverage.dataRequirementVersion())) {
            return new BasicBacktestCapabilityResult(
                    List.of(),
                    List.of(),
                    List.of(new BasicBacktestCapabilityIssue(
                            "DATA_REQUIREMENT_VERSION_MISMATCH",
                            "dataRequirementVersion",
                            "Backtest coverage must match the catalog data requirement version",
                            List.of("dataRequirementVersion:" + catalog.version().dataRequirementVersion()))));
        }

        Map<String, StrategyElementDefinition> elements = new HashMap<>();
        catalog.elements().forEach(element -> elements.put(element.elementCode(), element));
        Set<String> catalogFeatures = new HashSet<>();
        catalog.features().forEach(feature -> catalogFeatures.add(feature.featureCode()));

        var requiredFeeds = new TreeSet<>(FEED_ORDER);
        var requiredFeatures = new TreeSet<String>();
        var issues = new ArrayList<BasicBacktestCapabilityIssue>();

        for (int groupIndex = 0; groupIndex < assembly.groups().size(); groupIndex++) {
            var group = assembly.groups().get(groupIndex);
            for (int blockIndex = 0; blockIndex < group.blocks().size(); blockIndex++) {
                var block = group.blocks().get(blockIndex);
                String location = "groups[" + groupIndex + "].blocks[" + blockIndex + "].elementCode";
                validateBlock(
                        elements.get(block.elementCode()),
                        location,
                        catalogFeatures,
                        coverage,
                        requiredFeeds,
                        requiredFeatures,
                        issues);
            }
        }

        return new BasicBacktestCapabilityResult(
                List.copyOf(requiredFeeds),
                List.copyOf(requiredFeatures),
                issues);
    }

    private void validateBlock(
            StrategyElementDefinition definition,
            String location,
            Set<String> catalogFeatures,
            BacktestDataCoverage coverage,
            Set<FeedResolution> requiredFeeds,
            Set<String> requiredFeatures,
            List<BasicBacktestCapabilityIssue> issues) {
        JsonNode contract;
        try {
            contract = objectMapper.readTree(definition.executionContract());
        } catch (JsonProcessingException exception) {
            add(issues, "BACKTEST_CONTRACT_INVALID", location,
                    "Element backtest contract is not valid JSON", List.of());
            return;
        }

        JsonNode backtest = contract == null ? null : contract.get("backtest");
        if (backtest == null || !backtest.isObject()) {
            add(issues, "BACKTEST_CONTRACT_MISSING", location,
                    "Element does not declare its backtest capability", List.of());
            return;
        }
        if (!backtest.path("supported").isBoolean()) {
            add(issues, "BACKTEST_CONTRACT_INVALID", location,
                    "Element backtest contract must declare supported as a boolean", List.of());
            return;
        }
        if (!backtest.path("supported").asBoolean()) {
            String reason = textOrFallback(backtest.get("reason"), "Element is not reproducible in backtests");
            add(issues, "BACKTEST_BLOCK_UNSUPPORTED", location, reason, textArray(backtest.get("requirements")));
            return;
        }

        JsonNode feeds = backtest.get("feeds");
        JsonNode features = backtest.get("features");
        if (feeds == null || !feeds.isArray() || features == null || !features.isArray()) {
            add(issues, "BACKTEST_CONTRACT_INVALID", location,
                    "Supported backtest contract must declare feed and feature arrays", List.of());
            return;
        }

        for (JsonNode feedNode : feeds) {
            String feed = textOrFallback(feedNode.get("feed"), "");
            String resolution = textOrFallback(feedNode.get("resolution"), "");
            if (feed.isBlank() || resolution.isBlank()) {
                add(issues, "BACKTEST_CONTRACT_INVALID", location,
                        "Backtest feed must declare an exact feed and resolution", List.of());
                continue;
            }
            var requirement = new FeedResolution(feed, resolution);
            requiredFeeds.add(requirement);
            if (!coverage.feeds().contains(requirement)) {
                add(issues, "BACKTEST_FEED_UNAVAILABLE", location,
                        "Exact historical feed and resolution are unavailable",
                        List.of("feed:" + feed + "@" + resolution));
            }
        }

        for (JsonNode featureNode : features) {
            if (!featureNode.isTextual() || featureNode.asText().isBlank()) {
                add(issues, "BACKTEST_CONTRACT_INVALID", location,
                        "Backtest feature must be a non-blank catalog feature code", List.of());
                continue;
            }
            String feature = featureNode.asText();
            requiredFeatures.add(feature);
            if (!catalogFeatures.contains(feature)) {
                add(issues, "BACKTEST_FEATURE_UNKNOWN", location,
                        "Backtest feature is not defined by the supplied catalog",
                        List.of("feature:" + feature));
            } else if (!coverage.features().contains(feature)) {
                add(issues, "BACKTEST_FEATURE_UNAVAILABLE", location,
                        "Exact historical feature is unavailable",
                        List.of("feature:" + feature));
            }
        }
    }

    private static List<String> textArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        var values = new ArrayList<String>();
        for (JsonNode value : node) {
            if (value.isTextual() && !value.asText().isBlank()) {
                values.add(value.asText());
            }
        }
        return List.copyOf(values);
    }

    private static String textOrFallback(JsonNode node, String fallback) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : fallback;
    }

    private static void add(
            List<BasicBacktestCapabilityIssue> issues,
            String code,
            String location,
            String message,
            List<String> requirements) {
        issues.add(new BasicBacktestCapabilityIssue(code, location, message, requirements));
    }
}
