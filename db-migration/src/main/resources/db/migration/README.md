# Flyway migration integration point

## Pre-launch V1 baseline

`V1__initial_schema.sql` is the immutable executable PostgreSQL baseline established on 2026-08-13 after the Development AWS environment was retired.

It is a deterministic dump of the final schema and canonical seed/reference data produced by the last historical migration bundle. The rebaseline equivalence check recorded:

- baseline SHA-256: `ccc5950a4d23b9c8f07b895a83dbea18a9fb072523b56b121eecb65bec15c533`
- schema fingerprint: `301075f9a6c9c1dd5b1f766d099b5fdd3b6149bfc5842fb0f59671e91e719571`
- seed-data fingerprint: `e14e81ad82c679420aa3f0c27a48a82f853d3fcc7fcea214ce1ef626599076db`
- 10 application schemas, 70 enum types, and 181 application tables

The pre-baseline timestamped migrations remain available in Git history but are not active Flyway inputs. Do not restore them to this directory.

## Post-V1 development

V1 is immutable. Every schema or canonical seed change after this baseline uses a new file named:

```text
VyyyyMMddHHmmss__owner_description.sql
```

The timestamp is UTC and globally unique. `owner` is one of `backend`, `trading`, `backtest`, `pipeline`, or `shared`. Applied files are never edited; corrections use a later forward migration.

## Repository contribution contract

Trading, backtest, and data-pipeline repositories retain their `db/migration-contributions` roots. Each root declares its owner, schemas, filename regex, and active `migrations` directory in `contribution.properties`. Future owner migrations enter the central bundle through those directories. Fixtures never enter the runtime bundle.

The central assembler validates the immutable V1 checksum, migration naming, global timestamp uniqueness, schema ownership, and contribution metadata. It writes a deterministic manifest and bundle digest.

## Runtime database grants

Every assembled bundle ends with generated repeatable migration `R__database_runtime_grants.sql`. `DatabaseAccessPolicy` remains its single source of truth. It creates credential-free group roles, revokes public application access, and grants only the required schema and table privileges.

Environment-specific login roles and passwords remain deployment/bootstrap concerns and never appear in migration SQL.

## `storage.objects` event trigger and RDS major upgrades

`V20260902000001__pipeline_bind_backtest_cleanup_ownership.sql` installs the narrowly
scoped `storage_reject_unvalidated_object_fks` event trigger. It runs only after
`ALTER TABLE`, `CREATE TABLE`, or `CREATE TABLE AS`, and rejects only a command that
leaves an unvalidated foreign key targeting `storage.objects`. Unrelated DDL is not
blocked.

PostgreSQL restricts event-trigger creation to superusers. The Development deployment
contract satisfies that requirement without relying on the application role:

- `infra/terraform/environments/development/database.tf` creates the RDS master user
  `idea2strategy_admin` with an AWS-managed master secret.
- `scripts/aws/development-database-bootstrap.sh` resolves that exact
  `master_user_secret` and supplies its username and password to Flyway.
- The migration fails explicitly unless Flyway's current user is a PostgreSQL
  superuser or a member of AWS RDS's `rds_superuser` role.

AWS RDS requires event triggers to be removed before a major-version upgrade. During
the upgrade maintenance window, stop application and migration traffic, then run as
the RDS master user:

```sql
DROP EVENT TRIGGER storage_reject_unvalidated_object_fks;
```

Immediately after the upgrade, first verify that no unsafe constraint was introduced:

```sql
SELECT n.nspname AS source_schema, c.relname AS source_table, fk.conname
FROM pg_constraint AS fk
JOIN pg_class AS c ON c.oid = fk.conrelid
JOIN pg_namespace AS n ON n.oid = c.relnamespace
WHERE fk.contype = 'f'
  AND fk.confrelid = 'storage.objects'::regclass
  AND NOT fk.convalidated;
```

The result must be empty. Then recreate the trigger from the already-migrated function
and verify that it is enabled:

```sql
CREATE EVENT TRIGGER storage_reject_unvalidated_object_fks
ON ddl_command_end
WHEN TAG IN ('ALTER TABLE', 'CREATE TABLE', 'CREATE TABLE AS')
EXECUTE FUNCTION storage.reject_unvalidated_storage_object_fks();

SELECT evtname, evtenabled
FROM pg_event_trigger
WHERE evtname = 'storage_reject_unvalidated_object_fks';
```

Do not resume cleanup traffic unless the constraint query is empty and the trigger is
present with `evtenabled = 'O'`. See the PostgreSQL event-trigger privilege contract
and the AWS RDS major-upgrade event-trigger prerequisite:

- <https://www.postgresql.org/docs/current/sql-createeventtrigger.html>
- <https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_UpgradeDBInstance.PostgreSQL.MajorVersion.html>
