# Flyway migration integration point

## Current baseline

- `V1__initial_schema.sql` is the executable PostgreSQL baseline generated from the Git-canonical `db/schema.dbml`.
- The source DBML baseline SHA-256 is `FD888356BC32F7E319A156C0FC4734B0FD01EBDDF5FB1C77EDAB0F7123B92005`.
- DBML `Records` blocks are review-only examples and are intentionally excluded from the migration.
- The baseline contains 10 schemas, 48 enum types, and 137 tables.
- After this baseline is shared, schema changes must be added as new versioned migrations; do not rewrite an applied migration.

## Post-baseline convention

All new files use `VyyyyMMddHHmmss__owner_description.sql`, where the version is a valid UTC timestamp and `owner` is one of:

- `backend`
- `trading`
- `backtest`
- `pipeline`
- `shared`

Versions are globally unique. The central verification rejects duplicate timestamps, unknown owners, invalid timestamps, legacy numeric versions after `V1`, and any change to the applied baseline checksum.

## Repository contribution contract

Each owner repository exposes new canonical migrations from a separate contribution root. The root contains `contribution.properties`:

```properties
contract.version=1
owner=trading
schemas=bot,trading
filename.regex=^V[0-9]{14}__trading_[a-z0-9]+(?:_[a-z0-9]+)*[.]sql$
runtime.flyway.enabled=false
migrations.directory=migrations
fixtures.directory=fixtures
```

- `owner` must match the owner token in every migration filename.
- `schemas` limits which schemas the contribution may mutate; table-level ownership is checked separately.
- `filename.regex` is required and every contributed SQL filename must match it as well as the central naming rule.
- `runtime.flyway.enabled` must be `false`; owner applications cannot claim migration execution.
- Only SQL files under `migrations.directory` enter the central Flyway bundle.
- Test fixtures and legacy standalone migrations stay under `fixtures.directory` or their existing test location and never enter the bundle.
- Both declared directories must stay inside the contribution root.

The central assembler combines this directory with the immutable baseline, rejects global timestamp collisions and ownership violations, and writes an ordered `migration-bundle.manifest` plus `migration-bundle.sha256`. The digest depends only on the ordered filenames and exact SQL bytes, so the same inputs always identify the same bundle.

From the backend repository, an integration workspace can run:

```text
gradlew :db-migration:run --args="<central-migration-dir> <empty-output-dir> [contribution-root ...]"
```

Flyway must execute only the resulting output directory. Application startup must not collect repository directories or run owner-local fixture migrations.

## A11 preference migration recovery

`V20260802050054__backend_account_preferences_theme.sql` is additive and PostgreSQL applies its DDL and backfill in one Flyway transaction. Take the normal database backup before deployment. If the transaction fails, correct the cause and rerun the unchanged migration after Flyway validation; do not edit the applied file.

After a successful deployment, older application versions can ignore the added column. Do not drop `theme_preference` or its enum as a rollback because that would destroy saved account preferences. Recover with a new timestamped forward-fix migration, or restore the pre-deployment backup only when the entire deployment must be reverted.

## A12 account lifecycle migrations

`V20260802060000__backend_account_lifecycle_dormant_status.sql` adds `DORMANT` in its own committed Flyway migration. PostgreSQL does not allow the new enum value to be referenced safely by DDL in the transaction that first adds it.

`V20260802060100__backend_account_lifecycle_contract.sql` upgrades and backfills lifecycle evidence, installs the current-projection/head guard, and adds immutable retention policy versions/rules, fail-closed obligations, legal holds, and 30-day keyed-HMAC identifier quarantines. It deliberately seeds no retention policy: deployment must install an actually approved policy through an independently reviewed forward migration or controlled administration path. Until then, destructive retention work records `RETENTION_POLICY_MISSING` and remains blocked.

`V20260802060200__backend_account_lifecycle_command_receipts.sql` adds immutable completed-command receipts. A receipt binds the account, command type, idempotency key, and request hash to the original HTTP-style status, response code/document, and optional same-account lifecycle event. This permits exact retries without weakening the append-only lifecycle event contract.

`V20260802060300__backend_oidc_step_up_nonces.sql` adds short-lived, server-issued OIDC step-up challenges. Only keyed-HMAC nonce digests are stored; raw nonces and ID tokens are never persisted. Successful DORMANT-to-ACTIVE commands consume a challenge in the same transaction as exact current-policy acceptance, lifecycle evidence, projection/head mutation, and the idempotent command receipt. Issuance deletes expired rows and enforces a provider-scoped pending-challenge ceiling under an advisory transaction lock. Each challenge also has a database-atomic five-attempt verification ceiling so repeated invalid tokens cannot drive unbounded upstream JWKS work.

`V20260802220000` through `V20260802220300` install the approved A12 ten-category retention policy and its backend/trading/backtest-owned execution boundaries. The upgrade backfills only CLOSED events at or after the immutable policy effective time; older missing-policy evidence remains fail-closed. Account-wide legal-hold serialization and identifier fingerprint locks make disposition, hold, release, and reuse mutually ordered.

`V20260802230000__backend_operator_room_permissions.sql` installs the approved E30 room read/manage permission codes without granting either permission to a role. Existing codes must retain the approved sensitivity or migration fails closed; role membership remains an explicit audited operations decision.

## A17 transactional outbox proposal migration

`V20260802231100__backend_transactional_outbox.sql` is the implementation candidate for isolated root proposal commit `52870121`. It adds a durable delivery head, append-only claim attempts, immutable replay lineage, and handler/message consumer receipts while keeping retry/lease numbers in versioned runtime configuration. Existing envelopes are backfilled without changing their payload or producer idempotency identity, and legacy writers remain compatible through a database-side envelope preparation trigger. This migration is not canonical or release-ready until the exact COM-A17 proposal is approved and integrated.

`V20260802231300__backend_user_case_contract.sql` prepares the isolated COM-A19 proposal as typed user-owned case heads, append-only event/evidence chains, and successful command receipts. It is not canonical or release-ready until exact proposal approval; A20 operator workflow remains outside this migration.

`V20260802231600__backend_account_sanction_commands.sql` prepares A14 account sanction heads, stable appeal references, append-only history, due-expiry lookup, and immutable idempotency receipts. It preserves account, strategy, trading, audit, and case data and remains non-canonical until the A13/A17 parents and product contract are approved.

These migrations are forward-only. If deployment fails, preserve their bytes, correct the environmental or data cause, and rerun after Flyway repair/validation. Do not drop lifecycle evidence, retention records, legal holds, or quarantine tombstones as rollback; restore the pre-deployment database backup only for a whole-release rollback, otherwise ship a new forward-fix migration.

각 도메인 소유자가 자신의 변경을 새 migration으로 작성하고, 중앙 통합 담당자가 순서 충돌과 `db/schema.dbml` 일치를 검토합니다.

적용된 migration 파일은 수정하지 않습니다. 실제 최초 migration은 DBML과 migration 계획을 함께 검토한 PR에서 추가합니다.
