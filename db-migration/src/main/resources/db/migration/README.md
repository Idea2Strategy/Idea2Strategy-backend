# Flyway migration integration point

## Current baseline

- `V1__initial_schema.sql` is the executable PostgreSQL baseline generated from the Git-canonical `db/schema.dbml`.
- The source DBML baseline SHA-256 is `FD888356BC32F7E319A156C0FC4734B0FD01EBDDF5FB1C77EDAB0F7123B92005`.
- DBML `Records` blocks are review-only examples and are intentionally excluded from the migration.
- The baseline contains 10 schemas, 48 enum types, and 137 tables.
- After this baseline is shared, schema changes must be added as new versioned migrations; do not rewrite an applied migration.

각 도메인 소유자가 자신의 변경을 새 migration으로 작성하고, 중앙 통합 담당자가 순서 충돌과 `db/schema.dbml` 일치를 검토합니다.

적용된 migration 파일은 수정하지 않습니다. 실제 최초 migration은 DBML과 migration 계획을 함께 검토한 PR에서 추가합니다.
