# Proposal: custom and competition backtest producers

Status: isolated proposal with an implementation candidate; not a protected canonical contract.

The current canonical product sources and implemented consumer contract support one
backend-produced backtest request: `strategy-bot.v1` `OFFICIAL_BACKTEST_REQUESTED`,
created atomically with an immutable BASIC strategy release. That request maps to
the `basic` SQS lane.

The implementation candidate now provides backend producers and explicit routing:

- `POST /api/v1/bots/{botId}/backtests` writes `CUSTOM_BACKTEST_REQUESTED`
  after owner and available-dataset coverage checks;
- a BACKTEST room evaluation start writes `COMPETITION_BACKTEST_REQUESTED`
  when the locked evaluation plan and immutable bot plan exist;
- both use a stable producer key, a semantic request hash for conflict detection,
  the transactional Outbox, and dedicated runtime queue URLs.

The following still require a protected canonical contract and matching consumer:

- a user command/API for a date-range backtest;
- the immutable inputs, authorization, idempotency scope, or payload contract for
  that command;
- a competition backtest request payload, whether it is one request per room,
  participation, evaluation plan, or evaluation period, or its ordering rules;
- consumer schemas or intake handlers for either request.

The current backtest engine does not yet consume `backtest-request.v1`, so these
events must not be enabled in a deployed relay until the matching consumer schemas
and intake handlers pass cross-repository compatibility tests.

After product-authority approval, integrate in this order:

1. Add versioned request contracts and consumer fixtures for both request kinds.
2. Add backtest-engine intake tests and durable run identity derivation.
3. Review and ratify the candidate producer payload fields and API behavior.
4. Verify all three paths against PostgreSQL and LocalStack SQS, then update the
   deployment environment.

The concurrency policy (`basic=2`, `custom=1`, `competition=1`, global `4`) belongs
to the backtest worker scheduler and does not alter producer delivery semantics.
