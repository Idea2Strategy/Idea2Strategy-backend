package com.idea2strategy.backend.migration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class DatabaseAccessPolicy {
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?i)CREATE\\s+TABLE\\s+\"(?<schema>[a-z_]+)\"\\.\"(?<table>[a-z_]+)\"");
    private static final Pattern APPLICATION_DDL_GRANT = Pattern.compile(
            "(?is)\\bGRANT\\s+CREATE\\b.*?\\bTO\\s+idea2strategy_(?:backend|batch|trading|backtest|pipeline)\\b");
    private static final Pattern APPLICATION_OWNERSHIP = Pattern.compile(
            "(?is)\\bALTER\\s+(?:DATABASE|SCHEMA|TABLE)\\b.*?\\bOWNER\\s+TO\\s+"
                    + "idea2strategy_(?:backend|batch|trading|backtest|pipeline)\\b");
    private static final String QUALIFIED_TABLE =
            "\"?(?<schema>[a-z_][a-z0-9_]*)\"?\\s*\\.\\s*\"?(?<table>[a-z_][a-z0-9_]*)\"?";
    private static final List<Pattern> MUTATION_TARGETS = List.of(
            Pattern.compile(
                    "(?is)\\b(?:CREATE\\s+TABLE|ALTER\\s+TABLE|INSERT\\s+INTO|UPDATE|DELETE\\s+FROM|"
                            + "TRUNCATE(?:\\s+TABLE)?)\\s+(?:ONLY\\s+)?"
                            + QUALIFIED_TABLE),
            Pattern.compile(
                    "(?is)\\bCREATE\\s+(?:UNIQUE\\s+)?INDEX\\b.*?\\bON\\s+" + QUALIFIED_TABLE));

    private static final Map<String, MigrationOwner> SCHEMA_OWNERS = Map.of(
            "identity", MigrationOwner.BACKEND,
            "strategy", MigrationOwner.BACKEND,
            "bot", MigrationOwner.BACKEND,
            "storage", MigrationOwner.SHARED,
            "market_data", MigrationOwner.PIPELINE,
            "trading", MigrationOwner.TRADING,
            "backtest", MigrationOwner.BACKTEST,
            "performance", MigrationOwner.BACKEND,
            "competition", MigrationOwner.BACKEND,
            "operations", MigrationOwner.BACKEND);
    private static final Set<String> TRADING_BOT_TABLES = Set.of(
            "bot_events", "evaluation_runs", "runtime_state_values", "runtime_state_changes");

    private DatabaseAccessPolicy() {}

    public static OwnershipManifest verifyBaselineOwnership(String baselineSql) {
        var ownership = new LinkedHashMap<QualifiedTable, MigrationOwner>();
        var matcher = CREATE_TABLE.matcher(baselineSql);
        while (matcher.find()) {
            var table = new QualifiedTable(matcher.group("schema"), matcher.group("table"));
            var owner = ownerFor(table);
            if (ownership.putIfAbsent(table, owner) != null) {
                throw new IllegalArgumentException("Table is declared more than once: " + table);
            }
        }
        if (ownership.isEmpty()) {
            throw new IllegalArgumentException("Baseline does not declare any schema-qualified tables");
        }
        return new OwnershipManifest(ownership);
    }

    public static boolean allows(ApplicationRole role, Access access, String schema, String table) {
        if (access == Access.DDL) {
            return false;
        }
        return switch (role) {
            case BACKTEST -> allowsBacktest(access, schema, table);
            case PIPELINE -> allowsPipeline(access, schema, table);
            case TRADING -> allowsTrading(access, schema, table);
            case BACKEND -> access == Access.READ || ownsBackendTable(schema, table);
            case BATCH -> access == Access.READ || Set.of("performance", "operations").contains(schema);
        };
    }

    public static void verifyNoApplicationDdlGrants(String migrationSql) {
        if (APPLICATION_DDL_GRANT.matcher(migrationSql).find()
                || APPLICATION_OWNERSHIP.matcher(migrationSql).find()) {
            throw new IllegalArgumentException("Application roles must not receive database DDL ownership");
        }
    }

    public static void verifyMigrationOwnership(MigrationOwner declaredOwner, String migrationSql) {
        for (var targetPattern : MUTATION_TARGETS) {
            var matcher = targetPattern.matcher(migrationSql);
            while (matcher.find()) {
                var table = new QualifiedTable(matcher.group("schema"), matcher.group("table"));
                var actualOwner = ownerFor(table);
                if (actualOwner != declaredOwner) {
                    throw new IllegalArgumentException(
                            "Migration owner " + declaredOwner.key() + " cannot mutate " + table
                                    + "; registered owner is " + actualOwner.key());
                }
            }
        }
    }

    private static MigrationOwner ownerFor(QualifiedTable table) {
        var schemaOwner = SCHEMA_OWNERS.get(table.schema());
        if (schemaOwner == null) {
            throw new IllegalArgumentException("No write owner is registered for schema: " + table.schema());
        }
        if ("bot".equals(table.schema()) && TRADING_BOT_TABLES.contains(table.table())) {
            return MigrationOwner.TRADING;
        }
        return schemaOwner;
    }

    private static boolean allowsBacktest(Access access, String schema, String table) {
        if ("backtest".equals(schema)) {
            return access == Access.READ || access == Access.INSERT || access == Access.UPDATE;
        }
        if (("strategy".equals(schema) || "market_data".equals(schema)) && access == Access.READ) {
            return true;
        }
        return "storage".equals(schema)
                && "objects".equals(table)
                && (access == Access.READ || access == Access.INSERT);
    }

    private static boolean allowsPipeline(Access access, String schema, String table) {
        if ("market_data".equals(schema)) {
            return access == Access.READ || access == Access.INSERT || access == Access.UPDATE;
        }
        return "storage".equals(schema)
                && "objects".equals(table)
                && (access == Access.READ || access == Access.INSERT);
    }

    private static boolean allowsTrading(Access access, String schema, String table) {
        if (access == Access.READ && Set.of("strategy", "market_data", "bot", "trading", "operations").contains(schema)) {
            return true;
        }
        return "trading".equals(schema)
                || ("bot".equals(schema) && TRADING_BOT_TABLES.contains(table))
                || "operations".equals(schema);
    }

    private static boolean ownsBackendTable(String schema, String table) {
        if (Set.of("identity", "strategy", "performance", "competition", "operations").contains(schema)) {
            return true;
        }
        return "bot".equals(schema) && !TRADING_BOT_TABLES.contains(table);
    }

    public enum ApplicationRole {
        BACKEND,
        BATCH,
        TRADING,
        BACKTEST,
        PIPELINE
    }

    public enum Access {
        READ,
        INSERT,
        UPDATE,
        DELETE,
        DDL
    }

    public record QualifiedTable(String schema, String table) {}

    public static final class OwnershipManifest {
        private final Map<QualifiedTable, MigrationOwner> ownership;

        private OwnershipManifest(Map<QualifiedTable, MigrationOwner> ownership) {
            this.ownership = Map.copyOf(ownership);
        }

        public MigrationOwner ownerOf(String schema, String table) {
            var qualifiedTable = new QualifiedTable(schema, table);
            var owner = ownership.get(qualifiedTable);
            if (owner == null) {
                throw new IllegalArgumentException("Unknown table: " + qualifiedTable);
            }
            return owner;
        }

        public Set<QualifiedTable> tables() {
            return ownership.keySet();
        }
    }
}
