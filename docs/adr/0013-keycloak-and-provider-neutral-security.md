# ADR-0013: Keycloak locally, security that works with any OIDC provider

- **Status:** Accepted
- **Date:** 2026-11-06

## Context

Okta was the planned identity provider, but an Okta developer account needs a corporate email. A mock token server
was used first, but it cannot show a real sign-in, sign-out or token refresh.

## Decision

Keycloak 26 runs in docker-compose; `infra/keycloak/setup-realm.sh` creates the realm, groups, test users, clients
and scopes. The code depends only on standard OIDC/OAuth2: one issuer setting (`IDP_ISSUER_URI`), the audience
`api://account-upgrade`, groups in a `groups` claim becoming roles (`AU_OPS_ADMIN`, `AU_OPS_VIEWER`, `AU_SUPPORT`,
`AU_AUDITOR`), and scopes (from `scope`, or Okta's `scp`) for service-to-service calls. All access rules are in one
place (`ApiAccessRules`): any `/api/**` call without a rule is refused, and `/internal/**` accepts scopes only.

## Consequences

- Real tokens and flows locally; Okta or Auth0 need only settings (endpoints, a `groups` claim, the audience).
- Keycloak keeps no data locally: the setup script is the source of truth and runs on every start.
- One file shows who may call what, and `AccessMatrixTest` checks every endpoint against every role.

## Alternatives considered

- **Okta developer account:** blocked (corporate email).
- **The mock token server:** kept only for the history; replaced.
