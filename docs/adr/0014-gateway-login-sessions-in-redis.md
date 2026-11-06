# ADR-0014: Sign-in at the gateway, sessions in Redis, tokens relayed

- **Status:** Accepted
- **Date:** 2026-11-06

## Context

The ops console is a single-page app. Tokens in the browser can be stolen by injected script. The services should
stay stateless, and there may be several gateway copies.

## Decision

The gateway (Spring Cloud Gateway on WebFlux) signs people in with authorization code + PKCE, keeps the session in
Redis through Spring Session (30 minutes idle), and passes the person's access token to the services
(`TokenRelay`), removing cookies. The browser holds only a session cookie and a CSRF token. Signing out also ends
the identity provider's session. Other systems can send `Authorization: Bearer` straight to the gateway.

## Consequences

- Tokens never reach the browser; any gateway copy serves any session; a restart signs nobody out.
- Redis holds access tokens: it must be private, password protected, and use TLS in the cloud.
- The gateway does not start without Redis.

## Alternatives considered

- **Tokens in the browser (public PKCE client):** simpler, but exposed to cross-site scripting.
- **Sessions in gateway memory with sticky sessions:** lost on restart.
