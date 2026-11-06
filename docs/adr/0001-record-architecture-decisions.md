# ADR-0001: Record architecture decisions

- **Status:** Accepted
- **Date:** 2026-11-06

## Context

The platform was designed and built quickly, and many decisions were made in conversation. The code shows what the
system does, not why it was built that way or what was rejected. Without a record, a later change (Spring Boot 4, the
move to Google Cloud) risks undoing a decision without knowing its reason.

## Decision

Every significant technical decision is recorded as a short, numbered Architecture Decision Record (ADR) in
`docs/adr/`, in Michael Nygard's format: context, decision, consequences, alternatives. An accepted ADR is never
rewritten: a changed decision gets a new ADR that supersedes the old one, and the old one's status says so.
`docs/hld.md` links to the ADRs instead of repeating their reasoning.

## Consequences

- About ten minutes per decision, written when it is made.
- New people can learn why things are the way they are without asking or guessing.
- Before changing something big, check whether the ADRs' context still holds.
