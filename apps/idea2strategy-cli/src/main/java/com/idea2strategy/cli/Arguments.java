package com.idea2strategy.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Arguments {
    private final List<String> positionals;
    private final Map<String, String> options;

    private Arguments(List<String> positionals, Map<String, String> options) {
        this.positionals = positionals;
        this.options = options;
    }

    static Arguments parse(List<String> values) {
        List<String> positionals = new ArrayList<>();
        Map<String, String> options = new LinkedHashMap<>();
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            if (!value.startsWith("--")) {
                positionals.add(value);
                continue;
            }
            if (index + 1 >= values.size() || values.get(index + 1).startsWith("--")) {
                throw usage("Option requires a value: " + value);
            }
            if (options.put(value, values.get(++index)) != null) {
                throw usage("Option may be supplied only once: " + value);
            }
        }
        return new Arguments(List.copyOf(positionals), Map.copyOf(options));
    }

    List<String> positionals() {
        return positionals;
    }

    String required(String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw usage("Missing required option: " + name);
        }
        return value;
    }

    String optional(String name) {
        return options.get(name);
    }

    String optional(String name, String defaultValue) {
        return options.getOrDefault(name, defaultValue);
    }

    void rejectUnknown(String... allowed) {
        var accepted = SetSupport.of(allowed);
        options.keySet().stream()
                .filter(option -> !accepted.contains(option))
                .findFirst()
                .ifPresent(option -> { throw usage("Unknown option: " + option); });
    }

    static CliFailure usage(String message) {
        return new CliFailure(2, "USAGE_ERROR", message);
    }

    private static final class SetSupport {
        private SetSupport() {}

        static java.util.Set<String> of(String[] values) {
            return java.util.Set.of(values);
        }
    }
}
