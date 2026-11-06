package com.bank.upgrade.ingestion.sftp;

import com.bank.upgrade.ingestion.config.IngestionProperties;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.xfer.FileSystemFile;

public class SshjFileSource implements FileSource {

    private final IngestionProperties.Sftp config;

    public SshjFileSource(IngestionProperties.Sftp config) {
        this.config = config;
    }

    /**
     * The SFTP client is not closed here on purpose: it belongs to the returned session, which closes it and the SSH
     * connection in {@link Session#close()}, and callers hold the session in try-with-resources.
     */
    @Override
    @SuppressWarnings("java:S2095")   // Sonar cannot see that the session takes over the SFTP client
    public Session open() throws IOException {
        var ssh = new SSHClient();
        try {
            if (config.hostKeyFingerprint() != null && !config.hostKeyFingerprint().isBlank()) {
                ssh.addHostKeyVerifier(config.hostKeyFingerprint());
            } else if (config.trustAnyHostKey()) {
                ssh.addHostKeyVerifier(new PromiscuousVerifier());
            } else {
                throw new IllegalStateException(
                    "Set bank.ingestion.sftp.host-key-fingerprint (or trust-any-host-key=true for local development)");
            }
            ssh.setConnectTimeout(15_000);
            ssh.setTimeout(60_000);
            ssh.connect(config.host(), config.port());
            if (config.privateKeyPath() != null && !config.privateKeyPath().isBlank()) {
                ssh.authPublickey(config.username(), ssh.loadKeys(config.privateKeyPath()));
            } else {
                ssh.authPassword(config.username(), config.password());
            }
            return new SshjSession(ssh, ssh.newSFTPClient(), config);
        } catch (IOException | RuntimeException e) {
            try {
                ssh.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    private static final class SshjSession implements Session {
        private final SSHClient ssh;
        private final SFTPClient sftp;
        private final IngestionProperties.Sftp config;

        SshjSession(SSHClient ssh, SFTPClient sftp, IngestionProperties.Sftp config) {
            this.ssh = ssh;
            this.sftp = sftp;
            this.config = config;
        }

        @Override
        public List<String> listIncoming() throws IOException {
            return sftp.ls(config.incomingDir()).stream()
                .filter(RemoteResourceInfo::isRegularFile)
                .map(RemoteResourceInfo::getName)
                .sorted()
                .toList();
        }

        @Override
        public void download(String name, Path target) throws IOException {
            sftp.get(config.incomingDir() + "/" + name, new FileSystemFile(target.toFile()));
        }

        @Override
        public void move(String name, Folder folder, String newName) throws IOException {
            String dir = folder == Folder.PROCESSED ? config.processedDir() : config.errorDir();
            String target = dir + "/" + (newName != null ? newName : name);
            // SFTP will not rename onto an existing file: a file name sent again (a corrected file) gets a timestamp
            if (sftp.statExistence(target) != null) {
                target = target + "." + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
            }
            sftp.rename(config.incomingDir() + "/" + name, target);
        }

        @Override
        public void close() throws IOException {
            try {
                sftp.close();
            } finally {
                ssh.close();
            }
        }
    }
}
