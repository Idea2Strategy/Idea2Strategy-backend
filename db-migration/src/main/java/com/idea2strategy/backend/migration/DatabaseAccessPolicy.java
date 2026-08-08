package com.idea2strategy.backend.migration;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class DatabaseAccessPolicy {
    public static final String RUNTIME_GRANTS_FILE = "R__database_runtime_grants.sql";
    private static final String ROLE_PREFIX = "idea2strategy_";
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?i)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?"
                    + "\"?(?<schema>[a-z_][a-z0-9_]*)\"?\\s*\\.\\s*"
                    + "\"?(?<table>[a-z_][a-z0-9_]*)\"?");
    private static final Pattern DROP_TABLE = Pattern.compile(
            "(?i)DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?"
                    + "\"?(?<schema>[a-z_][a-z0-9_]*)\"?\\s*\\.\\s*"
                    + "\"?(?<table>[a-z_][a-z0-9_]*)\"?");
    private static final Pattern APPLICATION_DDL_GRANT = Pattern.compile(
            "(?is)\\bGRANT\\s+CREATE\\b.*?\\bTO\\s+idea2strategy_(?:backend|batch|trading|backtest|pipeline)\\b");
    private static final Pattern APPLICATION_OWNERSHIP = Pattern.compile(
            "(?is)\\bALTER\\s+(?:DATABASE|SCHEMA|TABLE)\\b.*?\\bOWNER\\s+TO\\s+"
                    + "idea2strategy_(?:backend|batch|trading|backtest|pipeline)\\b");
    private static final String QUALIFIED_TABLE =
            "\"?(?<schema>[a-z_][a-z0-9_]*)\"?\\s*\\.\\s*\"?(?<table>[a-z_][a-z0-9_]*)\"?";
    private static final List<Pattern> MUTATION_TARGETS = List.of(
            Pattern.compile(
                    "(?is)\\b(?:INSERT\\s+INTO|UPDATE|DELETE\\s+FROM|TRUNCATE(?:\\s+TABLE)?)\\s+(?:ONLY\\s+)?"
                            + QUALIFIED_TABLE),
            Pattern.compile(
                    "(?is)\\b(?:CREATE(?:\\s+OR\\s+REPLACE)?|ALTER|DROP)\\s+"
                            + "(?:MATERIALIZED\\s+)?(?:TABLE|VIEW|TYPE|SEQUENCE)\\s+"
                            + "(?:IF\\s+(?:NOT\\s+)?EXISTS\\s+)?"
                            + "(?:ONLY\\s+)?"
                            + QUALIFIED_TABLE),
            Pattern.compile(
                    "(?is)\\bCREATE\\s+(?:UNIQUE\\s+)?INDEX\\b.*?\\bON\\s+" + QUALIFIED_TABLE));

    private static final Map<String, MigrationOwner> SCHEMA_OWNERS = Map.of(
            "identity", MigrationOwner.BACKEND,
            "strategy", MigrationOwner.BACKEND,
            "bot", MigrationOwner.BACKEND,
            "storage", MigrationOwner.PIPELINE,
            "market_data", MigrationOwner.PIPELINE,
            "trading", MigrationOwner.TRADING,
            "backtest", MigrationOwner.BACKTEST,
            "performance", MigrationOwner.BACKEND,
            "competition", MigrationOwner.BACKEND,
            "operations", MigrationOwner.BACKEND);
    private static final Set<String> TRADING_BOT_TABLES = Set.of(
            "bot_events", "evaluation_runs", "runtime_state_values", "runtime_state_changes");

    /**
     * Tables {@code backend-batch} updates outside the schemas it owns. See
     * {@link #allowsBatchScheduledWrite}: these three are also the tables its {@code ... for update}
     * statements lock, and PostgreSQL requires UPDATE on every table a FOR UPDATE names.
     */
    private static final Set<QualifiedTable> BATCH_UPDATED_TABLES = Set.of(
            new QualifiedTable("competition", "rooms"),
            new QualifiedTable("competition", "participations"),
            new QualifiedTable("bot", "bots"));

    /** Tables {@code backend-batch} appends to outside the schemas it owns. */
    private static final Set<QualifiedTable> BATCH_INSERTED_TABLES = Set.of(
            new QualifiedTable("competition", "room_events"),
            new QualifiedTable("competition", "participation_events"),
            new QualifiedTable("competition", "backtest_period_runs"),
            new QualifiedTable("competition", "live_evaluation_segments"),
            new QualifiedTable("bot", "continuation_deadlines"),
            new QualifiedTable("backtest", "runs"));

    private DatabaseAccessPolicy() {}

    public static OwnershipManifest verifyBaselineOwnership(String baselineSql) {
        return ownershipManifest(List.of(baselineSql));
    }

    public static OwnershipManifest ownershipManifest(List<String> migrationSql) {
        var ownership = new LinkedHashMap<QualifiedTable, MigrationOwner>();
        for (var sql : migrationSql) {
            var matcher = CREATE_TABLE.matcher(sql);
            while (matcher.find()) {
                var table = new QualifiedTable(matcher.group("schema"), matcher.group("table"));
                var owner = ownerFor(table);
                // A guarded legacy-schema upgrade may redeclare a table that already exists in
                // the immutable fresh-install baseline. Ownership is derived from the qualified
                // table name, so repeated declarations cannot change owners and the ACL manifest
                // must retain exactly one entry.
                ownership.putIfAbsent(table, owner);
            }
            var dropped = DROP_TABLE.matcher(sql);
            while (dropped.find()) {
                ownership.remove(new QualifiedTable(dropped.group("schema"), dropped.group("table")));
            }
        }
        if (ownership.isEmpty()) {
            throw new IllegalArgumentException("Baseline does not declare any schema-qualified tables");
        }
        return new OwnershipManifest(ownership);
    }

    /**
     * Builds the repeatable Flyway unit that turns this policy into PostgreSQL ACLs.
     *
     * <p>The generated roles are deliberately NOLOGIN group roles. Deployment creates or rotates
     * credential-bearing login roles separately, then grants exactly one group role to each login.
     * This keeps credentials out of migrations and makes this class the single source for table
     * privileges.
     */
    public static String runtimeGrantSql(List<String> migrationSql) {
        var manifest = ownershipManifest(migrationSql);
        var tables = manifest.tables().stream()
                .sorted(Comparator.comparing(QualifiedTable::schema).thenComparing(QualifiedTable::table))
                .toList();
        var schemas = tables.stream().map(QualifiedTable::schema).distinct().sorted().toList();
        var sql = new StringBuilder("-- Generated by DatabaseAccessPolicy; do not hand-edit.\n");

        for (var role : ApplicationRole.values()) {
            var roleName = databaseRole(role);
            sql.append("DO $$ BEGIN\n")
                    .append("  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '")
                    .append(roleName)
                    .append("') THEN CREATE ROLE ")
                    .append(roleName)
                    .append(" NOLOGIN; END IF;\n")
                    .append("END $$;\n")
                    .append("ALTER ROLE ")
                    .append(roleName)
                    .append(" NOLOGIN NOCREATEDB NOCREATEROLE NOINHERIT;\n")
                    .append("DO $$ BEGIN\n")
                    .append("  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '")
                    .append(roleName)
                    .append("' AND (rolsuper OR rolreplication OR rolbypassrls OR rolcanlogin OR rolcreatedb OR rolcreaterole OR rolinherit)) THEN\n")
                    .append("    RAISE EXCEPTION 'application group role ")
                    .append(roleName)
                    .append(" has forbidden privileged attributes';\n")
                    .append("  END IF;\n")
                    .append("END $$;\n")
                    .append("DO $$ BEGIN\n")
                    .append("  IF EXISTS (SELECT 1 FROM pg_database WHERE datdba = '")
                    .append(roleName)
                    .append("'::regrole) OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner = '")
                    .append(roleName)
                    .append("'::regrole) OR EXISTS (SELECT 1 FROM pg_class WHERE relowner = '")
                    .append(roleName)
                    .append("'::regrole) THEN\n")
                    .append("    RAISE EXCEPTION 'application group role ")
                    .append(roleName)
                    .append(" must not own database objects';\n")
                    .append("  END IF;\n")
                    .append("END $$;\n")
                    .append("DO $$ BEGIN EXECUTE format('GRANT CONNECT ON DATABASE %I TO ")
                    .append(roleName)
                    .append("', current_database()); END $$;\n");
            for (var schema : schemas) {
                sql.append("REVOKE ALL PRIVILEGES ON SCHEMA ")
                        .append(quoted(schema)).append(" FROM ").append(roleName).append(";\n")
                        .append("REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA ")
                        .append(quoted(schema)).append(" FROM ").append(roleName).append(";\n")
                        .append("REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA ")
                        .append(quoted(schema)).append(" FROM ").append(roleName).append(";\n");
            }
        }
        for (var schema : schemas) {
            sql.append("REVOKE ALL PRIVILEGES ON SCHEMA ").append(quoted(schema)).append(" FROM PUBLIC;\n")
                    .append("REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA ")
                    .append(quoted(schema)).append(" FROM PUBLIC;\n")
                    .append("REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA ")
                    .append(quoted(schema)).append(" FROM PUBLIC;\n");
        }

        for (var role : ApplicationRole.values()) {
            var roleName = databaseRole(role);
            var usedSchemas = new java.util.TreeSet<String>();
            for (var table : tables) {
                var accesses = EnumSet.noneOf(Access.class);
                for (var access : List.of(Access.READ, Access.INSERT, Access.UPDATE, Access.DELETE)) {
                    if (allows(role, access, table.schema(), table.table())) {
                        accesses.add(access);
                    }
                }
                if (!accesses.isEmpty()) {
                    usedSchemas.add(table.schema());
                    sql.append("GRANT ")
                            .append(accesses.stream().map(DatabaseAccessPolicy::sqlPrivilege).toList()
                                    .stream().collect(java.util.stream.Collectors.joining(", ")))
                            .append(" ON TABLE ").append(quoted(table.schema())).append(".")
                            .append(quoted(table.table())).append(" TO ").append(roleName).append(";\n");
                }
            }
            for (var schema : usedSchemas) {
                sql.append("GRANT USAGE ON SCHEMA ").append(quoted(schema))
                        .append(" TO ").append(roleName).append(";\n");
            }
        }
        return sql.toString();
    }

    public static String databaseRole(ApplicationRole role) {
        return ROLE_PREFIX + role.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String sqlPrivilege(Access access) {
        return switch (access) {
            case READ -> "SELECT";
            case INSERT -> "INSERT";
            case UPDATE -> "UPDATE";
            case DELETE -> "DELETE";
            case DDL -> throw new IllegalArgumentException("DDL is never an application privilege");
        };
    }

    private static String quoted(String identifier) {
        return "\"" + identifier + "\"";
    }

    public static boolean allows(ApplicationRole role, Access access, String schema, String table) {
        if (access == Access.DDL) {
            return false;
        }
        return switch (role) {
            case BACKTEST -> allowsBacktest(access, schema, table);
            case PIPELINE -> allowsPipeline(access, schema, table);
            case TRADING -> allowsTrading(access, schema, table);
            case BACKEND -> access == Access.READ
                    || ownsBackendTable(schema, table)
                    || (access == Access.INSERT && "backtest".equals(schema) && Set.of(
                            "runs",
                            "input_bundles",
                            "input_datasets",
                            "input_feature_materializations",
                            "run_input_pins").contains(table));
            case BATCH -> access == Access.READ
                    || Set.of("performance", "operations").contains(schema)
                    || allowsBatchScheduledWrite(access, schema, table);
        };
    }

    /**
     * The writes {@code backend-batch}'s scheduled jobs actually perform, outside the two schemas it
     * owns outright.
     *
     * <p>Derived from the write statements of the six adapters the batch application imports —
     * {@code RoomScheduleTransition}, {@code RoomEvaluationStart}, {@code PrivateContinuationTransition},
     * {@code PostEvaluationStopTransition}, {@code BotRunCommand} and {@code BotStopCommand}. Read
     * access already comes from the {@code Access.READ} branch above, so only the mutations are listed.
     *
     * <p>No adapter deletes, so {@code DELETE} is deliberately absent: the batch may append events and
     * advance state, never remove a room, bot or run.
     *
     * <p>{@code competition.rooms}, {@code competition.participations} and {@code bot.bots} need
     * {@code UPDATE} for a second reason beyond their update statements. These adapters take
     * {@code ... for update}, and PostgreSQL requires {@code UPDATE} on every table a {@code FOR UPDATE}
     * names. That is the same trap as backend #241 with the opposite resolution — there the lock was
     * unnecessary and was removed, here the locks are load-bearing so the privilege has to match.
     */
    private static boolean allowsBatchScheduledWrite(Access access, String schema, String table) {
        return switch (access) {
            case UPDATE -> BATCH_UPDATED_TABLES.contains(new QualifiedTable(schema, table));
            case INSERT -> BATCH_INSERTED_TABLES.contains(new QualifiedTable(schema, table));
            default -> false;
        };
    }

    public static void verifyNoApplicationDdlGrants(String migrationSql) {
        if (APPLICATION_DDL_GRANT.matcher(migrationSql).find()
                || APPLICATION_OWNERSHIP.matcher(migrationSql).find()) {
            throw new IllegalArgumentException("Application roles must not receive database DDL ownership");
        }
    }

    public static void verifyMigrationOwnership(MigrationOwner declaredOwner, String migrationSql) {
        verifyMigrationOwnership(declaredOwner, null, migrationSql);
    }

    public static void verifyMigrationOwnership(
            MigrationOwner declaredOwner, Set<String> declaredSchemas, String migrationSql) {
        for (var targetPattern : MUTATION_TARGETS) {
            var matcher = targetPattern.matcher(migrationSql);
            while (matcher.find()) {
                var table = new QualifiedTable(matcher.group("schema"), matcher.group("table"));
                if (declaredSchemas != null && !declaredSchemas.contains(table.schema())) {
                    throw new IllegalArgumentException(
                            "Migration mutates schema outside its contribution contract: " + table.schema());
                }
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
        // The request intake claims a transactional consumer receipt before it runs anything, so it
        // reads the producer's outbox row and owns its own receipt row. Two tables only — widening the
        // whole operations schema would hand the worker the audit trail and the operator case tables,
        // which it has no reason to see.
        if ("operations".equals(schema)) {
            if ("outbox_messages".equals(table)) {
                return access == Access.READ;
            }
            // The intake selects, inserts and updates receipts. It never deletes one, so DELETE stays
            // out even though the backend, batch and trading roles hold it on this table.
            return "outbox_consumer_receipts".equals(table)
                    && (access == Access.READ || access == Access.INSERT || access == Access.UPDATE);
        }
        return "storage".equals(schema)
                && "objects".equals(table)
                && (access == Access.READ || access == Access.INSERT);
    }

    private static boolean allowsPipeline(Access access, String schema, String table) {
        if ("market_data".equals(schema)) {
            return access == Access.READ || access == Access.INSERT || access == Access.UPDATE;
        }
        if ("operations".equals(schema)
                && Set.of("operator_accounts", "audit_events").contains(table)) {
            return access == Access.READ;
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
