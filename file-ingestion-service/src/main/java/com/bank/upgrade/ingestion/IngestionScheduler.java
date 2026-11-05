package com.bank.upgrade.ingestion;

import java.io.IOException;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;

/** Starts the daily load. The cluster lock makes sure only one instance runs it. */
public class IngestionScheduler {

    private final IngestionService service;

    public IngestionScheduler(IngestionService service) {
        this.service = service;
    }

    /** 01:00 every day in the bank's time zone ({@code bank.time-zone}). */
    @Scheduled(cron = "${bank.ingestion.schedule.cron:0 0 1 * * *}", zone = "${bank.time-zone}")
    @SchedulerLock(name = "fileIngestion", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void runDaily() throws IOException {
        service.runOnce();
    }
}
