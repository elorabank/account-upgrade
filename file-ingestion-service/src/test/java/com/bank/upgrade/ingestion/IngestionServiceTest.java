package com.bank.upgrade.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bank.upgrade.common.events.FileLoaded;
import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.messaging.OutboxService;
import com.bank.upgrade.ingestion.IngestionService.Outcome;
import com.bank.upgrade.ingestion.config.IngestionProperties;
import com.bank.upgrade.ingestion.domain.AccountStagingRepository;
import com.bank.upgrade.ingestion.domain.FileIngestionLog;
import com.bank.upgrade.ingestion.domain.FileIngestionLogRepository;
import com.bank.upgrade.ingestion.domain.IngestionErrorRepository;
import com.bank.upgrade.ingestion.excel.InvalidFileException;
import com.bank.upgrade.ingestion.job.IngestionJobConfig;
import com.bank.upgrade.ingestion.sftp.FileSource;
import com.bank.upgrade.test.NoOpTransactionManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;

/**
 * One load run with the SFTP server, the database and Spring Batch mocked: which files are loaded, skipped, failed
 * or resumed, where each file is moved, and that a completed file announces itself with one FileLoaded event.
 */
class IngestionServiceTest {

    @TempDir Path workDir;

    /** The SFTP incoming folder: name -> content; moves are recorded as "name -> FOLDER[/new name]". */
    final Map<String, byte[]> incoming = new LinkedHashMap<>();
    final List<String> moves = new ArrayList<>();
    final List<String> brokenDownloads = new ArrayList<>();

    /** FILE_INGESTION_LOG in memory. */
    final Map<Long, FileIngestionLog> logTable = new LinkedHashMap<>();
    final AtomicLong ids = new AtomicLong();

    final FileIngestionLogRepository logs = mock(FileIngestionLogRepository.class);
    final AccountStagingRepository staging = mock(AccountStagingRepository.class);
    final IngestionErrorRepository errors = mock(IngestionErrorRepository.class);
    final JobLauncher launcher = mock(JobLauncher.class);
    final JobExplorer explorer = mock(JobExplorer.class);
    final JobRepository jobRepository = mock(JobRepository.class);
    final OutboxService outbox = mock(OutboxService.class);
    final Clock clock = Clock.fixed(Instant.parse("2026-11-10T19:30:00Z"), ZoneOffset.UTC);

    IngestionService service;

    @BeforeEach
    void fakes() throws Exception {
        FileSource source = () -> new FileSource.Session() {
            @Override
            public List<String> listIncoming() {
                return List.copyOf(incoming.keySet());
            }

            @Override
            public void download(String name, Path target) throws IOException {
                if (brokenDownloads.contains(name)) {
                    throw new IOException("connection reset");
                }
                Files.write(target, incoming.get(name));
            }

            @Override
            public void move(String name, FileSource.Folder folder, String newName) {
                incoming.remove(name);
                moves.add(name + " -> " + folder + (newName == null ? "" : "/" + newName));
            }

            @Override
            public void close() {}
        };
        when(logs.save(any())).thenAnswer(inv -> {
            FileIngestionLog entry = inv.getArgument(0);
            if (entry.getId() == null) {
                entry.setId(ids.incrementAndGet());
            }
            logTable.put(entry.getId(), entry);
            return entry;
        });
        when(logs.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(logTable.get(inv.<Long>getArgument(0))));
        when(logs.findByChecksum(anyString())).thenAnswer(inv -> logTable.values().stream()
            .filter(e -> inv.getArgument(0).equals(e.getChecksum())).findFirst());
        when(staging.countByFileId(anyLong())).thenReturn(9L);
        when(errors.countByFileId(anyLong())).thenReturn(1L);
        when(explorer.findRunningJobExecutions(IngestionJobConfig.JOB_NAME)).thenReturn(java.util.Set.of());

        var properties = new IngestionProperties(workDir, 500, "accounts_(\\d{8})\\.xlsx", null);
        service = new IngestionService(source, properties, logs, staging, errors, launcher, explorer, jobRepository,
            mock(Job.class), outbox, new NoOpTransactionManager(), clock);
    }

    private static JobExecution execution(BatchStatus status, Throwable... failures) {
        var execution = new JobExecution(new JobInstance(1L, IngestionJobConfig.JOB_NAME), 1L, new JobParameters());
        execution.setStatus(status);
        for (Throwable failure : failures) {
            execution.addFailureException(failure);
        }
        return execution;
    }

    private void file(String name, String content) {
        incoming.put(name, content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aLoadedFileIsCompletedAnnouncedOnceAndMovedToProcessed() throws Exception {
        file("accounts_20261110.xlsx", "ten rows");
        when(launcher.run(any(), any())).thenReturn(execution(BatchStatus.COMPLETED));

        var results = service.runOnce();

        assertThat(results).extracting(IngestionService.FileResult::outcome).containsExactly(Outcome.LOADED);
        var entry = logTable.get(1L);
        assertThat(entry.getStatus()).isEqualTo("COMPLETED");
        assertThat(entry.getBusinessDate()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(entry.getLoadedRows()).isEqualTo(9);
        assertThat(entry.getRejectedRows()).isEqualTo(1);
        assertThat(entry.getTotalRows()).isEqualTo(10);
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(outbox).publish(eq(Topics.FILE_LOADED), eq("1"), eq("FileLoaded"), eq("1"), event.capture(), any());
        assertThat(event.getValue()).isEqualTo(new FileLoaded(1, "accounts_20261110.xlsx", LocalDate.of(2026, 11, 10), 10, 9, 1));
        assertThat(moves).containsExactly("accounts_20261110.xlsx -> PROCESSED");
        try (var leftovers = Files.list(workDir)) {
            assertThat(leftovers).as("the downloaded copy is deleted").isEmpty();
        }
    }

    @Test
    void theSameContentUnderANewNameIsNotLoadedTwice() throws Exception {
        file("accounts_20261110.xlsx", "same bytes");
        when(launcher.run(any(), any())).thenReturn(execution(BatchStatus.COMPLETED));
        service.runOnce();

        file("accounts_20261111.xlsx", "same bytes");
        var results = service.runOnce();

        assertThat(results).extracting(IngestionService.FileResult::outcome).containsExactly(Outcome.DUPLICATE_CONTENT);
        assertThat(moves).contains("accounts_20261111.xlsx -> ERROR/accounts_20261111.xlsx.duplicate");
        verify(outbox).publish(eq(Topics.FILE_LOADED), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void aFileLoadedEarlierButNotMovedIsOnlyMovedNow() throws Exception {
        file("accounts_20261110.xlsx", "bytes");
        when(launcher.run(any(), any())).thenReturn(execution(BatchStatus.COMPLETED));
        service.runOnce();
        file("accounts_20261110.xlsx", "bytes");   // the move had failed, so it is still there

        var results = service.runOnce();

        assertThat(results).extracting(IngestionService.FileResult::outcome).containsExactly(Outcome.ALREADY_LOADED);
        assertThat(moves).containsExactly("accounts_20261110.xlsx -> PROCESSED", "accounts_20261110.xlsx -> PROCESSED");
    }

    @Test
    void aFileThatCannotBeReadGoesToTheErrorFolderWithTheReason() throws Exception {
        file("accounts_20261110.xlsx", "no account column");
        when(launcher.run(any(), any())).thenReturn(execution(BatchStatus.FAILED,
            new IllegalStateException("step failed", new InvalidFileException("Missing column: AccountNo"))));

        var results = service.runOnce();

        assertThat(results).extracting(IngestionService.FileResult::outcome).containsExactly(Outcome.REJECTED_FILE);
        assertThat(logTable.get(1L).getStatus()).isEqualTo("FAILED");
        assertThat(logTable.get(1L).getErrorMessage()).isEqualTo("Missing column: AccountNo");
        assertThat(moves).containsExactly("accounts_20261110.xlsx -> ERROR");
        verify(outbox, never()).publish(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void aLoadThatFailsHalfWayStaysAndIsResumedByTheNextRun() throws Exception {
        file("accounts_20261110.xlsx", "big file");
        when(launcher.run(any(), any()))
            .thenReturn(execution(BatchStatus.FAILED, new IllegalStateException("database went away")))
            .thenReturn(execution(BatchStatus.COMPLETED));

        var first = service.runOnce();
        assertThat(first).extracting(IngestionService.FileResult::outcome).containsExactly(Outcome.FAILED_WILL_RETRY);
        assertThat(moves).isEmpty();
        assertThat(logTable.get(1L).getStatus()).isEqualTo("FAILED");

        var second = service.runOnce();

        assertThat(second).extracting(IngestionService.FileResult::outcome).containsExactly(Outcome.LOADED);
        assertThat(logTable).as("the same file entry, so Spring Batch restarts the same job instance").hasSize(1);
        ArgumentCaptor<JobParameters> params = ArgumentCaptor.forClass(JobParameters.class);
        verify(launcher, org.mockito.Mockito.times(2)).run(any(), params.capture());
        assertThat(params.getAllValues()).extracting(p -> p.getLong("fileId")).containsExactly(1L, 1L);
    }

    @Test
    void aJobFinishedBeforeACrashOnlyNeedsItsBookkeeping() throws Exception {
        file("accounts_20261110.xlsx", "bytes");
        when(launcher.run(any(), any())).thenThrow(new JobInstanceAlreadyCompleteException("done already"));

        var results = service.runOnce();

        assertThat(results).extracting(IngestionService.FileResult::outcome).containsExactly(Outcome.LOADED);
        assertThat(logTable.get(1L).getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void aJobACrashLeftMarkedRunningIsMarkedFailedSoItCanBeRestarted() throws Exception {
        var stale = execution(BatchStatus.STARTED);
        var step = stale.createStepExecution("load");
        step.setStatus(BatchStatus.STARTED);
        when(explorer.findRunningJobExecutions(IngestionJobConfig.JOB_NAME)).thenReturn(java.util.Set.of(stale));

        service.runOnce();

        assertThat(stale.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(step.getStatus()).isEqualTo(BatchStatus.FAILED);
        verify(jobRepository).update(step);
        verify(jobRepository).update(stale);
    }

    @Test
    void filesWithOtherNamesAreLeftAloneAndABadDateIsRejected() throws Exception {
        file("notes.txt", "hello");
        file("accounts_20261340.xlsx", "month 13");

        var results = service.runOnce();

        assertThat(results).extracting(IngestionService.FileResult::outcome).containsExactly(Outcome.IGNORED, Outcome.REJECTED_FILE);
        assertThat(moves).containsExactly("accounts_20261340.xlsx -> ERROR/accounts_20261340.xlsx.bad-date");
        verify(launcher, never()).run(any(), any());
    }

    @Test
    void oneFileThatCannotBeDownloadedDoesNotStopTheOthers() throws Exception {
        file("accounts_20261110.xlsx", "first");
        file("accounts_20261111.xlsx", "second");
        brokenDownloads.add("accounts_20261110.xlsx");
        when(launcher.run(any(), any())).thenReturn(execution(BatchStatus.COMPLETED));

        var results = service.runOnce();

        assertThat(results).extracting(IngestionService.FileResult::outcome).containsExactly(Outcome.FAILED_WILL_RETRY, Outcome.LOADED);
        assertThat(moves).containsExactly("accounts_20261111.xlsx -> PROCESSED");
    }
}
