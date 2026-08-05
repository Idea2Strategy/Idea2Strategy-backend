package com.idea2strategy.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Idea2StrategyCliTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> requestMethod = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respond(exchange, 200, "{\"id\":\"ok\"}"));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void loginReadsPasswordFromStandardInputAndStoresTokenWithoutEchoingSecrets() throws Exception {
        server.removeContext("/");
        server.createContext("/api/v1/auth/login", exchange -> respond(exchange, 200,
                "{\"accountId\":\"a\",\"sessionToken\":\"top-secret-token\"}"));

        Result result = run("correct horse battery staple\n", "--base-url", baseUrl,
                "--config-dir", tempDir.toString(), "login", "--email", "person@example.com");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).doesNotContain("correct horse", "top-secret-token");
        assertThat(result.stderr()).isEmpty();
        assertThat(requestBody.get()).contains("person@example.com", "correct horse battery staple");
        assertThat(Files.readString(tempDir.resolve("credentials.json"))).contains("top-secret-token");
        assertThat(JSON.readTree(result.stdout()).path("ok").asBoolean()).isTrue();
    }

    @Test
    void strategyListUsesStoredBearerTokenAndReturnsStableJsonEnvelope() throws Exception {
        Files.writeString(tempDir.resolve("credentials.json"), "{\"sessionToken\":\"stored-token\"}");
        server.removeContext("/");
        server.createContext("/api/v1/strategies", exchange -> respond(exchange, 200, "{\"items\":[]}"));

        Result result = run("", "--base-url", baseUrl, "--config-dir", tempDir.toString(),
                "strategy", "list", "--limit", "25");

        assertThat(result.exitCode()).isZero();
        assertThat(requestMethod.get()).isEqualTo("GET");
        assertThat(requestPath.get()).isEqualTo("/api/v1/strategies?limit=25");
        assertThat(authorization.get()).isEqualTo("Bearer stored-token");
        JsonNode output = JSON.readTree(result.stdout());
        assertThat(output.path("ok").asBoolean()).isTrue();
        assertThat(output.path("command").asText()).isEqualTo("strategy.list");
        assertThat(output.path("data").path("items").isArray()).isTrue();
    }

    @Test
    void basicEditApplyRequiresReviewedPreviewHashBeforeCallingServer() throws Exception {
        Files.writeString(tempDir.resolve("credentials.json"), "{\"sessionToken\":\"stored-token\"}");
        Path operations = tempDir.resolve("operations.json");
        Files.writeString(operations, "[{\"action\":\"ADD_BLOCK\",\"arguments\":{}}]");

        Result result = run("", "--base-url", baseUrl, "--config-dir", tempDir.toString(),
                "strategy", "edit", "apply", "--strategy-id", "strategy-1",
                "--authorization-id", "auth-1", "--credential-id", "credential-1",
                "--operations-file", operations.toString());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(requestPath.get()).isNull();
        assertThat(JSON.readTree(result.stderr()).path("error").path("code").asText())
                .isEqualTo("USAGE_ERROR");
    }

    @Test
    void basicEditApplySendsOnlyAllowedOperationsWithPreviewHash() throws Exception {
        Files.writeString(tempDir.resolve("credentials.json"), "{\"sessionToken\":\"stored-token\"}");
        Path operations = tempDir.resolve("operations.json");
        Files.writeString(operations, "[{\"action\":\"SET_VALUE\",\"arguments\":{\"value\":14}}]");

        Result result = run("", "--base-url", baseUrl, "--config-dir", tempDir.toString(),
                "strategy", "edit", "apply", "--strategy-id", "strategy-1",
                "--authorization-id", "auth-1", "--credential-id", "credential-1",
                "--operations-file", operations.toString(), "--preview-hash", "sha256:reviewed");

        assertThat(result.exitCode()).isZero();
        assertThat(requestPath.get()).isEqualTo("/api/v1/strategies/strategy-1/basic-edits/apply");
        assertThat(requestBody.get()).contains("sha256:reviewed", "SET_VALUE", "auth-1", "credential-1");
    }

    @Test
    void arbitraryCodeAndDirectOrderOperationsAreRejectedLocally() throws Exception {
        Files.writeString(tempDir.resolve("credentials.json"), "{\"sessionToken\":\"stored-token\"}");
        Path operations = tempDir.resolve("operations.json");
        Files.writeString(operations, "[{\"action\":\"SUBMIT_ORDER\",\"arguments\":{}}]");

        Result result = run("", "--base-url", baseUrl, "--config-dir", tempDir.toString(),
                "strategy", "edit", "preview", "--strategy-id", "strategy-1",
                "--authorization-id", "auth-1", "--credential-id", "credential-1",
                "--operations-file", operations.toString());

        assertThat(result.exitCode()).isEqualTo(5);
        assertThat(requestPath.get()).isNull();
        assertThat(result.stderr()).contains("OPERATION_NOT_ALLOWED").doesNotContain("SUBMIT_ORDER");
    }

    @Test
    void httpStatusesMapToStableExitCodesAndErrorJson() throws Exception {
        server.removeContext("/");
        server.createContext("/api/v1/strategies", exchange -> respond(exchange, 403,
                "{\"code\":\"SCOPE_DENIED\",\"message\":\"scope denied\"}"));
        Files.writeString(tempDir.resolve("credentials.json"), "{\"sessionToken\":\"stored-token\"}");

        Result result = run("", "--base-url", baseUrl, "--config-dir", tempDir.toString(),
                "strategy", "list");

        assertThat(result.exitCode()).isEqualTo(4);
        JsonNode error = JSON.readTree(result.stderr()).path("error");
        assertThat(error.path("code").asText()).isEqualTo("SCOPE_DENIED");
        assertThat(error.path("status").asInt()).isEqualTo(403);
    }

    @Test
    void unknownCommandIsAUsageErrorEvenBeforeLogin() throws Exception {
        Result result = run("", "--base-url", baseUrl, "--config-dir", tempDir.toString(),
                "strategy", "execute-code");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(JSON.readTree(result.stderr()).path("error").path("code").asText())
                .isEqualTo("USAGE_ERROR");
        assertThat(requestPath.get()).isNull();
    }

    @Test
    void requiredWorkflowCommandsUseVersionedApiRoutes() throws Exception {
        Files.writeString(tempDir.resolve("credentials.json"), "{\"sessionToken\":\"stored-token\"}");

        assertRoute("delegation", "create", "--name", "assistant", "--scopes", "STRATEGY_EDIT,STRATEGY_VALIDATE",
                "POST", "/api/v1/delegations");
        assertRoute("strategy", "create", "--name", "draft", "POST", "/api/v1/strategies");
        assertRoute("strategy", "copy", "--strategy-id", "s1", "--name", "copy", "POST",
                "/api/v1/strategies/s1/copies");
        assertRoute("strategy", "validate", "--strategy-id", "s1", "POST",
                "/api/v1/strategies/s1/validations");
        assertRoute("strategy", "release", "--strategy-id", "s1", "--validation-run-id", "v1",
                "--initial-cash-amount", "100000.00", "--budget-cap-bps", "10000",
                "--broker-rules-version", "broker/v1", "--accounting-rules-version", "accounting/v1",
                "--precision-rules-version", "precision/v1", "--fee-policy-id", "fee1",
                "--buying-power-buffer-policy-id", "buffer1", "--dataset-manifest-id", "dataset1",
                "--execution-policy-version", "backtest-policy-v1",
                "--candidate-conflict-policy", "{\"policy\":\"FIRST_WINS\"}", "POST",
                "/api/v1/strategies/s1/releases");
    }

    @Test
    void strategyReleaseSendsEveryLockedLaunchAndBacktestInput() throws Exception {
        Files.writeString(tempDir.resolve("credentials.json"), "{\"sessionToken\":\"stored-token\"}");

        Result result = run("", "--base-url", baseUrl, "--config-dir", tempDir.toString(),
                "strategy", "release", "--strategy-id", "s1", "--validation-run-id", "v1",
                "--initial-cash-amount", "100000.00", "--budget-cap-bps", "10000",
                "--broker-rules-version", "broker/v1", "--accounting-rules-version", "accounting/v1",
                "--precision-rules-version", "precision/v1", "--fee-policy-id", "fee1",
                "--buying-power-buffer-policy-id", "buffer1", "--dataset-manifest-id", "dataset1",
                "--execution-policy-version", "backtest-policy-v1",
                "--candidate-conflict-policy", "{\"policy\":\"FIRST_WINS\"}");

        assertThat(result.exitCode()).isZero();
        JsonNode body = JSON.readTree(requestBody.get());
        assertThat(body.path("validationRunId").asText()).isEqualTo("v1");
        assertThat(body.path("initialCashAmount").decimalValue()).isEqualByComparingTo("100000.00");
        assertThat(body.path("budgetCapBps").asInt()).isEqualTo(10000);
        assertThat(body.path("feePolicyId").asText()).isEqualTo("fee1");
        assertThat(body.path("datasetManifestId").asText()).isEqualTo("dataset1");
        assertThat(body.path("candidateConflictPolicy").path("policy").asText()).isEqualTo("FIRST_WINS");
    }

    private void assertRoute(String... commandAndExpectation) throws Exception {
        int length = commandAndExpectation.length;
        String expectedMethod = commandAndExpectation[length - 2];
        String expectedPath = commandAndExpectation[length - 1];
        String[] args = new String[length + 2];
        args[0] = "--base-url";
        args[1] = baseUrl;
        args[2] = "--config-dir";
        args[3] = tempDir.toString();
        System.arraycopy(commandAndExpectation, 0, args, 4, length - 2);
        Result result = run("", args);
        assertThat(result.exitCode()).isZero();
        assertThat(requestMethod.get()).isEqualTo(expectedMethod);
        assertThat(requestPath.get()).isEqualTo(expectedPath);
    }

    private Result run(String stdin, String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = Idea2StrategyCli.run(
                args,
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                stdout,
                stderr,
                System.getenv());
        return new Result(exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        requestMethod.set(exchange.getRequestMethod());
        requestPath.set(exchange.getRequestURI().toString());
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Result(int exitCode, String stdout, String stderr) {}
}
