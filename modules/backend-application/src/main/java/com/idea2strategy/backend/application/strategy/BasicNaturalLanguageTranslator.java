package com.idea2strategy.backend.application.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.strategy.BasicNaturalLanguageReview.BlockReview;
import com.idea2strategy.backend.application.strategy.BasicNaturalLanguageReview.GroupReview;
import com.idea2strategy.backend.domain.strategy.StrategyElementDefinition;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class BasicNaturalLanguageTranslator {
    private static final String REVIEW_LOCALE = "ko-KR";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)}");

    private final BasicBlockAssemblyValidator validator;
    private final ObjectMapper objectMapper;

    public BasicNaturalLanguageTranslator() {
        this(new BasicBlockAssemblyValidator(), new ObjectMapper());
    }

    BasicNaturalLanguageTranslator(BasicBlockAssemblyValidator validator, ObjectMapper objectMapper) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public BasicNaturalLanguageReview translate(BasicBlockAssembly assembly, BasicStrategyCatalog catalog) {
        BasicBlockAssemblyValidationResult validation = validator.validate(assembly, catalog);
        if (!validation.valid()) {
            return new BasicNaturalLanguageReview(List.of(), validation.issues());
        }

        Map<String, StrategyElementDefinition> definitions = catalog.elements().stream()
                .collect(Collectors.toMap(StrategyElementDefinition::elementCode, Function.identity()));
        var issues = new ArrayList<BasicBlockAssemblyIssue>();
        var groups = new ArrayList<GroupReview>();

        for (int groupIndex = 0; groupIndex < assembly.groups().size(); groupIndex++) {
            var group = assembly.groups().get(groupIndex);
            var blockReviews = new ArrayList<BlockReview>();
            for (int blockIndex = 0; blockIndex < group.blocks().size(); blockIndex++) {
                var block = group.blocks().get(blockIndex);
                String blockPath = "groups[" + groupIndex + "].blocks[" + blockIndex + "]";
                String template = reviewTemplate(definitions.get(block.elementCode()), blockPath, issues);
                if (template == null) {
                    continue;
                }
                String text = render(template, block.parameters(), blockPath, issues);
                if (text != null) {
                    blockReviews.add(new BlockReview(block.id(), text));
                }
            }
            groups.add(new GroupReview(group.id(), group.container(), blockReviews));
        }

        if (!issues.isEmpty()) {
            return new BasicNaturalLanguageReview(List.of(), issues);
        }
        return new BasicNaturalLanguageReview(groups, List.of());
    }

    private String reviewTemplate(
            StrategyElementDefinition definition,
            String blockPath,
            List<BasicBlockAssemblyIssue> issues) {
        try {
            JsonNode template = objectMapper.readTree(definition.executionContract())
                    .path("reviewTemplates")
                    .path(REVIEW_LOCALE);
            if (!template.isTextual() || template.textValue().isBlank()) {
                add(issues, "REVIEW_TEMPLATE_MISSING", blockPath + ".elementCode",
                        "Catalog element has no ko-KR review template");
                return null;
            }
            return template.textValue();
        } catch (JsonProcessingException exception) {
            add(issues, "REVIEW_TEMPLATE_INVALID", blockPath + ".elementCode",
                    "Catalog review template is not valid JSON");
            return null;
        }
    }

    private String render(
            String template,
            Map<String, Object> parameters,
            String blockPath,
            List<BasicBlockAssemblyIssue> issues) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        Set<String> referenced = new LinkedHashSet<>();
        StringBuffer output = new StringBuffer();
        boolean failed = false;
        while (matcher.find()) {
            String name = matcher.group(1);
            referenced.add(name);
            if (!parameters.containsKey(name)) {
                add(issues, "REVIEW_PLACEHOLDER_UNKNOWN", blockPath + ".parameters." + name,
                        "Review template references an unknown parameter");
                failed = true;
                matcher.appendReplacement(output, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            try {
                matcher.appendReplacement(output, Matcher.quoteReplacement(renderValue(parameters.get(name))));
            } catch (IllegalArgumentException exception) {
                add(issues, "REVIEW_VALUE_UNSUPPORTED", blockPath + ".parameters." + name,
                        "Parameter value cannot be represented deterministically");
                failed = true;
                matcher.appendReplacement(output, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(output);

        for (String name : parameters.keySet().stream().sorted().toList()) {
            if (!referenced.contains(name)) {
                add(issues, "REVIEW_VALUE_OMITTED", blockPath + ".parameters." + name,
                        "Review template must include every block parameter");
                failed = true;
            }
        }
        return failed ? null : output.toString();
    }

    private static String renderValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof BigInteger
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value instanceof Boolean
                || value instanceof String
                || value instanceof java.util.UUID) {
            return value.toString();
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof List<?> values) {
            return values.stream().map(BasicNaturalLanguageTranslator::renderValue)
                    .collect(Collectors.joining(", ", "[", "]"));
        }
        if (value instanceof Map<?, ?> values) {
            return values.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .map(entry -> String.valueOf(entry.getKey()) + "=" + renderValue(entry.getValue()))
                    .collect(Collectors.joining(", ", "{", "}"));
        }
        throw new IllegalArgumentException("Unsupported review value");
    }

    private static void add(
            List<BasicBlockAssemblyIssue> issues, String code, String location, String message) {
        issues.add(new BasicBlockAssemblyIssue(code, location, message));
    }
}
