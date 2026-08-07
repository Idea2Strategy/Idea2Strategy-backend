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

    /**
     * Reports what a strategy requires of historical data, and never whether that data exists.
     *
     * <p>{@code decision.backtest.supportability} assigns availability to release and backtest
     * request time, where it is resolved against the artifacts the backtest execution contract pins
     * into the request. Coverage is infrastructure state that moves with what the pipeline has
     * published, so a conclusion about it cannot live in a record pinned only by edit sequence,
     * semantic hash and catalog version.
     */
    public BasicBacktestCapabilityResult validate(
            BasicBlockAssembly assembly,
            BasicStrategyCatalog catalog) {
        var assemblyResult = assemblyValidator.validate(assembly, catalog);
        if (!assemblyResult.valid()) {
            var issues = assemblyResult.issues().stream()
                    .map(issue -> new BasicBacktestCapabilityIssue(
                            issue.code(), issue.location(), issue.message(), List.of()))
                    .toList();
            return new BasicBacktestCapabilityResult(List.of(), List.of(), issues);
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

        /* A feed declaration is optional. Adjusted bars at the evaluated resolution are a platform
           invariant, so an element states the official features it reads and need not name a feed.
           A declaration is still read where a published catalog carries one, so both catalog
           versions validate identically. */
        JsonNode feeds = backtest.get("feeds");
        JsonNode features = backtest.get("features");
        if (features == null || !features.isArray()) {
            add(issues, "BACKTEST_CONTRACT_INVALID", location,
                    "Supported backtest contract must declare a feature array", List.of());
            return;
        }
        if (feeds != null && !feeds.isArray()) {
            add(issues, "BACKTEST_CONTRACT_INVALID", location,
                    "Backtest feeds must be an array when declared", List.of());
            return;
        }

        if (feeds != null) {
            for (JsonNode feedNode : feeds) {
                String feed = textOrFallback(feedNode.get("feed"), "");
                String resolution = textOrFallback(feedNode.get("resolution"), "");
                if (feed.isBlank() || resolution.isBlank()) {
                    add(issues, "BACKTEST_CONTRACT_INVALID", location,
                            "Backtest feed must declare an exact feed and resolution", List.of());
                    continue;
                }
                requiredFeeds.add(new FeedResolution(feed, resolution));
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
            /* Catalog membership is a function of the pinned catalog, so it stays. Whether the
               feature has been materialized is not, so it does not. */
            if (!catalogFeatures.contains(feature)) {
                add(issues, "BACKTEST_FEATURE_UNKNOWN", location,
                        "Backtest feature is not defined by the supplied catalog",
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
