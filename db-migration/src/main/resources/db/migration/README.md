# Flyway migration integration point

## Pre-launch V1 baseline

`V1__initial_schema.sql` is the immutable executable PostgreSQL baseline established on 2026-08-13 after the Development AWS environment was retired.

It is a deterministic dump of the final schema and canonical seed/reference data produced by the last historical migration bundle. The rebaseline equivalence check recorded:

- baseline SHA-256: `e3bec37557b570d9a33c10a07e3e5c706ab56105dec409ad5ddb800720517282`
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
