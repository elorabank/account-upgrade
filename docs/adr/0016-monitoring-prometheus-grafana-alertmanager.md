# ADR-0016: Monitoring with Prometheus, Grafana and Alertmanager; metrics need a scope

- **Status:** Accepted
- **Date:** 2026-11-06

## Context

With several copies and an overnight pipeline, logs alone do not show throughput, backlogs or a stuck stage.

## Decision

Every service publishes Micrometer metrics at `/actuator/prometheus`, including pipeline metrics (core calls, rate
limit waits, outcomes, lease takeovers, outbox backlog, consumer results, emails, retention). The endpoint requires
the `metrics.read` scope, which only Keycloak's `prometheus` client gets; Prometheus fetches its token itself.
Grafana shows a dashboard kept as a file in the repo and signs people in through Keycloak (ops admins are Grafana
admins, other ops groups viewers). Seven alert rules go to Alertmanager, which emails the ops team.

## Consequences

- Metrics are protected like the API, and dashboards and alerts are versioned with the code.
- On an 8 GB laptop the whole stack plus monitoring is too much for honest performance numbers.

## Alternatives considered

- **An open metrics endpoint on a separate management port, protected by the network:** common in Kubernetes; may be
  the simpler choice in the cloud.
- **A hosted monitoring service:** to decide with the cloud move.
