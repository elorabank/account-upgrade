# ADR-0020: Letters keep their template version and values, not the HTML

- **Status:** Accepted
- **Date:** 2026-11-06

## Context

Every welcome letter stored its finished HTML: about 1.8 KB, nearly all of it the same template text, for every
upgraded account. At a million upgrades a day that is close to 2 GB a day of repeated text, kept 400 days. The
details that differ between two letters are a name, an account number, two account types and a date.

## Decision

A letter (`WELCOME_LETTER`) keeps the template version it was written from (`TEMPLATE_ID`), the filled-in subject
(for the console's list) and the values that fill the body: customer name, old and new type, business date (about
35 bytes). The email's HTML is built when it is sent (`LetterContent`, Thymeleaf, values escaped, account masked).

A template version never changes once written: a database trigger refuses any change to its subject, body, type,
code or version number (turning `ACTIVE` on and off is still allowed). A new wording is a new `VERSION_NO`. So a
letter built again, for a retry or a resend, says exactly what the first one said. The sender reads each version once
and keeps it. The first letter from a version is built in full when it is written, so a broken template is caught
(and the event parked) before letters point at it. Letters written before this change keep their `HTML_BODY` and are
sent from it; a check constraint makes sure each letter has one or the other.

## Consequences

- About fifty times less letter data per upgrade; the HTML column stays only for older letters and leaves with them.
- Sending does a little more work (one template fill per email, a few microseconds); the template is never re-read.
- A corrected template applies to new letters only: letters already written keep their version, and getting the
  corrected wording to them means writing them again from the new version.
- What a customer received is no longer a stored copy but a reproducible one: the version plus the values. The
  database trigger is what makes that reliable, so it must stay.

## Alternatives considered

- **Keep the HTML, compress it:** smaller, but still a copy per letter, and the database does not compress LOBs
  without the Advanced Compression option.
- **Store nothing and read the values from the upgrade service when sending:** a call back across services for every
  email, and the letter could change if the upgrade data changed.
