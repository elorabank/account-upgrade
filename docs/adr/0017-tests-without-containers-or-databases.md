# ADR-0017: Tests without containers or databases

- **Status:** Accepted
- **Date:** 2026-11-06

## Context

The integration tests started Oracle, Kafka, Redis, SFTP and mail servers with Testcontainers. CI failed on every run
because Testcontainers 1.21.3 was too old for GitHub's Docker, and a full build took about 6 minutes, mostly
starting containers. The project owner chose simple, fast tests.

## Decision

`mvn verify` runs unit tests with mocks or small fakes and API tests (`@ApiTest`: the controllers and the real
security chain with signed test tokens). No test starts a container or a database. Behaviour that lives in Oracle,
Kafka or Redis is checked locally against docker-compose with `perf/run.sh`.

## Consequences

- About 400 tests in about a minute, and CI passes.
- Not covered automatically: the SQL itself (claims with `SKIP LOCKED`, the outbox, Spring Batch, the Liquibase
  scripts), several copies at once, Kafka's ordering and redelivery, gateway sessions in Redis. Run a local
  end-to-end check before releases.

## Alternatives considered

- **Keep the container tests and upgrade Testcontainers to 1.21.4,** running them as a separate, slower suite (the
  common industry practice). Rejected for simplicity; worth revisiting before production.
