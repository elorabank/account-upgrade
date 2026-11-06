package com.bank.upgrade.upgrade.run;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Starts the daily upgrade on EVERY copy at 03:00. All copies derive the same run id from the time
 * ({@code scheduled-2026-11-07T03:00}), so they join one run and share its work instead of each running its own.
 */
public class UpgradeScheduler {

    private final UpgradeRunner runner;
    private final ZoneId zone;

    public UpgradeScheduler(UpgradeRunner runner, ZoneId zone) {
        this.runner = runner;
        this.zone = zone;
    }

    /** 03:00 every day in the bank's time zone ({@code bank.time-zone}). */
    @Scheduled(cron = "${bank.upgrade.schedule.cron:0 0 3 * * *}", zone = "${bank.time-zone}")
    public void runDaily() {
        runner.runIfIdle("scheduled-" + LocalDateTime.now(zone).truncatedTo(ChronoUnit.MINUTES));
    }
}
