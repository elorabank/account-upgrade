package com.bank.upgrade.common.events;

/** Kafka topic names shared by all services. */
public final class Topics {
    public static final String FILE_LOADED = "account.file-loaded";
    public static final String ACCOUNT_UPGRADED = "account.upgraded";
    public static final String UPGRADE_FAILED = "account.upgrade-failed";
    public static final String UPGRADE_REJECTED = "account.upgrade-rejected";
    public static final String NOTIFICATION_SENT = "notification.sent";
    public static final String NOTIFICATION_FAILED = "notification.failed";
    public static final String USER_ACTION = "audit.user-action";
    /** "Run the upgrade now": read by every copy of the upgrade service, each in its own consumer group. */
    public static final String UPGRADE_RUN_REQUESTED = "upgrade.run-requested";

    /** Where a message that could not be processed is parked. */
    public static String dlt(String topic) {
        return topic + ".DLT";
    }

    private Topics() {}
}
