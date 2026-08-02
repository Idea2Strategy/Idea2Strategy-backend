# Strategy and bot-control contract fixtures v1

These JSON examples let the trading and backtest consumers develop without the backend domain implementation.

- `basic-compiled-plan.valid.json`: immutable Basic execution snapshot, explicit warm-up requirements, and sequential candidate-producing plan.
- `bot-run-command.valid.json`: idempotent server-side bot run command.
- `bot-stop-command.valid.json`: idempotent permanent stop request.
- `official-backtest-request.valid.json`: the single official release backtest request.
- `*.unsupported-version.json`: examples consumers must reject without substitution.

The contract version is `strategy-bot.v1`. The plan checksum and message idempotency keys are lowercase SHA-256 values prefixed with `sha256:`. Their canonical material is assembled by `StrategyBotContractFixtures` with newline-separated named fields; plan step arguments are sorted by key. Consumers may independently reproduce the calculation or treat the supplied values as test vectors.

Each `requiredFeatures` entry pins a canonical feature UUID, exact `major.minor.patch` feature version, sorted official instrument UUIDs, a normalized positive ISO-8601 resolution, and a positive observation count. These fields are covered by `planChecksum`; consumers must reject duplicates or ambiguous values rather than infer from a feature name or default.

This boundary contains no Pro nodes, executable user code, external data sources, or direct-order command. `EMIT_ORDER_CANDIDATE` is evaluated by the trading service under its own budget, risk, and execution contracts.
