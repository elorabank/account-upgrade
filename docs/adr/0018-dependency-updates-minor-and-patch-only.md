# ADR-0018: Dependency updates: minor and patch only, majors on purpose

- **Status:** Accepted; Dependabot paused since 2026-11-06
- **Date:** 2026-11-06

## Context

Dependabot opened twelve pull requests on its first day, half of them failing: major versions (Spring Boot 4, Vite 8,
React 19, Kafka 4) that are migrations, not updates.

## Decision

Dependabot proposes only minor and patch versions for Maven, npm and Docker images, any version for GitHub actions
(CI runs them itself), React's packages together, and skips Spring Cloud 2025.1 (the release for Spring Boot 4). Major
versions are planned migrations on their own branch. Dependabot is currently paused (pull-request limits set to 0)
to avoid noise; the rules stay for when it is resumed.

## Consequences

- Fewer broken pull requests, and green CI means more.
- While paused, nothing reminds us of updates: Spring Boot 3.5 has had no free fixes since 2026-06-30. Resume
  Dependabot or update by hand, and plan the move to Spring Boot 4.1.
- CI does not start Docker images, so image updates need a local try.
