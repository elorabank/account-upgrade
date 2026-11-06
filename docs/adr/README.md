# Architecture decision records

Why the platform is built the way it is. One record per decision, never rewritten: a changed decision gets a
new record that supersedes the old one. See [ADR-0001](0001-record-architecture-decisions.md) for the format.

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-reactive-services-with-webflux.md) | Reactive services with Spring WebFlux | Accepted |
| [0003](0003-one-service-per-step-one-schema-each.md) | One service per business step, one Oracle schema each | Accepted |
| [0004](0004-transactional-outbox.md) | Every event goes through a transactional outbox | Accepted |
| [0005](0005-idempotent-consumers-retries-dead-letters.md) | Idempotent consumers, retries and dead-letter topics | Accepted |
| [0006](0006-spring-batch-for-the-excel-load.md) | Spring Batch for the Excel load | Accepted |
| [0007](0007-planning-pulls-accounts-over-http.md) | Planning pulls a loaded file's accounts over an internal HTTP stream | Accepted |
| [0008](0008-core-calls-outside-transactions.md) | Core-system calls outside transactions, with idempotency keys | Accepted |
| [0009](0009-database-lock-for-scheduled-jobs.md) | Scheduled jobs run once at a time under a database lock (ShedLock) | Accepted for the load; superseded by ADR-0010 for the upgrade run |
| [0010](0010-upgrade-copies-share-a-run-through-leases.md) | Several upgrade copies share one run through leases | Accepted (supersedes ADR-0009 for the upgrade run) |
| [0011](0011-run-now-broadcast-over-kafka.md) | "Run now" is broadcast to every copy over Kafka | Accepted |
| [0012](0012-shared-core-call-limit-in-redis.md) | One core-call rate limit for all copies, counted in Redis | Accepted |
| [0013](0013-keycloak-and-provider-neutral-security.md) | Keycloak locally, security that works with any OIDC provider | Accepted |
| [0014](0014-gateway-login-sessions-in-redis.md) | Sign-in at the gateway, sessions in Redis, tokens relayed | Accepted |
| [0015](0015-insert-only-audit-trail-without-personal-data.md) | An insert-only audit trail without personal data | Accepted; old months archived by ADR-0022 |
| [0016](0016-monitoring-prometheus-grafana-alertmanager.md) | Monitoring with Prometheus, Grafana and Alertmanager; metrics need a scope | Accepted |
| [0017](0017-tests-without-containers-or-databases.md) | Tests without containers or databases | Accepted |
| [0018](0018-dependency-updates-minor-and-patch-only.md) | Dependency updates: minor and patch only, majors on purpose | Accepted; Dependabot paused since 2026-11-06 |
| [0019](0019-daily-retention-with-batched-deletes.md) | Daily retention with batched deletes | Accepted; partition drops added by ADR-0021 |
| [0020](0020-letters-keep-values-not-html.md) | Letters keep their template version and values, not the HTML | Accepted |
| [0021](0021-partition-the-big-tables.md) | Partition the big tables; the clean-up drops whole partitions | Accepted |
| [0022](0022-audit-archive-files.md) | Old audit months move to checked archive files | Accepted |
