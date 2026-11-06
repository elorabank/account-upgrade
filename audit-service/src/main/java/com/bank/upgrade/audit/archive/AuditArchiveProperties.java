package com.bank.upgrade.audit.archive;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code bank.audit.archive.*}: moving old months of the audit trail from the database to archive files.
 *
 * @param enabled    off with {@code false}: the whole trail stays in the database
 * @param cron       when to look for months to archive, in the bank's time zone (default 05:00, before the clean-up)
 * @param keep       how long a month stays in the database after it ended (default 400 days, about 13 months)
 * @param dir        the folder archive files are written to: a mounted volume in real environments
 * @param staleAfter a month still marked WRITING after this long was left by a copy that stopped; another takes it over
 */
@ConfigurationProperties("bank.audit.archive")
public record AuditArchiveProperties(Boolean enabled, String cron, Duration keep, String dir, Duration staleAfter) {

    public AuditArchiveProperties {
        enabled = enabled == null || enabled;
        cron = cron == null ? "0 0 5 * * *" : cron;
        keep = keep == null ? Duration.ofDays(400) : keep;
        dir = dir == null || dir.isBlank() ? "audit-archive" : dir;
        staleAfter = staleAfter == null ? Duration.ofHours(1) : staleAfter;
    }
}
