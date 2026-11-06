package com.bank.upgrade.common.events;

/**
 * Data of {@link Topics#UPGRADE_RUN_REQUESTED}: someone pressed "run now". Every upgrade-service copy that reads it
 * joins the run with the same {@code runId}, so the copies share the work instead of each running its own.
 */
public record UpgradeRunRequested(String runId) {}
