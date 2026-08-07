package com.idea2strategy.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

final class CredentialStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path file;

    CredentialStore(Path configDirectory) {
        this.file = configDirectory.resolve("credentials.json");
    }

    void save(String token) {
        try {
            Files.createDirectories(file.getParent());
            ObjectNode value = JSON.createObjectNode().put("accessToken", token);
            Files.writeString(file, JSON.writeValueAsString(value));
            try {
                Files.setPosixFilePermissions(file, Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // Windows ACL inheritance protects the user-local configuration directory.
            }
        } catch (IOException exception) {
            throw new CliFailure(70, "CREDENTIAL_STORE_ERROR", "Unable to store login credentials");
        }
    }

    String load() {
        try {
            if (!Files.exists(file)) {
                throw new CliFailure(3, "AUTHENTICATION_REQUIRED", "Run login before this command");
            }
            var stored = JSON.readTree(Files.readString(file));
            String token = stored.path("accessToken").asText();
            if (token.isBlank()) {
                token = stored.path("sessionToken").asText();
            }
            if (token.isBlank()) {
                throw new CliFailure(3, "AUTHENTICATION_REQUIRED", "Stored credentials are invalid; login again");
            }
            return token;
        } catch (CliFailure failure) {
            throw failure;
        } catch (IOException exception) {
            throw new CliFailure(3, "AUTHENTICATION_REQUIRED", "Stored credentials are invalid; login again");
        }
    }
}
