package com.bank.upgrade.upgrade.run;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;

/** Starts the daily upgrade. The cluster lock makes sure only one instance runs it. */
public class UpgradeScheduler {

    private final UpgradeRunner runner;

    public UpgradeScheduler(UpgradeRunner runner) {
        this.runner = runner;
    }

    /** 03:00 every day in the bank's time zone ({@code bank.time-zone}). */
    @Scheduled(cron = "${bank.upgrade.schedule.cron:0 0 3 * * *}", zone = "${bank.time-zone}")
    @SchedulerLock(name = "accountUpgrade", lockAtMostFor = "PT4H", lockAtLeastFor = "PT1M")
    public void runDaily() {
        runner.runOnce();
    }
}
