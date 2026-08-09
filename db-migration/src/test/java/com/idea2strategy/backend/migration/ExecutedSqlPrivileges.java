package com.idea2strategy.backend.migration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Derives, from a persistence adapter's own source, the table privileges the database will demand of
 * whatever role executes it.
 *
 * <p>This exists because three separate grant gaps reached the deployed environment on 2026-08-09
 * alone (backend #246, #249, #251), and each was found by watching something fail rather than by
 * comparing what a role holds against what its SQL asks for. Two of the three were the same trap:
 * PostgreSQL requires UPDATE on every table a {@code FOR UPDATE} names, so a privilege table derived
 * from UPDATE statements alone leaves a locked-but-never-updated table read-only. #251 was exactly
 * that — no adapter updates {@code bot.continuation_deadlines}, yet two of them lock it.
 *
 * <p>What this deliberately does not attempt: SQL assembled from values only known at run time, and
 * {@code FOR UPDATE} statements whose locked table cannot be read off the text. Both are reported as
 * unresolved rather than guessed at, because a check that silently skips what it cannot parse would
 * read as coverage it does not have.
 */
final class ExecutedSqlPrivileges {

    /** One privilege the executing role must hold for a specific table. */
    record Requirement(DatabaseAccessPolicy.Access access, String schema, String table, String because) {
        @Override
        public String toString() {
            return access + " on " + schema + "." + table + " (" + because + ")";
        }
    }

    private static final Pattern WRITE = Pattern.compile(
            "(insert\\s+into|update|delete\\s+from)\\s+([a-z_]+)\\.([a-z_]+)");
    private static final Pattern ALIASED_TABLE = Pattern.compile(
            "(?:from|join)\\s+([a-z_]+)\\.([a-z_]+)\\s+(?:as\\s+)?([a-z][a-z_0-9]*)");
    private static final Pattern LOCK_OF = Pattern.compile("for\\s+update\\s+of\\s+([a-z_0-9,\\s]+?)(?:\\s+skip\\s+locked|\\s+nowait|\\s|$)");
    private static final Pattern BARE_LOCK = Pattern.compile("for\\s+update(?!\\s+of)");
    private static final Pattern FROM_TABLE = Pattern.compile("from\\s+([a-z_]+)\\.([a-z_]+)");
    private static final Set<String> SQL_KEYWORDS_AFTER_ALIAS =
            Set.of("on", "where", "set", "using", "left", "inner", "join", "order", "group", "limit", "for");

    private ExecutedSqlPrivileges() {}

    /** Every {@code *JooqAdapter} and {@code *Store} source under a persistence tree. */
    static List<Path> adapterSources(Path persistenceMainJava) {
        try (Stream<Path> files = Files.walk(persistenceMainJava)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith("JooqAdapter.java") || name.endsWith("Store.java");
                    })
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** The adapter class names an application's own source names. */
    static Set<String> adaptersReferencedBy(Path applicationMainJava) {
        Pattern reference = Pattern.compile("([A-Z][A-Za-z0-9]*(?:JooqAdapter|Store))\\b");
        Set<String> names = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(applicationMainJava)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .toList()) {
                Matcher matcher = reference.matcher(readSource(file));
                while (matcher.find()) {
                    names.add(matcher.group(1));
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return names;
    }

    static List<Requirement> requirements(Path adapterSource) {
        String sql = normalizedSql(readSource(adapterSource));
        String origin = adapterSource.getFileName().toString();
        List<Requirement> requirements = new ArrayList<>();

        Matcher writes = WRITE.matcher(sql);
        while (writes.find()) {
            DatabaseAccessPolicy.Access access = switch (writes.group(1).replaceAll("\\s+", " ")) {
                case "insert into" -> DatabaseAccessPolicy.Access.INSERT;
                case "delete from" -> DatabaseAccessPolicy.Access.DELETE;
                default -> DatabaseAccessPolicy.Access.UPDATE;
            };
            requirements.add(new Requirement(
                    access, writes.group(2), writes.group(3), origin + " writes it"));
        }

        Map<String, String[]> aliases = new HashMap<>();
        Matcher aliased = ALIASED_TABLE.matcher(sql);
        while (aliased.find()) {
            String alias = aliased.group(3);
            if (SQL_KEYWORDS_AFTER_ALIAS.contains(alias)) {
                continue;
            }
            aliases.putIfAbsent(alias, new String[] {aliased.group(1), aliased.group(2)});
        }

        // PostgreSQL requires UPDATE on every table a FOR UPDATE names, whether or not an UPDATE
        // statement follows. Both spellings are covered: `for update of <alias>` resolves through the
        // aliases the same statement declared, and a bare `for update` locks what the statement selects
        // from, which for every adapter here is the nearest preceding FROM.
        Matcher lockOf = LOCK_OF.matcher(sql);
        while (lockOf.find()) {
            for (String alias : lockOf.group(1).split(",")) {
                String trimmed = alias.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String[] target = aliases.get(trimmed);
                if (target == null) {
                    throw new IllegalStateException(
                            origin + " locks alias '" + trimmed + "' but no FROM or JOIN in it declares that "
                                    + "alias. Resolve it here rather than leaving the lock unchecked.");
                }
                requirements.add(new Requirement(
                        DatabaseAccessPolicy.Access.UPDATE, target[0], target[1],
                        origin + " locks it with for update of " + trimmed));
            }
        }

        Matcher bareLock = BARE_LOCK.matcher(sql);
        while (bareLock.find()) {
            Matcher from = FROM_TABLE.matcher(sql.substring(0, bareLock.start()));
            String schema = null;
            String table = null;
            while (from.find()) {
                schema = from.group(1);
                table = from.group(2);
            }
            if (schema == null) {
                throw new IllegalStateException(
                        origin + " has a bare `for update` with no resolvable FROM before it. Resolve it here "
                                + "rather than leaving the lock unchecked.");
            }
            requirements.add(new Requirement(
                    DatabaseAccessPolicy.Access.UPDATE, schema, table, origin + " locks it with a bare for update"));
        }

        return requirements;
    }

    /**
     * Java source to comparable SQL: comments removed with a scanner rather than a regex so a {@code //}
     * inside a string literal cannot delete the rest of the line, adjacent string literals joined so a
     * statement split across concatenated fragments is still one statement, and everything lowercased.
     */
    static String normalizedSql(String source) {
        StringBuilder withoutComments = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == '/' && index + 1 < source.length()) {
                char next = source.charAt(index + 1);
                if (next == '/') {
                    while (index < source.length() && source.charAt(index) != '\n') {
                        index++;
                    }
                    continue;
                }
                if (next == '*') {
                    index += 2;
                    while (index + 1 < source.length() && !(source.charAt(index) == '*' && source.charAt(index + 1) == '/')) {
                        index++;
                    }
                    index = Math.min(source.length(), index + 2);
                    continue;
                }
            }
            if (current == '"' && source.startsWith("\"\"\"", index)) {
                int end = source.indexOf("\"\"\"", index + 3);
                end = end < 0 ? source.length() : end + 3;
                withoutComments.append(source, index, end);
                index = end;
                continue;
            }
            if (current == '"' || current == '\'') {
                withoutComments.append(current);
                index++;
                while (index < source.length() && source.charAt(index) != current) {
                    if (source.charAt(index) == '\\' && index + 1 < source.length()) {
                        withoutComments.append(source, index, index + 2);
                        index += 2;
                        continue;
                    }
                    withoutComments.append(source.charAt(index));
                    index++;
                }
                if (index < source.length()) {
                    withoutComments.append(current);
                    index++;
                }
                continue;
            }
            withoutComments.append(current);
            index++;
        }
        return withoutComments.toString()
                .replaceAll("\"\\s*\\+\\s*\"", "")
                .replaceAll("\\s+", " ")
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static String readSource(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
