package com.bank.upgrade.ingestion;

import static com.bank.upgrade.ingestion.XlsxBuilder.HEADER;
import static com.bank.upgrade.ingestion.XlsxBuilder.row;
import static org.assertj.core.api.Assertions.assertThat;

import com.bank.upgrade.common.persistence.outbox.OutboxEvent;
import com.bank.upgrade.common.persistence.outbox.OutboxEventRepository;
import com.bank.upgrade.ingestion.IngestionService.Outcome;
import com.bank.upgrade.ingestion.domain.*;
import com.bank.upgrade.ingestion.job.IngestionJobConfig;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import com.bank.upgrade.test.OracleTestConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.xfer.InMemorySourceFile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** The whole nightly load against a real SFTP server and Oracle: download, de-duplicate, load, event, move. */
@Import(OracleTestConfig.class)
@SpringBootTest(properties = {
    "bank.ingestion.schedule.enabled=false",
    "bank.ingestion.chunk-size=2",
    "bank.ingestion.sftp.username=bank",
    "bank.ingestion.sftp.password=bank_local_pw",
    "bank.ingestion.sftp.trust-any-host-key=true"
})
class IngestionFlowTest {

    static final GenericContainer<?> SFTP = new GenericContainer<>("atmoz/sftp:latest")
        .withCommand("bank:bank_local_pw:1001:100:incoming,processed,error")
        .withExposedPorts(22)
        .waitingFor(Wait.forLogMessage(".*Server listening on.*", 1));

    static {
        SFTP.start();
    }

    @DynamicPropertySource
    static void sftp(DynamicPropertyRegistry registry) {
        registry.add("bank.ingestion.sftp.host", SFTP::getHost);
        registry.add("bank.ingestion.sftp.port", () -> SFTP.getMappedPort(22));
    }

    @Autowired IngestionService service;
    @Autowired FileIngestionLogRepository logs;
    @Autowired AccountStagingRepository staging;
    @Autowired IngestionErrorRepository errors;
    @Autowired OutboxEventRepository outbox;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired JobExplorer jobExplorer;
    @Autowired JobRepository jobRepository;

    // --- SFTP helpers -------------------------------------------------------------------------------

    interface SftpWork { void run(SFTPClient sftp) throws IOException; }

    static void withSftp(SftpWork work) throws IOException {
        try (var ssh = new SSHClient()) {
            ssh.addHostKeyVerifier(new PromiscuousVerifier());
            ssh.connect(SFTP.getHost(), SFTP.getMappedPort(22));
            ssh.authPassword("bank", "bank_local_pw");
            try (var sftp = ssh.newSFTPClient()) {
                work.run(sftp);
            }
        }
    }

    static void upload(String name, byte[] content) throws IOException {
        withSftp(sftp -> sftp.put(new InMemorySourceFile() {
            @Override public String getName() { return name; }
            @Override public long getLength() { return content.length; }
            @Override public InputStream getInputStream() { return new ByteArrayInputStream(content); }
        }, "incoming/" + name));
    }

    static Set<String> names(String dir) throws IOException {
        var found = new ArrayList<String>();
        withSftp(sftp -> sftp.ls(dir).forEach(f -> found.add(f.getName())));
        return Set.copyOf(found);
    }

    static byte[] file(String prefix, int rows, int badRows) {
        var data = new ArrayList<List<Object>>();
        for (int i = 1; i <= rows; i++) {
            data.add(row(prefix + i, "C" + i, "Customer " + i, "c" + i + "@example.com", "savings", 100 * i, "VERIFIED"));
        }
        for (int i = 1; i <= badRows; i++) {
            data.add(row(prefix + "BAD" + i, "C", "Bad " + i, "no-at-sign", "savings", 1, null));
        }
        return XlsxBuilder.workbook(HEADER, data);
    }

    // --- tests --------------------------------------------------------------------------------------

    @Test
    void loadsAFileRejectsBadRowsPublishesTheEventAndMovesTheFile() throws Exception {
        upload("accounts_20261102.xlsx", file("HP", 5, 2));

        var results = service.runOnce();

        assertThat(results).singleElement().satisfies(r -> {
            assertThat(r.fileName()).isEqualTo("accounts_20261102.xlsx");
            assertThat(r.outcome()).isEqualTo(Outcome.LOADED);
        });
        var entry = logs.findAll().stream().filter(l -> "accounts_20261102.xlsx".equals(l.getFileName())).findFirst().orElseThrow();
        assertThat(entry.getStatus()).isEqualTo("COMPLETED");
        assertThat(entry.getBusinessDate()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(entry.getLoadedRows()).isEqualTo(5);
        assertThat(entry.getRejectedRows()).isEqualTo(2);
        assertThat(entry.getTotalRows()).isEqualTo(7);
        assertThat(staging.countByFileId(entry.getId())).isEqualTo(5);
        assertThat(errors.countByFileId(entry.getId())).isEqualTo(2);

        OutboxEvent event = outbox.findAll().stream()
            .filter(e -> String.valueOf(entry.getId()).equals(e.getAggregateId())).findFirst().orElseThrow();
        assertThat(event.getTopic()).isEqualTo("account.file-loaded");
        assertThat(event.getEventType()).isEqualTo("FileLoaded");
        var data = mapper.readTree(event.getPayload()).get("data");
        assertThat(data.get("fileId").asLong()).isEqualTo(entry.getId());
        assertThat(data.get("loadedRows").asInt()).isEqualTo(5);
        assertThat(data.get("rejectedRows").asInt()).isEqualTo(2);

        assertThat(names("incoming")).doesNotContain("accounts_20261102.xlsx");
        assertThat(names("processed")).contains("accounts_20261102.xlsx");
    }

    @Test
    void sameContentUnderANewNameIsNotLoadedTwice() throws Exception {
        byte[] content = file("DU", 3, 0);
        upload("accounts_20261103.xlsx", content);
        assertThat(service.runOnce()).singleElement().extracting(IngestionService.FileResult::outcome).isEqualTo(Outcome.LOADED);
        long before = logs.count();

        upload("accounts_20261104.xlsx", content);
        var second = service.runOnce();

        assertThat(second).singleElement().extracting(IngestionService.FileResult::outcome).isEqualTo(Outcome.DUPLICATE_CONTENT);
        assertThat(logs.count()).isEqualTo(before);
        assertThat(names("error")).contains("accounts_20261104.xlsx.duplicate");
        assertThat(names("incoming")).doesNotContain("accounts_20261104.xlsx");
    }

    @Test
    void aFileWithAMissingColumnIsFailedAndMovedToTheErrorFolder() throws Exception {
        upload("accounts_20261105.xlsx", XlsxBuilder.workbook(List.of("Account No", "Customer Name"), List.of()));

        var results = service.runOnce();

        assertThat(results).singleElement().satisfies(r -> {
            assertThat(r.outcome()).isEqualTo(Outcome.REJECTED_FILE);
            assertThat(r.detail()).contains("Missing required column");
        });
        var entry = logs.findAll().stream().filter(l -> "accounts_20261105.xlsx".equals(l.getFileName())).findFirst().orElseThrow();
        assertThat(entry.getStatus()).isEqualTo("FAILED");
        assertThat(entry.getErrorMessage()).contains("customerid");
        assertThat(names("error")).contains("accounts_20261105.xlsx");
        assertThat(names("incoming")).doesNotContain("accounts_20261105.xlsx");
    }

    @Test
    void aFileThatFailsHalfWayIsRestartedBySpringBatchAfterTheLastCommittedChunk() throws Exception {
        // Make the database refuse one account while the switch row exists: a real failure in the third chunk
        jdbc.execute("CREATE TABLE IF NOT EXISTS TEST_FAIL_SWITCH (ON_FLAG NUMBER(1))");
        jdbc.execute("""
            CREATE OR REPLACE TRIGGER TRG_TEST_FAIL BEFORE INSERT ON ACCOUNT_STAGING FOR EACH ROW
            DECLARE n NUMBER;
            BEGIN
              IF :NEW.ACCOUNT_NO = 'RS5' THEN
                SELECT COUNT(*) INTO n FROM TEST_FAIL_SWITCH;
                IF n > 0 THEN RAISE_APPLICATION_ERROR(-20999, 'injected failure'); END IF;
              END IF;
            END;""");
        jdbc.update("INSERT INTO TEST_FAIL_SWITCH VALUES (1)");
        try {
            upload("accounts_20261106.xlsx", file("RS", 6, 0));   // chunks of 2: RS1-2, RS3-4, RS5-6

            var first = service.runOnce();

            assertThat(first).singleElement().extracting(IngestionService.FileResult::outcome).isEqualTo(Outcome.FAILED_WILL_RETRY);
            var entry = logs.findAll().stream().filter(l -> "accounts_20261106.xlsx".equals(l.getFileName())).findFirst().orElseThrow();
            assertThat(entry.getStatus()).isEqualTo("FAILED");
            assertThat(staging.findAccountNosByFileId(entry.getId())).isEqualTo(Set.of("RS1", "RS2", "RS3", "RS4"));
            assertThat(names("incoming")).contains("accounts_20261106.xlsx");

            jdbc.update("DELETE FROM TEST_FAIL_SWITCH");
            var second = service.runOnce();

            assertThat(second).singleElement().extracting(IngestionService.FileResult::outcome).isEqualTo(Outcome.LOADED);
            assertThat(staging.findAccountNosByFileId(entry.getId())).isEqualTo(Set.of("RS1", "RS2", "RS3", "RS4", "RS5", "RS6"));
            assertThat(staging.countByFileId(entry.getId())).isEqualTo(6);   // nothing loaded twice
            var done = logs.findById(entry.getId()).orElseThrow();
            assertThat(done.getStatus()).isEqualTo("COMPLETED");
            assertThat(done.getLoadedRows()).isEqualTo(6);
            // One job instance for the file, restarted: two executions
            var instance = jobExplorer.getJobInstances(IngestionJobConfig.JOB_NAME, 0, 1000).stream()
                .filter(i -> jobExplorer.getJobExecutions(i).get(0).getJobParameters().getLong("fileId") == entry.getId())
                .toList();
            assertThat(instance).hasSize(1);
            assertThat(jobExplorer.getJobExecutions(instance.get(0))).extracting(e -> e.getStatus().name())
                .containsExactlyInAnyOrder("FAILED", "COMPLETED");
            assertThat(outbox.findAll().stream().filter(e -> String.valueOf(entry.getId()).equals(e.getAggregateId())).count()).isEqualTo(1);
        } finally {
            jdbc.execute("DROP TRIGGER TRG_TEST_FAIL");
        }
    }

    @Test
    void aCrashThatLeftTheJobMarkedRunningDoesNotBlockTheFile() throws Exception {
        byte[] content = file("CR", 3, 0);
        // What a crash leaves behind: the log row RUNNING and a job execution that never ended
        var crashed = new FileIngestionLog();
        crashed.setFileName("accounts_20261107.xlsx");
        crashed.setChecksum(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content)));
        crashed.setBusinessDate(LocalDate.of(2026, 11, 7));
        crashed = logs.save(crashed);
        var params = new JobParametersBuilder().addLong("fileId", crashed.getId()).addString("path", "/gone", false).toJobParameters();
        var stale = jobRepository.createJobExecution(IngestionJobConfig.JOB_NAME, params);
        stale.setStatus(BatchStatus.STARTED);
        jobRepository.update(stale);
        upload("accounts_20261107.xlsx", content);

        var results = service.runOnce();

        assertThat(results).singleElement().extracting(IngestionService.FileResult::outcome).isEqualTo(Outcome.LOADED);
        assertThat(jobExplorer.getJobExecution(stale.getId()).getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(staging.countByFileId(crashed.getId())).isEqualTo(3);
    }

    @Test
    void aFileNameSentAgainIsStillMovedOutOfIncoming() throws Exception {
        upload("accounts_20261108.xlsx", file("SN", 2, 0));
        assertThat(service.runOnce()).singleElement().extracting(IngestionService.FileResult::outcome).isEqualTo(Outcome.LOADED);

        upload("accounts_20261108.xlsx", file("SN2", 2, 0));   // same name, new content
        assertThat(service.runOnce()).singleElement().extracting(IngestionService.FileResult::outcome).isEqualTo(Outcome.LOADED);

        assertThat(names("incoming")).doesNotContain("accounts_20261108.xlsx");
        assertThat(names("processed")).contains("accounts_20261108.xlsx")
            .anySatisfy(n -> assertThat(n).startsWith("accounts_20261108.xlsx."));
    }

    @Test
    void filesWithOtherNamesAreLeftAlone() throws Exception {
        upload("notes.txt", "hello".getBytes());

        var results = service.runOnce();

        assertThat(results).filteredOn(r -> r.fileName().equals("notes.txt"))
            .singleElement().extracting(IngestionService.FileResult::outcome).isEqualTo(Outcome.IGNORED);
        assertThat(names("incoming")).contains("notes.txt");
        withSftp(sftp -> sftp.rm("incoming/notes.txt"));
    }
}
