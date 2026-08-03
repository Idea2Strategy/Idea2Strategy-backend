package com.idea2strategy.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Idea2StrategyCli {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ALLOWED_EDIT_OPERATIONS =
            Set.of("ADD_BLOCK", "REMOVE_BLOCK", "CONNECT_BLOCKS", "SET_VALUE");
    private static final Set<String> AUTHENTICATED_COMMANDS = Set.of(
            "delegation.create",
            "delegation.revoke",
            "strategy.list",
            "strategy.create",
            "strategy.copy",
            "strategy.edit.preview",
            "strategy.edit.apply",
            "strategy.validate",
            "strategy.release");

    private Idea2StrategyCli() {}

    public static void main(String[] args) {
        System.exit(run(args, System.in, System.out, System.err, System.getenv()));
    }

    static int run(String[] args, InputStream stdin, OutputStream stdout, OutputStream stderr,
            Map<String, String> environment) {
        PrintWriter out = new PrintWriter(stdout, true, StandardCharsets.UTF_8);
        PrintWriter err = new PrintWriter(stderr, true, StandardCharsets.UTF_8);
        String commandName = "unknown";
        try {
            Invocation invocation = Invocation.parse(args, environment);
            commandName = invocation.commandName();
            JsonNode data = execute(invocation, stdin, environment);
            ObjectNode envelope = JSON.createObjectNode().put("ok", true).put("command", commandName);
            envelope.set("data", data);
            out.println(JSON.writeValueAsString(envelope));
            return 0;
        } catch (CliFailure failure) {
            writeError(err, commandName, failure);
            return failure.exitCode();
        } catch (Exception exception) {
            writeError(err, commandName,
                    new CliFailure(70, "INTERNAL_ERROR", "The CLI could not complete the command"));
            return 70;
        }
    }

    private static JsonNode execute(Invocation invocation, InputStream stdin, Map<String, String> environment) {
        Arguments arguments = Arguments.parse(invocation.commandArguments());
        if (arguments.positionals().equals(List.of("operator", "bootstrap"))) {
            return OperatorBootstrapCommand.execute(arguments, environment);
        }
        ApiClient api = new ApiClient(invocation.baseUrl());
        CredentialStore credentials = new CredentialStore(invocation.configDirectory());
        List<String> command = arguments.positionals();
        if (command.equals(List.of("tool-contract"))) {
            arguments.rejectUnknown();
            return toolContract();
        }
        if (command.equals(List.of("login"))) {
            return login(arguments, api, credentials, stdin);
        }
        String commandKey = String.join(".", command);
        if (!AUTHENTICATED_COMMANDS.contains(commandKey)) {
            throw Arguments.usage("Unknown command");
        }
        String token = invocation.environmentToken() == null
                ? credentials.load()
                : invocation.environmentToken();
        return switch (commandKey) {
            case "delegation.create" -> delegationCreate(arguments, api, token);
            case "delegation.revoke" -> delegationRevoke(arguments, api, token);
            case "strategy.list" -> strategyList(arguments, api, token);
            case "strategy.create" -> strategyCreate(arguments, api, token);
            case "strategy.copy" -> strategyCopy(arguments, api, token);
            case "strategy.edit.preview" -> basicEdit(arguments, api, token, false);
            case "strategy.edit.apply" -> basicEdit(arguments, api, token, true);
            case "strategy.validate" -> strategyValidate(arguments, api, token);
            case "strategy.release" -> strategyRelease(arguments, api, token);
            default -> throw new IllegalStateException("Unmapped authenticated command");
        };
    }

    private static JsonNode toolContract() {
        try (InputStream resource = Idea2StrategyCli.class.getResourceAsStream(
                "/idea2strategy-ai-tool-contract.json")) {
            if (resource == null) {
                throw new IOException("Tool contract resource is missing");
            }
            return JSON.readTree(resource);
        } catch (IOException exception) {
            throw new CliFailure(70, "TOOL_CONTRACT_UNAVAILABLE",
                    "The external AI tool contract could not be loaded");
        }
    }

    private static JsonNode login(Arguments args, ApiClient api, CredentialStore credentials, InputStream stdin) {
        args.rejectUnknown("--email", "--device-label");
        String password;
        try {
            password = new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8)).readLine();
        } catch (IOException exception) {
            throw Arguments.usage("Unable to read password from standard input");
        }
        if (password == null || password.isBlank()) {
            throw Arguments.usage("Login password must be provided on standard input");
        }
        ObjectNode body = JSON.createObjectNode()
                .put("email", args.required("--email"))
                .put("password", password)
                .put("deviceLabel", args.optional("--device-label", "idea2strategy-cli"));
        JsonNode response = api.post("/api/v1/auth/login", body, null);
        String token = response.path("sessionToken").asText();
        if (token.isBlank()) {
            throw new CliFailure(6, "INVALID_SERVER_RESPONSE", "Login response did not contain a session token");
        }
        credentials.save(token);
        ObjectNode result = JSON.createObjectNode().put("credentialSaved", true);
        copyIfPresent(response, result, "accountId", "sessionId", "expiresAt");
        return result;
    }

    private static JsonNode delegationCreate(Arguments args, ApiClient api, String token) {
        args.rejectUnknown("--name", "--scopes");
        ArrayNode scopes = JSON.createArrayNode();
        for (String scope : args.required("--scopes").split(",")) {
            String normalized = scope.trim();
            if (!Set.of("STRATEGY_EDIT", "STRATEGY_VALIDATE").contains(normalized)) {
                throw new CliFailure(5, "SCOPE_NOT_ALLOWED", "Only Basic edit and validation scopes are supported");
            }
            scopes.add(normalized);
        }
        ObjectNode body = JSON.createObjectNode().put("name", args.required("--name"));
        body.set("scopes", scopes);
        return api.post("/api/v1/delegations", body, token);
    }

    private static JsonNode delegationRevoke(Arguments args, ApiClient api, String token) {
        args.rejectUnknown("--authorization-id");
        return api.delete("/api/v1/delegations/" + segment(args.required("--authorization-id")), token);
    }

    private static JsonNode strategyList(Arguments args, ApiClient api, String token) {
        args.rejectUnknown("--limit", "--cursor");
        String limit = args.optional("--limit", "50");
        int parsedLimit;
        try {
            parsedLimit = Integer.parseInt(limit);
        } catch (NumberFormatException exception) {
            throw Arguments.usage("--limit must be an integer");
        }
        if (parsedLimit < 1 || parsedLimit > 100) {
            throw Arguments.usage("--limit must be between 1 and 100");
        }
        String path = "/api/v1/strategies?limit=" + parsedLimit;
        if (args.optional("--cursor") != null) {
            path += "&cursor=" + segment(args.optional("--cursor"));
        }
        return api.get(path, token);
    }

    private static JsonNode strategyCreate(Arguments args, ApiClient api, String token) {
        args.rejectUnknown("--name", "--description");
        ObjectNode body = JSON.createObjectNode().put("name", args.required("--name")).put("mode", "BASIC");
        putOptional(body, "description", args.optional("--description"));
        return api.post("/api/v1/strategies", body, token);
    }

    private static JsonNode strategyCopy(Arguments args, ApiClient api, String token) {
        args.rejectUnknown("--strategy-id", "--name");
        ObjectNode body = JSON.createObjectNode().put("name", args.required("--name"));
        return api.post("/api/v1/strategies/" + segment(args.required("--strategy-id")) + "/copies", body, token);
    }

    private static JsonNode basicEdit(Arguments args, ApiClient api, String token, boolean apply) {
        args.rejectUnknown("--strategy-id", "--authorization-id", "--credential-id",
                "--operations-file", "--preview-hash");
        String previewHash = args.optional("--preview-hash");
        if (apply && (previewHash == null || previewHash.isBlank())) {
            throw Arguments.usage("Apply requires --preview-hash from a reviewed preview");
        }
        ArrayNode operations = readOperations(args.required("--operations-file"));
        for (JsonNode operation : operations) {
            String action = operation.path("action").asText();
            if (!ALLOWED_EDIT_OPERATIONS.contains(action)) {
                throw new CliFailure(5, "OPERATION_NOT_ALLOWED", "The operation is outside the Basic edit boundary");
            }
        }
        ObjectNode body = JSON.createObjectNode()
                .put("authorizationId", args.required("--authorization-id"))
                .put("credentialId", args.required("--credential-id"));
        body.set("operations", operations);
        putOptional(body, "previewHash", previewHash);
        String suffix = apply ? "apply" : "preview";
        return api.post("/api/v1/strategies/" + segment(args.required("--strategy-id"))
                + "/basic-edits/" + suffix, body, token);
    }

    private static JsonNode strategyValidate(Arguments args, ApiClient api, String token) {
        args.rejectUnknown("--strategy-id");
        return api.post("/api/v1/strategies/" + segment(args.required("--strategy-id"))
                + "/validations", JSON.createObjectNode(), token);
    }

    private static JsonNode strategyRelease(Arguments args, ApiClient api, String token) {
        args.rejectUnknown("--strategy-id", "--validation-run-id");
        ObjectNode body = JSON.createObjectNode().put("validationRunId", args.required("--validation-run-id"));
        return api.post("/api/v1/strategies/" + segment(args.required("--strategy-id"))
                + "/releases", body, token);
    }

    private static ArrayNode readOperations(String fileName) {
        try {
            JsonNode value = JSON.readTree(Files.readString(Path.of(fileName)));
            if (!(value instanceof ArrayNode array)) {
                throw new CliFailure(5, "INVALID_OPERATIONS", "Operations file must contain a JSON array");
            }
            return array;
        } catch (CliFailure failure) {
            throw failure;
        } catch (Exception exception) {
            throw new CliFailure(5, "INVALID_OPERATIONS", "Operations file is not valid JSON");
        }
    }

    private static void writeError(PrintWriter writer, String command, CliFailure failure) {
        ObjectNode envelope = JSON.createObjectNode().put("ok", false).put("command", command);
        ObjectNode error = JSON.createObjectNode()
                .put("code", failure.errorCode())
                .put("message", failure.getMessage());
        if (failure.status() != null) {
            error.put("status", failure.status());
        }
        envelope.set("error", error);
        writer.println(envelope);
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void copyIfPresent(JsonNode source, ObjectNode target, String... names) {
        for (String name : names) {
            if (source.has(name)) {
                target.set(name, source.get(name));
            }
        }
    }

    private static void putOptional(ObjectNode target, String name, String value) {
        if (value != null) {
            target.put(name, value);
        }
    }

    private record Invocation(
            String baseUrl,
            Path configDirectory,
            String environmentToken,
            List<String> commandArguments,
            String commandName) {

        static Invocation parse(String[] raw, Map<String, String> environment) {
            List<String> values = new ArrayList<>(Arrays.asList(raw));
            String baseUrl = takeGlobal(values, "--base-url", environment.getOrDefault(
                    "I2S_BASE_URL", "http://localhost:8080"));
            String defaultConfig = environment.get("I2S_CONFIG_DIR");
            if (defaultConfig == null || defaultConfig.isBlank()) {
                defaultConfig = Path.of(System.getProperty("user.home"), ".idea2strategy").toString();
            }
            String config = takeGlobal(values, "--config-dir", defaultConfig);
            if (values.isEmpty()) {
                throw Arguments.usage("A command is required");
            }
            List<String> words = values.stream().takeWhile(value -> !value.startsWith("--")).toList();
            int commandWordCount = switch (words.getFirst()) {
                case "login" -> 1;
                case "delegation" -> 2;
                case "strategy" -> words.size() >= 2 && "edit".equals(words.get(1)) ? 3 : 2;
                case "operator" -> 2;
                default -> 1;
            };
            if (words.size() < commandWordCount) {
                throw Arguments.usage("Incomplete command");
            }
            String commandName = String.join(".", words.subList(0, commandWordCount));
            return new Invocation(baseUrl, Path.of(config), blankToNull(environment.get("I2S_TOKEN")),
                    List.copyOf(values), commandName);
        }

        private static String takeGlobal(List<String> values, String option, String defaultValue) {
            int index = values.indexOf(option);
            if (index < 0) {
                return defaultValue;
            }
            if (index + 1 >= values.size()) {
                throw Arguments.usage("Option requires a value: " + option);
            }
            String value = values.get(index + 1);
            values.remove(index + 1);
            values.remove(index);
            return value;
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }
}
