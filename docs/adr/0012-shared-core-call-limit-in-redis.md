# ADR-0012: One core-call rate limit for all copies, counted in Redis

- **Status:** Accepted
- **Date:** 2026-11-06

## Context

The core team limits calls per second for the platform as a whole. `bank.upgrade.concurrency` limits calls in flight
per copy, so three copies can call three times as fast.

## Decision

`UPGRADE_CORE_CALLS_PER_SECOND` sets a limit for all copies together. Each call increments a Redis counter for the
current second (key per epoch second, expiring after 10 s); over the limit, the call waits for the next second plus
a little jitter. If Redis cannot be reached the copies call without the limit and log a warning. 0 means no limit.

## Consequences

- The total stays under the limit (150 calls/s held with three copies in a live run).
- The upgrade service needs Redis, and connects at startup.
- A fixed window per second allows short bursts at second boundaries.
- Failing open means a Redis outage can exceed the core team's limit; failing closed would stop upgrades instead.
  Which is worse is a business call; confirm with the core team.

## Alternatives considered

- **A token bucket in a Redis script:** smoother, more complex.
- **Each copy taking limit ÷ copies:** needs to know how many copies run.
