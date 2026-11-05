package com.bank.upgrade.ingestion.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code bank.ingestion}.
 *
 * @param workDir   where files are downloaded to while they are loaded
 * @param chunkSize rows saved per database transaction (and the unit a restart can lose)
 */
@ConfigurationProperties("bank.ingestion")
public record IngestionProperties(
        Path workDir,
        @DefaultValue("500") int chunkSize,
        @DefaultValue("accounts_(\\d{8})\\.xlsx") String filePattern,
        @DefaultValue Sftp sftp) {

    public IngestionProperties {
        if (workDir == null) {
            workDir = Path.of(System.getProperty("java.io.tmpdir"), "account-ingestion");
        }
    }

    /**
     * @param hostKeyFingerprint expected server key, for example {@code SHA256:abc...}; required unless trustAnyHostKey
     * @param trustAnyHostKey    local development only: accepts whatever key the server presents
     * @param privateKeyPath     key file for public-key login; if empty the password is used
     */
    public record Sftp(
            @DefaultValue("localhost") String host,
            @DefaultValue("22") int port,
            String username,
            String password,
            String privateKeyPath,
            String hostKeyFingerprint,
            @DefaultValue("false") boolean trustAnyHostKey,
            @DefaultValue("incoming") String incomingDir,
            @DefaultValue("processed") String processedDir,
            @DefaultValue("error") String errorDir) {}
}
