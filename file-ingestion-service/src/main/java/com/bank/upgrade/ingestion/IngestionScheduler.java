package com.bank.upgrade.ingestion;

import java.io.IOException;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;

/** Starts the daily load. The cluster lock makes sure only one instance runs it. */
public class IngestionScheduler {

    /** Shared with "run now" (ManualRunner), so a manual and a scheduled load never overlap. */
    public static final String LOCK_NAME = "fileIngestion";
    public static final java.time.Duration LOCK_AT_MOST_FOR = java.time.Duration.ofHours(2);

    private final IngestionService service;

    public IngestionScheduler(IngestionService service) {
        this.service = service;
    }

    /** 01:00 every day in the bank's time zone ({@code bank.time-zone}). */
    @Scheduled(cron = "${bank.ingestion.schedule.cron:0 0 1 * * *}", zone = "${bank.time-zone}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void runDaily() throws IOException {
        service.runOnce();
    }
}
