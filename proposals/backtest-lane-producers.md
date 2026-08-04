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

The follow-up producer candidate carries every immutable input that can currently
be resolved without inventing protected meaning. Custom requests expose the
requesting account, dataset hash, compiled-plan instrument catalog, initial cash,
and accounting assumptions. Competition requests carry the locked room/scoring
facts and the complete ordered hidden-period input bundle, including shared dataset
and feature-materialization evidence. Hidden periods exist only in the internal
Outbox/SQS payload and are not copied to participant or public room events.

Two approved-contract obligations remain unresolvable from the current backend
schema: neither a custom bot release nor a competition room has a locked reference
to the backtest engine's `ExecutionPolicyCatalog.version`, and the period-run
linkage contract has no approved command/event representation. Accounting rules
are not an execution-policy version and must not be relabelled as one. These gaps
must remain explicit blockers rather than guessed constants or derived identifiers.

The backtest engine's `backtest-request.v1` intake must therefore remain guarded,
and the custom/competition relay routes must stay disabled until the protected
execution-policy/linkage decision is approved and cross-repository compatibility
tests pass.

After product-authority approval, integrate in this order:

1. Approve and persist the execution-policy reference and competition period-run
   linkage without conflating them with accounting rules.
2. Complete backtest-engine intake tests and durable run identity derivation.
3. Ratify the additive producer payload fields and API failure behavior.
4. Verify all three paths against PostgreSQL and LocalStack SQS, then update the
   deployment environment.

The concurrency policy (`basic=2`, `custom=1`, `competition=1`, global `4`) belongs
to the backtest worker scheduler and does not alter producer delivery semantics.
