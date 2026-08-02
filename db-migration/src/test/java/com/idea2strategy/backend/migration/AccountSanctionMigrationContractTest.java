package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AccountSanctionMigrationContractTest {
    @Test
    void definesVersionedHeadReceiptStableReferenceAndAppendOnlyHistory() throws Exception {
        String sql = Files.readString(Path.of(getClass().getClassLoader().getResource(
                "db/migration/V20260802231600__backend_account_sanction_commands.sql").toURI()));

        for (String required : new String[] {
                "identity.account_sanction_heads", "aggregate_version", "public_reference",
                "identity.account_sanction_command_receipts", "account_sanction_events_append_only",
                "account_sanction_due_idx"}) {
            assertTrue(sql.contains(required), required);
        }
        assertFalse(sql.contains("delete from identity.account_sanctions"));
    }
}
