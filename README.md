# Account Upgrade Platform

Spring Boot (WebFlux) microservices for the daily bank account upgrade.

| Module | Port | Purpose |
|---|---|---|
| common | - | Shared library: Kafka topics and event envelope, outbox + consumer template, scheduler locks, correlation id, Okta resource-server security |
| common-test | - | Test-only helpers (Oracle Testcontainer) |
| file-ingestion-service | 8081 | SFTP fetch, Excel load |
| account-upgrade-service | 8082 | Daily upgrade job |
| communication-service | 8083 | Welcome letter HTML, sent as the email body |
| audit-service | 8085 | Audit trail of every business step and user action |
| api-gateway | 8080 | Entry point, Okta login, serves the ops console |
| ops-ui | - | The ops console (React + TypeScript), built by Maven into the gateway |

## Run locally

```bash
docker compose up -d      # Oracle, Kafka, Kafka UI, SFTP, Mailpit, Keycloak, Redis
mvn clean verify          # build and test (tests never connect to this Kafka; see the surefire setting in each service pom)
SPRING_PROFILES_ACTIVE=local mvn -pl file-ingestion-service spring-boot:run
```

Run the services with the `local` profile. It holds the local database and SFTP logins, accepts the compose SFTP's
changing host key, and uses Keycloak from docker-compose as the identity provider. Without it a service has no
passwords and verifies the SFTP host key, which is what every real environment should do.

Mailpit UI: http://localhost:8025 · Kafka UI: http://localhost:8070 · SFTP: localhost:2222 · Oracle: localhost:1521/FREEPDB1 · Keycloak: http://localhost:8180
Schedules run in `bank.time-zone` (env `BANK_TIME_ZONE`, default `Asia/Kolkata`), never in the server's zone.
Run Maven with JDK 21 (BlockHound does not work on newer JDKs yet).

### In IntelliJ

Open the project folder (it is a Maven project). Shared run configurations are in `.run/`:
`file-ingestion-service (local)`, `account-upgrade-service (local)`, `communication-service (local)`,
`audit-service (local)`, `api-gateway (local)`, and `Upgrade flow (all services)`, which starts all five. They set `SPRING_PROFILES_ACTIVE=local` and short schedules
(load every minute, upgrade at :30 past each minute).
The project SDK must be JDK 21, and annotation processing must be on (Lombok).

### Test data

`samples/accounts_20261110.xlsx` has 10,000 rows with a known mix of good, rejected and broken rows. Make a fresh one
for another day (each date gives new content) with `python3 samples/make_sample_accounts.py 20261111 10000`; it prints
the counts to expect. Upload with `sftp -P 2222 bank@localhost` (password `bank_local_pw`), then `put <file> incoming/`.

## Security (identity provider)

Every service validates access tokens from one identity provider (audience `api://account-upgrade`). Locally that is
**Keycloak** in docker-compose; the code also works with Okta or Auth0 (see below). Groups become roles
(`AU_OPS_ADMIN`, `AU_OPS_VIEWER`, `AU_SUPPORT`, `AU_AUDITOR`) and granted scopes (`upgrade.read`, `upgrade.admin`,
`audit.read`, `notification.resend`, `staging.read`) are accepted for service-to-service calls; scopes are read from the
standard `scope` claim or Okta's `scp`. The access matrix is in `common/.../security/ApiAccessRules.java`; any `/api/**`
call without a rule is refused. `/internal/**` is for services only (a scope, never a role) and the gateway does not
route it: today that is `GET /internal/files/{id}/accounts`, scope `staging.read`.

The gateway signs people in (authorization code + PKCE), keeps the session, and passes the user's access token to the
services. Signing out ends the session at the identity provider too.

**Sessions in Redis.** The gateway keeps login sessions (who is signed in, their tokens, a login in progress) in Redis
through Spring Session, not in its own memory. Any gateway copy can serve any signed-in person, and a restart or
redeploy signs nobody out. Sessions expire after 30 minutes without activity (`spring.session.timeout`). They hold
access tokens, so Redis must be private and password protected, with TLS in the cloud: `REDIS_HOST`, `REDIS_PORT`,
`REDIS_PASSWORD`, `REDIS_SSL`. Locally the `local` profile uses the compose Redis (password `local-redis-pw`). The
gateway connects to Redis at startup, so it does not start without it. Postman and other systems can send
`Authorization: Bearer <token>` straight to the gateway.

**Keycloak locally.** `docker compose up -d` starts Keycloak on http://localhost:8180 (admin console: admin / admin)
and `keycloak-setup`, which creates the realm `account-upgrade` (`infra/keycloak/setup-realm.sh`):

| User (password `local-pw`) | Group |
|---|---|
| `olivia.ops` | AU_OPS_ADMIN |
| `victor.view` | AU_OPS_VIEWER |
| `sam.support` | AU_SUPPORT |
| `ada.audit` | AU_AUDITOR |

| Client (secret `local-secret`) | Used by | May ask for |
|---|---|---|
| `api-gateway` | the gateway's login | the person's groups |
| `account-upgrade-service` | the upgrade service, client credentials | `staging.read` |
| `local-auditor` | you, to try the audit API with curl | `audit.read` |

Keycloak keeps no data locally: when its container is recreated, `keycloak-setup` builds the realm again
(`docker compose up -d keycloak-setup`).

**Settings for a real environment**

| Variable | Used by | Meaning |
|---|---|---|
| `IDP_ISSUER_URI` | all | the realm, for example `https://sso.bank.example/realms/account-upgrade` |
| `IDP_CLIENT_ID`, `IDP_CLIENT_SECRET` | gateway, upgrade | that app's own client at the identity provider |

Endpoints are derived from the issuer with Keycloak's paths. For **Okta or Auth0**, set the gateway's
`spring.security.oauth2.client.provider.idp.*` endpoints, its `bank.gateway.idp-logout-uri`, and the upgrade service's
`...provider.idp.token-uri` to that provider's addresses, and have the provider put the person's groups in a `groups`
claim and `api://account-upgrade` in the access token's audience.

## Messaging (Kafka)

Services never write to Kafka directly. They record an event in the same database transaction as the business change
(`OutboxService.publish`), and `OutboxPoller` publishes it with `acks=all` and idempotence. Reading is done by declaring an
`EventSubscription` bean (topic + handler): the consumer template skips events it has already processed, runs the handler
and the "processed" marker in one transaction, acknowledges the Kafka offset only after that commit, retries with backoff,
and parks what still fails on `<topic>.DLT`. Reactive code reaches JPA only through `BlockingJpa`. Every message and HTTP
call carries a `correlationId` (`X-Correlation-Id` on HTTP).

| Property | Meaning |
|---|---|
| `bank.outbox.enabled` / `bank.processed-events.enabled` | switch on the shared OUTBOX_EVENT / PROCESSED_EVENT tables and the code that uses them |
| `bank.outbox.poll-interval`, `batch-size`, `retention` | poller tuning (1s, 100, 7 days) |
| `bank.consumer.max-attempts`, `backoff-initial`, `backoff-max` | retries before the dead-letter topic (4, 0.5s, 10s) |
| `bank.kafka.bootstrap-servers`, `bank.kafka.properties.*` | any Kafka client setting, for example `security.protocol` |
| `bank.kafka.sasl.username` / `password` | this service's own Kafka login |

Local Kafka (`docker compose up -d`) has no login. To practise the secured setup (TLS, one SCRAM login per service,
topic permissions, no auto-created topics):

```bash
./infra/kafka-secure/generate-certs.sh          # once
docker compose --profile secure up -d kafka-secure kafka-secure-init
source infra/kafka-secure/app.env               # points apps at localhost:9094 with the trust store
export BANK_KAFKA_SASL_USERNAME=communication-service BANK_KAFKA_SASL_PASSWORD=communication-service-kafka-local-pw
```

Each service logs in as itself (for example `communication-service`) and may only read and write the topics listed in
`infra/kafka-secure/init-acls.sh`.

## File ingestion (01:00 daily)

`file-ingestion-service` picks up `accounts_yyyyMMdd.xlsx` files from the SFTP `incoming` folder.

1. Downloads the file and computes its SHA-256. Content that was already loaded is never loaded again; a copy under another name goes to `error` as `<name>.duplicate`.
2. Streams the first sheet row by row (Spring Batch, chunks of `bank.ingestion.chunk-size`). Columns are found by header name, in any order: `Account No`, `Customer Id`, `Customer Name`, `Email`, `Current Type` are required; `Balance`, `KYC Status` are optional. Format account numbers as text in Excel.
3. Valid rows go to `ACCOUNT_STAGING`; bad rows go to `INGESTION_ERROR` with every problem listed and the raw row. A bad row never stops the file.
4. Each file is one Spring Batch job instance (`fileId` is its only identifying parameter). Batch saves the reader's position with every chunk, in the same transaction as the rows. Running a failed file again restarts that instance, so it resumes after the last committed chunk without duplicates. An execution a crash left marked as running is marked `FAILED` at the start of the next run (the scheduler lock means nothing else is running), so it can be restarted.
5. When the file is done, `FILE_INGESTION_LOG` becomes `COMPLETED` and a `FileLoaded` event is written to the outbox in the same transaction. The file moves to `processed`.

Failure handling: a temporary failure (database, network) leaves the file in `incoming` and the next run resumes it. A file that can never load (not a workbook, a required column missing) is marked `FAILED` and moved to `error`; send a corrected file.

| Setting | Default | Meaning |
|---|---|---|
| `bank.ingestion.schedule.cron` | `0 0 1 * * *` in `bank.time-zone` | When the load runs; a ShedLock lock keeps it to one instance |
| `bank.ingestion.schedule.enabled` | `true` | `false` turns the scheduler off |
| `bank.ingestion.sftp.*` | host `localhost:2222`, no login | `SFTP_HOST`, `SFTP_PORT`, `SFTP_USER`, `SFTP_PASSWORD`, or `private-key-path`; the `local` profile has the compose login |
| `bank.ingestion.sftp.host-key-fingerprint` | empty | Required outside local development (`SFTP_HOST_KEY`); `trust-any-host-key` is set only by the `local` profile |
| `bank.ingestion.file-pattern` | `accounts_(\d{8})\.xlsx` | The first group must be the date |

The service also streams a loaded file's accounts to the upgrade service: `GET /internal/files/{id}/accounts` returns newline-delimited JSON in id order, read from the database page by page only as fast as the caller consumes it (404 unknown file, 409 not `COMPLETED`).

Try it locally: copy a file into the compose SFTP, then run the service with a short schedule, for example `SPRING_PROFILES_ACTIVE=local BANK_INGESTION_SCHEDULE_CRON="*/10 * * * * *" java -jar file-ingestion-service/target/*.jar`.

## Account upgrade (03:00 daily)

`account-upgrade-service` has two parts.

**Planning** happens when `account.file-loaded` arrives. The service streams that file's accounts from the ingestion service (`bank.upgrade.ingestion-url`) with its own client-credentials token (scope `staging.read`), and saves them in chunks of 500, each in its own short transaction. The next chunk is only requested once the previous one is saved, so the HTTP stream and the ingestion service's database reads go at the speed this service can write. It creates one `UPGRADE_REQUEST` per account for the business day: `PENDING` if eligible, `REJECTED` with the reason and an `account.upgrade-rejected` event if not. Each request keeps the file's correlation id, and the events the nightly run publishes for it carry that id. Delivering the same event twice, a stop half way, or a second file for the same day queues nothing twice. An unknown file goes straight to the dead-letter topic; anything else (ingestion down, file still loading) is retried with backoff.

**The run** (`bank.upgrade.schedule.cron`, 03:00 in `bank.time-zone`, ShedLock `accountUpgrade`) claims `PENDING` requests in batches, calls the core system for each, and saves the result with its `account.upgraded` (or `account.upgrade-failed`) outbox event in one transaction.
- The core call is made outside any database transaction, with an idempotency key `<account>:<businessDate>`. After a crash the request is still `IN_PROGRESS`, the next run queues it again and repeats the call.
- A core-system refusal fails that account at once. A temporary problem is retried `inline-attempts` times, then the account stays `PENDING` for the next run; after `max-attempts` runs it becomes `FAILED`.
- If `abort-after-consecutive-failures` accounts in a row cannot be reached, the run stops so an outage does not use up every account's attempts.

Draft rules (change under `bank.upgrade.rules` once the business confirms them): current type must be in `target-types` (`SAVINGS -> SAVINGS_PLUS`, `CURRENT -> CURRENT_PLUS`), KYC status must be `VERIFIED`, balance at least `min-balance` (0).

The core-system call is `AccountUpgradeGateway`. The default (`bank.upgrade.gateway=simulated`) accepts every upgrade and only logs; add a real adapter bean under another `gateway` value when the core API is known.

## Communication

`communication-service` turns every `account.upgraded` event into a welcome email.

1. **Letter.** It takes the highest active `LETTER_TEMPLATE` for the new account type and fills it with Thymeleaf. Values are HTML-escaped, and the account number shows only its last four digits. The result is saved in `WELCOME_LETTER` as `PENDING`. This runs inside the Kafka consumer's transaction, so a slow mail server never holds up the topic. An upgrade gets one letter even if it is announced twice (`UPGRADE_REQUEST_ID` is unique). An account type without a template goes to the dead-letter topic; add the template and replay the event.
2. **Email.** Every few seconds a sender locks the due `PENDING` letters (`FOR UPDATE SKIP LOCKED`, so several instances never send the same letter), emails each with the letter as the HTML body, and records the result with its event in one transaction: `SENT` and `notification.sent`, or `FAILED` and `notification.failed`. A mail server that is down is retried with growing waits (`retry-delay` 1m, then 2m, 4m, 8m) up to `max-attempts` (5); an address the server rejects fails at once. If the service stops after the server accepted an email but before the commit, that email is sent again: delivery is at least once.
3. **Resend.** `POST /api/notifications/{id}/resend` (ops admin or support) puts a letter back to `PENDING` with fresh attempts and publishes an `audit.user-action` event naming who asked.

Templates are seeded by changeset `com-003-welcome-templates` (`SAVINGS_PLUS`, `CURRENT_PLUS`). To change a letter, insert a new `VERSION_NO` with `ACTIVE = 1`; letters already sent keep their own copy. Locally the emails go to Mailpit (http://localhost:8025). Elsewhere set `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD` and `MAIL_FROM` (STARTTLS is required). A mail outage does not mark the service DOWN in `/actuator/health`; letters wait and are retried.

## Audit

`audit-service` reads every business topic (`account.file-loaded`, `account.upgraded`, `account.upgrade-rejected`, `account.upgrade-failed`, `notification.sent`, `notification.failed`, `audit.user-action`) and writes one row per event to `AUDIT_EVENT`: who (`ACTOR`), what (`EVENT_TYPE` and a short `SUMMARY`), when (`OCCURRED_AT`), which account, and which business action (`CORRELATION_ID`).

The table is insert-only (a trigger blocks UPDATE and DELETE) and kept forever, so it holds no customer names or emails. Summaries are built from codes and counts, for example `Upgraded SAVINGS -> SAVINGS_PLUS for 2026-11-10`, and anything that looks like an email address is replaced with `[email removed]`. The event data is read as JSON, so a field a producer adds later does not break the audit trail. Each event is recorded once, even when delivered again.

`GET /api/audit/events` searches it, newest first (ops admin, auditor, or scope `audit.read`). The filters are all optional and combine:

| Parameter | Example | Gives |
|---|---|---|
| `accountNo` | `AC261110000042` | one account's history |
| `correlationId` | from any event or log line | one business action across services, for example one file from load to emails |
| `eventType`, `actor` | `UpgradeRejected`, `ops.person@bank.example` | one kind of event, or one person's actions |
| `from`, `to` | `2026-11-10T00:00:00Z` | a time window (`to` is exclusive) |
| `page`, `size` | `0`, `50` | paging; `size` is capped at 200 |

Locally, Keycloak's `local-auditor` client gets an `audit.read` token:

```bash
TOKEN=$(curl -s -X POST http://localhost:8180/realms/account-upgrade/protocol/openid-connect/token -d grant_type=client_credentials \
  -d client_id=local-auditor -d client_secret=local-secret -d scope=audit.read | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8085/api/audit/events?eventType=FileLoaded" | python3 -m json.tool
```

## Ops console

http://localhost:8080 (through the gateway, after signing in). It calls the same API as everything else, with the signed-in person's token, so each person sees and can do only what their role allows.

| Page | Shows | Actions |
|---|---|---|
| Today | latest file, the day's upgrade totals, last 24 hours of emails | Run now: load files, run the upgrade (ops admin) |
| Files | every file, then one file's rejected rows as they were in the sheet | |
| Upgrades | search by status, business date, account, file | Retry a FAILED upgrade (ops admin) |
| Emails | search by status, account | Resend (ops admin, support) |
| Audit | the audit trail by account, business action or event type | |

Run now takes the same cluster lock as the schedule, so it answers 409 while that job is already running anywhere. Retry, resend and run now are recorded in the audit trail with the person's name.

**Signing in locally.** With the `local` profile the gateway signs people in with Keycloak. Use one of the test users
from the Security section (password `local-pw`): `olivia.ops` sees and can do everything, `victor.view` only looks,
`sam.support` can also resend emails, `ada.audit` sees files, upgrades and the audit trail. Sign out to switch user.

**Working on the UI.** `cd ops-ui && npm run watch` rebuilds into `ops-ui/target/classes/static` on every save; with the gateway running from IntelliJ, reload the page. `mvn verify` builds it with its own Node (`node.version` in the parent pom) and type-checks it first.

## Performance tests (local only)

Scripts in `perf/` drive one complete run, from file to audit trail, and report how long each stage took.

```bash
perf/run.sh 100000            # generate, upload, run now (load, then upgrade), wait for everything, report
perf/report.sh 20300102       # the timeline of one run again (default: the latest)
perf/reset.sh                 # optional: deletes ALL local data (database rows, Kafka, SFTP, Mailpit), asks first
```

Start the services first with the `local` profile; for realistic numbers give the simulated core system a delay, for
example `BANK_UPGRADE_SIMULATED_LATENCY=50ms` (and optionally `BANK_UPGRADE_SIMULATED_FAILURE_RATE=0.01`). `run.sh`
presses "run now" as Keycloak's `perf-runner` client, so the schedules are not involved. Reports are saved in
`perf/reports/` (git-ignored). Each run gets its own business date from 2030-01-01 on, so runs sit side by side and the
report picks one out; `perf/reset.sh` is only needed to free space or start from zero.

An Excel sheet holds at most 1,048,576 rows, so the largest file is 1,048,575 accounts. Oracle Free stores at most
12 GB of data; a million-account run fits after the volume changes below, but not comfortably before them.
