package com.bank.upgrade.upgrade.run;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;

/** Starts the daily upgrade. The cluster lock makes sure only one instance runs it. */
public class UpgradeScheduler {

    /** Shared with "run now" (ManualRunner), so a manual and a scheduled run never overlap. */
    public static final String LOCK_NAME = "accountUpgrade";
    public static final java.time.Duration LOCK_AT_MOST_FOR = java.time.Duration.ofHours(4);

    private final UpgradeRunner runner;

    public UpgradeScheduler(UpgradeRunner runner) {
        this.runner = runner;
    }

    /** 03:00 every day in the bank's time zone ({@code bank.time-zone}). */
    @Scheduled(cron = "${bank.upgrade.schedule.cron:0 0 3 * * *}", zone = "${bank.time-zone}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT4H", lockAtLeastFor = "PT1M")
    public void runDaily() {
        runner.runOnce();
    }
}
