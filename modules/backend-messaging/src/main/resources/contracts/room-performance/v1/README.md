# Room evaluation and performance contract fixtures v1

These examples let bot-control, trading, backtest, and leaderboard consumers develop without the room domain implementation.

- `*-room-schedule.valid.json`: versioned public, private, and platform-official room schedules.
- `evaluation-commands.valid.json`: initialize, start, end, and the mutually exclusive continue-as-private/stop command examples.
- `live-performance-input.valid.json`: an anonymous live-bot input inside the locked evaluation segment.
- `live-performance-input.after-end.json`: an event that must be rejected at the half-open segment end.
- `live-performance-input.backtest-rejected.json`: a backtest result that must never enter live room scoring.

The contract version is `room-performance.v1`. Live evaluation uses a half-open interval: the start instant is accepted and the end instant is excluded. Performance inputs expose an anonymous bot identifier and intentionally contain no user, account, or owner identity.
