# Proposal: custom and competition backtest producers

Status: isolated proposal; not approved, integrated, or release-ready.

The current canonical product sources and implemented consumer contract support one
backend-produced backtest request: `strategy-bot.v1` `OFFICIAL_BACKTEST_REQUESTED`,
created atomically with an immutable BASIC strategy release. That request maps to
the `basic` SQS lane.

The requested `custom` and `competition` lanes cannot yet have backend producers.
No canonical source currently defines:

- a user command/API for a date-range backtest;
- the immutable inputs, authorization, idempotency scope, or payload contract for
  that command;
- a competition backtest request payload, whether it is one request per room,
  participation, evaluation plan, or evaluation period, or its ordering rules;
- consumer schemas or intake handlers for either request.

Creating rows named `CUSTOM_BACKTEST_REQUESTED` or
`COMPETITION_BACKTEST_REQUESTED` before those points are settled would produce
durable messages that the current backtest engine cannot consume. It would also
turn an unapproved guess into an externally visible success path.

After product-authority approval, integrate in this order:

1. Add versioned request contracts and consumer fixtures for both request kinds.
2. Add backtest-engine intake tests and durable run identity derivation.
3. Add backend command/API tests for authorization, immutable inputs, duplicate
   requests, idempotency conflicts, and transaction rollback.
4. Insert each request into `operations.outbox_messages` in the same transaction as
   its owning aggregate mutation, preserving producer idempotency key, aggregate
   sequence, payload hash, retry attempts, and replay lineage.
5. Route the approved event types to `BACKTEST_CUSTOM_QUEUE_URL` and
   `BACKTEST_COMPETITION_QUEUE_URL`; retain
   `OFFICIAL_BACKTEST_REQUESTED` -> `BACKTEST_BASIC_QUEUE_URL`.
6. Verify all three paths against PostgreSQL and LocalStack SQS, then update the
   deployment environment.

The concurrency policy (`basic=2`, `custom=1`, `competition=1`, global `4`) belongs
to the backtest worker scheduler and does not alter producer delivery semantics.
