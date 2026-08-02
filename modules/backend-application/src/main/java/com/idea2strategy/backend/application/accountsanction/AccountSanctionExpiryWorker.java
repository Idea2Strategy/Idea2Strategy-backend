package com.idea2strategy.backend.application.accountsanction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class AccountSanctionExpiryWorker {
    private final AccountSanctionExpiryPort dueSanctions;
    private final AccountSanctionCommandService commands;

    public AccountSanctionExpiryWorker(
            AccountSanctionExpiryPort dueSanctions, AccountSanctionCommandService commands) {
        this.dueSanctions = Objects.requireNonNull(dueSanctions, "dueSanctions");
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    public List<AccountSanctionResult> expireDue(int limit, UUID runCorrelationId) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
        return dueSanctions.findDue(limit).stream().map(due -> {
            String key = "sanction-expiry:" + due.sanctionId() + ":" + due.expiresAt();
            return commands.execute(new AccountSanctionCommand(
                    AccountSanctionCommand.Type.EXPIRE,
                    null,
                    due.accountId(),
                    due.sanctionId(),
                    null,
                    "SANCTION_EXPIRED",
                    null,
                    null,
                    runCorrelationId,
                    key,
                    sha256(key),
                    due.aggregateVersion()));
        }).toList();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
