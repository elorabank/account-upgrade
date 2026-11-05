package com.bank.upgrade.common.security;

/** Identity-provider group names (sent in the {@code groups} claim) and API scopes, as Spring authorities. */
public final class Roles {

    public static final String OPS_ADMIN = "ROLE_AU_OPS_ADMIN";
    public static final String OPS_VIEWER = "ROLE_AU_OPS_VIEWER";
    public static final String SUPPORT = "ROLE_AU_SUPPORT";
    public static final String AUDITOR = "ROLE_AU_AUDITOR";

    public static final String SCOPE_UPGRADE_READ = "SCOPE_upgrade.read";
    public static final String SCOPE_UPGRADE_ADMIN = "SCOPE_upgrade.admin";
    public static final String SCOPE_AUDIT_READ = "SCOPE_audit.read";
    public static final String SCOPE_NOTIFICATION_RESEND = "SCOPE_notification.resend";
    /** Service-to-service only: the upgrade service reading a loaded file's accounts from the ingestion service. */
    public static final String SCOPE_STAGING_READ = "SCOPE_staging.read";

    private Roles() {}
}
