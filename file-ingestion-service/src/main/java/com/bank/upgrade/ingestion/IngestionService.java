package com.bank.upgrade.ingestion;

import com.bank.upgrade.common.correlation.CorrelationId;
import com.bank.upgrade.common.events.FileLoaded;
import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.messaging.OutboxService;
import com.bank.upgrade.ingestion.config.IngestionProperties;
import com.bank.upgrade.ingestion.domain.AccountStagingRepository;
import com.bank.upgrade.ingestion.domain.FileIngestionLog;
import com.bank.upgrade.ingestion.domain.FileIngestionLogRepository;
import com.bank.upgrade.ingestion.domain.IngestionErrorRepository;
import com.bank.upgrade.ingestion.excel.InvalidFileException;
import com.bank.upgrade.ingestion.job.IngestionJobConfig;
import com.bank.upgrade.ingestion.sftp.FileSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The daily run: pick up each file from SFTP, skip content that was already loaded (SHA-256), load it with the
 * batch job, record the result, announce it with a {@code FileLoaded} event and move the file out of the incoming folder.
 * <p>
 * A file that fails for a temporary reason (database, network) stays in the incoming folder; the next run restarts
 * its Spring Batch job instance, which resumes after the last committed chunk. A file that can never load (not a
 * workbook, a required column missing) goes to the error folder.
 */
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    public enum Outcome { LOADED, ALREADY_LOADED, DUPLICATE_CONTENT, REJECTED_FILE, FAILED_WILL_RETRY, IGNORED }

    public record FileResult(String fileName, Outcome outcome, String detail) {}

    private final FileSource source;
    private final IngestionProperties properties;
    private final FileIngestionLogRepository logs;
    private final AccountStagingRepository staging;
    private final IngestionErrorRepository errors;
    private final JobLauncher jobLauncher;
    private final JobExplorer jobExplorer;
    private final JobRepository jobRepository;
    private final Job job;
    private final OutboxService outbox;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Pattern filePattern;

    public IngestionService(
            FileSource source,
            IngestionProperties properties,
            FileIngestionLogRepository logs,
            AccountStagingRepository staging,
            IngestionErrorRepository errors,
            JobLauncher jobLauncher,
            JobExplorer jobExplorer,
            JobRepository jobRepository,
            Job job,
            OutboxService outbox,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.source = source;
        this.properties = properties;
        this.logs = logs;
        this.staging = staging;
        this.errors = errors;
        this.jobLauncher = jobLauncher;
        this.jobExplorer = jobExplorer;
        this.jobRepository = jobRepository;
        this.job = job;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.filePattern = Pattern.compile(properties.filePattern());
        if (filePattern.matcher("").groupCount() < 1) {
            throw new IllegalStateException("bank.ingestion.file-pattern needs a group around the yyyyMMdd date");
        }
    }

    /** Handles every file waiting in the incoming folder. A problem with one file does not stop the others. */
    public List<FileResult> runOnce() throws IOException {
        List<FileResult> results = new ArrayList<>();
        try (var scope = CorrelationId.open(CorrelationId.newId()); var session = source.open()) {
            abandonInterruptedExecutions();
            List<String> names = session.listIncoming();
            log.info("Ingestion run started, {} file(s) in the incoming folder", names.size());
            for (String name : names) {
                try {
                    results.add(processFile(session, name));
                } catch (Exception e) {
                    log.error("File {} could not be processed, it stays in the incoming folder", name, e);
                    results.add(new FileResult(name, Outcome.FAILED_WILL_RETRY, e.toString()));
                }
            }
        }
        return results;
    }

    private FileResult processFile(FileSource.Session session, String name) throws IOException {
        Matcher matcher = filePattern.matcher(name);
        if (!matcher.matches()) {
            log.warn("Ignoring {}: the name does not match {}", name, properties.filePattern());
            return new FileResult(name, Outcome.IGNORED, "name does not match the file pattern");
        }
        LocalDate businessDate;
        try {
            businessDate = LocalDate.parse(matcher.group(1), DateTimeFormatter.BASIC_ISO_DATE);
        } catch (DateTimeParseException e) {
            session.move(name, FileSource.Folder.ERROR, name + ".bad-date");
            return new FileResult(name, Outcome.REJECTED_FILE, "the date in the name is not valid");
        }

        Files.createDirectories(properties.workDir());
        Path local = Files.createTempFile(properties.workDir(), "ingest-", ".xlsx");
        try {
            session.download(name, local);
            String checksum = Checksums.sha256(local);

            var existing = logs.findByChecksum(checksum);
            if (existing.isPresent() && "COMPLETED".equals(existing.get().getStatus())) {
                if (name.equals(existing.get().getFileName())) {
                    // We loaded this file earlier but could not move it away; finish the move now
                    session.move(name, FileSource.Folder.PROCESSED, null);
                    return new FileResult(name, Outcome.ALREADY_LOADED, "moved to processed");
                }
                log.warn("{} has the same content as {} which is already loaded, moving it to the error folder",
                    name, existing.get().getFileName());
                session.move(name, FileSource.Folder.ERROR, name + ".duplicate");
                return new FileResult(name, Outcome.DUPLICATE_CONTENT, "same content as " + existing.get().getFileName());
            }

            long fileId = existing.isPresent() ? restart(existing.get()) : start(name, checksum, businessDate);

            JobExecution execution;
            try {
                execution = jobLauncher.run(job, new JobParametersBuilder()
                    .addLong("fileId", fileId)
                    .addString("path", local.toString(), false)
                    .toJobParameters());
            } catch (JobInstanceAlreadyCompleteException e) {
                // The job finished but the service stopped before recording it: only the bookkeeping is left
                execution = null;
            }

            if (execution == null || execution.getStatus() == BatchStatus.COMPLETED) {
                complete(fileId);
                session.move(name, FileSource.Folder.PROCESSED, null);
                return new FileResult(name, Outcome.LOADED, "file id " + fileId);
            }

            Throwable cause = execution.getAllFailureExceptions().stream()
                .map(IngestionService::findInvalidFile)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
            String message = cause != null ? cause.getMessage() : describe(execution);
            fail(fileId, message);
            if (cause != null) {
                log.error("File {} is not loadable and goes to the error folder: {}", name, message);
                session.move(name, FileSource.Folder.ERROR, null);
                return new FileResult(name, Outcome.REJECTED_FILE, message);
            }
            log.error("Loading {} failed, the next run resumes it: {}", name, message);
            return new FileResult(name, Outcome.FAILED_WILL_RETRY, message);
        } catch (org.springframework.batch.core.JobExecutionException e) {
            throw new IOException("Batch job could not be started for " + name, e);
        } finally {
            Files.deleteIfExists(local);
        }
    }

    private long start(String name, String checksum, LocalDate businessDate) {
        return tx.execute(status -> {
            var entry = new FileIngestionLog();
            entry.setFileName(name);
            entry.setChecksum(checksum);
            entry.setBusinessDate(businessDate);
            entry.setStartedAt(clock.instant());
            return logs.save(entry).getId();
        });
    }

    /**
     * A crash leaves the job execution marked as running, and Spring Batch refuses to start that instance again.
     * The scheduler lock means no other run is active, so every running execution is left over: mark it FAILED,
     * which makes it restartable from its last committed chunk.
     */
    private void abandonInterruptedExecutions() {
        for (JobExecution stale : jobExplorer.findRunningJobExecutions(IngestionJobConfig.JOB_NAME)) {
            log.warn("Job execution {} was interrupted, marking it FAILED so it can be restarted", stale.getId());
            var now = java.time.LocalDateTime.now();   // Spring Batch keeps local times
            for (StepExecution step : stale.getStepExecutions()) {
                if (step.getStatus().isRunning()) {
                    step.setStatus(BatchStatus.FAILED);
                    step.setExitStatus(ExitStatus.FAILED.addExitDescription("interrupted"));
                    step.setEndTime(now);
                    jobRepository.update(step);
                }
            }
            stale.setStatus(BatchStatus.FAILED);
            stale.setExitStatus(ExitStatus.FAILED.addExitDescription("interrupted"));
            stale.setEndTime(now);
            jobRepository.update(stale);
        }
    }

    private long restart(FileIngestionLog existing) {
        log.info("Resuming {}: Spring Batch continues after the last committed chunk", existing.getFileName());
        return tx.execute(status -> {
            var entry = logs.findById(existing.getId()).orElseThrow();
            entry.setStatus("RUNNING");
            entry.setErrorMessage(null);
            entry.setCompletedAt(null);
            return entry.getId();
        });
    }

    /** Final counts, status and the FileLoaded event in one transaction, so the event exists exactly when the file is COMPLETED. */
    private void complete(long fileId) {
        tx.executeWithoutResult(status -> {
            var entry = logs.findById(fileId).orElseThrow();
            int loaded = Math.toIntExact(staging.countByFileId(fileId));
            int rejected = Math.toIntExact(errors.countByFileId(fileId));
            entry.setLoadedRows(loaded);
            entry.setRejectedRows(rejected);
            entry.setTotalRows(loaded + rejected);
            entry.setStatus("COMPLETED");
            entry.setErrorMessage(null);
            entry.setCompletedAt(clock.instant());
            outbox.publish(
                Topics.FILE_LOADED,
                String.valueOf(fileId),
                "FileLoaded",
                String.valueOf(fileId),
                new FileLoaded(fileId, entry.getFileName(), entry.getBusinessDate(), loaded + rejected, loaded, rejected),
                null);
            log.info("File {} loaded: {} rows saved, {} rejected", entry.getFileName(), loaded, rejected);
        });
    }

    private void fail(long fileId, String message) {
        tx.executeWithoutResult(status -> {
            var entry = logs.findById(fileId).orElseThrow();
            entry.setStatus("FAILED");
            entry.setErrorMessage(message == null ? "unknown error" : message.length() > 2000 ? message.substring(0, 2000) : message);
            entry.setCompletedAt(clock.instant());
        });
    }

    private static Throwable findInvalidFile(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof InvalidFileException) {
                return c;
            }
        }
        return null;
    }

    private static String describe(JobExecution execution) {
        return execution.getAllFailureExceptions().stream()
            .map(Throwable::toString)
            .findFirst()
            .orElse("job ended with status " + execution.getStatus());
    }
}
