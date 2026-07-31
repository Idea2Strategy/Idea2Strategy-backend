# Common contract fixtures v1

These fixtures define the shared authentication principal, API error, pagination, and event envelope contracts.

- `schemaVersion` is required and exact; unsupported versions are rejected.
- Unknown JSON fields are ignored so additive producer changes remain forward compatible.
- Persist and exchange timestamps as UTC ISO-8601 instants. Convert to `America/New_York` only for Eastern Time presentation.
- Correlation and idempotency values propagate from the request context into emitted events and errors.
- Fixtures contain identifiers and roles only. Credentials, tokens, private configuration, and business content are prohibited.
