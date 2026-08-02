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

각 도메인 소유자가 자신의 변경을 새 migration으로 작성하고, 중앙 통합 담당자가 순서 충돌과 `db/schema.dbml` 일치를 검토합니다.

적용된 migration 파일은 수정하지 않습니다. 실제 최초 migration은 DBML과 migration 계획을 함께 검토한 PR에서 추가합니다.
