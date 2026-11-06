# ADR-0010: Several upgrade copies share one run through leases

- **Status:** Accepted (supersedes ADR-0009 for the upgrade run)
- **Date:** 2026-11-06

## Context

Under ShedLock (ADR-0009) one copy of the upgrade service did the whole run while the others waited, so more copies
did not make the run faster, and a copy that died held the lock until it expired.

## Decision

Every copy joins the run and claims batches with `FOR UPDATE SKIP LOCKED`, so two copies never get the same rows. A
claim is a lease: `CLAIMED_BY` and `CLAIMED_UNTIL` (2 minutes), renewed every third of that while the copy works. A
copy that dies stops renewing; once its leases run out, any copy takes those requests over and repeats the core call
(same idempotency key, ADR-0008). A copy records an outcome only for a request it still holds. A copy with nothing
left to claim keeps looking while others hold requests, so the run ends only when nothing is in progress. A copy's
name is `UPGRADE_INSTANCE_ID` plus a random part, so a restarted process never renews its predecessor's leases.

## Consequences

- Copies split the work; a dead copy's share is finished by the others (shown with `kill -9` on 20,000 accounts).
- A run can wait up to one lease length for a dead copy's requests.
- Needs two more decisions: starting every copy on "run now" (ADR-0011) and one rate limit for all copies (ADR-0012).
- The claim query's behaviour is Oracle's; tests mock it (ADR-0017), so it is checked in local end-to-end runs.

## Alternatives considered

- **Keep the single lock:** no scaling.
- **Split accounts between copies by hash:** reassigning work when copies come and go is hard.
