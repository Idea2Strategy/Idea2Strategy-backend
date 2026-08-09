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
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.time.Instant;
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
            Set.of("ADD_GROUP", "ADD_BLOCK", "REMOVE_BLOCK", "CONNECT_BLOCKS", "SET_VALUE");
    private static final Set<String> AUTHENTICATED_COMMANDS = Set.of(
            "catalog.elements",
            "catalog.instruments",
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
            case "catalog.elements" -> catalogElements(arguments, api, token);
            case "catalog.instruments" -> catalogInstruments(arguments, api, token);
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

    /**
     * Signs in through the browser so nothing driving this CLI ever handles a password.
     *
     * <p>The short code is printed for a person to check against what the browser shows; the long
     * one stays here and is what actually collects the token. Progress is written to standard error
     * so standard output stays a single JSON document for whatever is parsing it.
     */
    private static JsonNode browserLogin(Arguments args, ApiClient api, CredentialStore credentials) {
        ObjectNode request = JSON.createObjectNode().put("clientLabel", "idea2strategy-cli");
        JsonNode authorization = api.post("/api/v1/auth/device/authorize", request, null);
        String deviceCode = authorization.path("deviceCode").asText();
        String userCode = authorization.path("userCode").asText();
        String openUri = authorization.path("verificationUriComplete").asText();
        if (deviceCode.isBlank() || userCode.isBlank() || openUri.isBlank()) {
            throw new CliFailure(6, "INVALID_SERVER_RESPONSE", "Device authorization response was incomplete");
        }

        System.err.println("Open " + openUri);
        System.err.println("Confirm this code in the browser: " + userCode);
        if (!args.flag("--no-open")) {
            openInBrowser(openUri);
        }

        long intervalSeconds = Math.max(1, authorization.path("intervalSeconds").asLong(5));
        Instant deadline = Instant.now().plusSeconds(600);
        ObjectNode poll = JSON.createObjectNode().put("deviceCode", deviceCode);
        while (Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(intervalSeconds * 1000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CliFailure(70, "INTERRUPTED", "Browser login was interrupted");
            }
            // A pending request answers 202, which the client treats as success with no token, so
            // the blank check below is the wait. Denied, expired, and unknown all arrive as
            // failures and propagate: none of them will ever turn into an approval, and polling on
            // would just burn the deadline.
            JsonNode response = api.post("/api/v1/auth/device/token", poll, null);
            String token = response.path("accessToken").asText();
            if (token.isBlank()) {
                continue;
            }
            credentials.save(token);
            ObjectNode result = JSON.createObjectNode().put("credentialSaved", true);
            copyIfPresent(response, result, "accountId", "expiresAt");
            return result;
        }
        throw new CliFailure(5, "DEVICE_AUTHORIZATION_TIMED_OUT", "The browser approval was not completed in time");
    }

    /** Best effort. A headless machine still gets the URI on standard error. */
    private static void openInBrowser(String uri) {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        List<String> command = os.contains("win")
                ? List.of("rundll32", "url.dll,FileProtocolHandler", uri)
                : os.contains("mac") ? List.of("open", uri) : List.of("xdg-open", uri);
        try {
            new ProcessBuilder(command).inheritIO().start();
        } catch (IOException ignored) {
            System.err.println("Could not open a browser automatically. Open the address above.");
        }
    }

    private static JsonNode login(Arguments args, ApiClient api, CredentialStore credentials, InputStream stdin) {
        args.rejectUnknown("--email", "--browser", "--no-open");
        if (args.flag("--browser")) {
            return browserLogin(args, api, credentials);
        }
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
                .put("password", password);
        JsonNode response = api.post("/api/v1/auth/login", body, null);
        String token = response.path("accessToken").asText();
        if (token.isBlank()) {
            throw new CliFailure(6, "INVALID_SERVER_RESPONSE", "Login response did not contain an access JWT");
        }
        credentials.save(token);
        ObjectNode result = JSON.createObjectNode().put("credentialSaved", true);
        copyIfPresent(response, result, "accountId", "expiresAt");
        return result;
    }

    /**
     * The catalog an edit is validated against.
     *
     * <p>Without this an external tool cannot turn "use RSI" into an operation: element codes and
     * their declared parameters live in the published catalog, and guessing a code produces an
     * edit the server refuses. Reading beats guessing.
     */
    private static JsonNode catalogElements(Arguments args, ApiClient api, String token) {
        args.rejectUnknown();
        return api.get("/api/v1/strategy-catalogs/basic", token);
    }

    /**
     * Symbol to instrument id.
     *
     * <p>A container names the instruments it trades by id, and a person asks for "Apple". Without
     * a lookup the tool has no way to cross that gap.
     */
    private static JsonNode catalogInstruments(Arguments args, ApiClient api, String token) {
        args.rejectUnknown("--symbol");
        JsonNode instruments = api.get("/api/v1/strategy-catalogs/basic/instruments", token);
        String symbol = args.optional("--symbol");
        if (symbol == null || symbol.isBlank()) {
            return instruments;
        }
        ArrayNode matches = JSON.createArrayNode();
        for (String requested : symbol.split(",")) {
            String wanted = requested.trim();
            for (JsonNode instrument : instruments.path("instruments")) {
                if (instrument.path("symbol").asText().equalsIgnoreCase(wanted)) {
                    matches.add(instrument);
                }
            }
        }
        ObjectNode filtered = JSON.createObjectNode();
        filtered.set("instruments", matches);
        return filtered;
    }

    private static JsonNode delegationCreate(Arguments args, ApiClient api, String token) {
        args.rejectUnknown("--name", "--scopes", "--strategy-id", "--expires-at");
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
        // A delegation the server cannot pin to a strategy authorizes nothing, so the CLI refuses
        // to send one rather than reporting a grant that will deny every edit.
        ArrayNode strategyIds = JSON.createArrayNode();
        for (String strategyId : args.required("--strategy-id").split(",")) {
            String normalized = strategyId.trim();
            if (!normalized.isEmpty()) {
                strategyIds.add(normalized);
            }
        }
        if (strategyIds.isEmpty()) {
            throw Arguments.usage("--strategy-id must name at least one strategy to delegate");
        }
        body.set("strategyIds", strategyIds);
        putOptional(body, "expiresAt", args.optional("--expires-at"));
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
                "--operations-file", "--preview-hash", "--expected-edit-sequence");
        String previewHash = args.optional("--preview-hash");
        String expectedEditSequence = args.optional("--expected-edit-sequence");
        if (apply && (previewHash == null || previewHash.isBlank())) {
            throw Arguments.usage("Apply requires --preview-hash from a reviewed preview");
        }
        // The preview reports the sequence it read. Returning it on apply is what makes the review
        // gate hold: without it the server would re-read, and an owner edit landing between the two
        // calls would be overwritten by a diff nobody reviewed against it.
        if (apply && (expectedEditSequence == null || expectedEditSequence.isBlank())) {
            throw Arguments.usage(
                    "Apply requires --expected-edit-sequence from the same reviewed preview");
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
        if (expectedEditSequence != null && !expectedEditSequence.isBlank()) {
            try {
                body.put("expectedEditSequence", Long.parseLong(expectedEditSequence.trim()));
            } catch (NumberFormatException exception) {
                throw Arguments.usage("--expected-edit-sequence must be the integer a preview returned");
            }
        }
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
        args.rejectUnknown(
                "--strategy-id",
                "--validation-run-id",
                "--initial-cash-amount",
                "--budget-cap-bps",
                "--broker-rules-version",
                "--accounting-rules-version",
                "--precision-rules-version",
                "--fee-policy-id",
                "--buying-power-buffer-policy-id",
                "--dataset-manifest-id",
                "--execution-policy-version",
                "--candidate-conflict-policy");
        BigDecimal initialCashAmount;
        int budgetCapBps;
        JsonNode candidateConflictPolicy;
        try {
            initialCashAmount = new BigDecimal(args.required("--initial-cash-amount"));
            budgetCapBps = Integer.parseInt(args.required("--budget-cap-bps"));
            candidateConflictPolicy = JSON.readTree(args.required("--candidate-conflict-policy"));
        } catch (Exception exception) {
            throw Arguments.usage("Release amounts, budget, and candidate conflict policy must be valid");
        }
        if (!candidateConflictPolicy.isObject()) {
            throw Arguments.usage("--candidate-conflict-policy must be a JSON object");
        }
        ObjectNode body = JSON.createObjectNode()
                .put("validationRunId", args.required("--validation-run-id"))
                .put("initialCashAmount", initialCashAmount)
                .put("budgetCapBps", budgetCapBps)
                .put("brokerRulesVersion", args.required("--broker-rules-version"))
                .put("accountingRulesVersion", args.required("--accounting-rules-version"))
                .put("precisionRulesVersion", args.required("--precision-rules-version"))
                .put("feePolicyId", args.required("--fee-policy-id"))
                .put("buyingPowerBufferPolicyId", args.required("--buying-power-buffer-policy-id"))
                .put("datasetManifestId", args.required("--dataset-manifest-id"))
                .put("executionPolicyVersion", args.required("--execution-policy-version"));
        body.set("candidateConflictPolicy", candidateConflictPolicy);
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
                case "catalog" -> 2;
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
