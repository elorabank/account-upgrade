package com.bank.upgrade.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.upgrade.common.persistence.outbox.OutboxEvent;
import com.bank.upgrade.common.persistence.outbox.OutboxEventRepository;
import com.bank.upgrade.ingestion.domain.*;
import com.bank.upgrade.test.OracleTestConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(OracleTestConfig.class)
@SpringBootTest
class IngestionPersistenceTest {

    @Autowired FileIngestionLogRepository files;
    @Autowired AccountStagingRepository staging;
    @Autowired OutboxEventRepository outbox;
    @Autowired JdbcTemplate jdbc;

    FileIngestionLog newFile(String checksum) {
        var f = new FileIngestionLog();
        f.setFileName("accounts_20261102.xlsx");
        f.setChecksum(checksum);
        f.setBusinessDate(LocalDate.of(2026, 11, 2));
        return files.save(f);
    }

    @Test
    void sameChecksumCannotBeLoadedTwice() {
        newFile("a".repeat(64));
        assertThat(files.existsByChecksum("a".repeat(64))).isTrue();
        assertThatThrownBy(() -> newFile("a".repeat(64))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void stagingRowsAreSavedInBatchesWithClob() {
        var file = newFile("b".repeat(64));
        var rows = IntStream.rangeClosed(1, 120).mapToObj(i -> {
            var r = new AccountStaging();
            r.setFileId(file.getId());
            r.setRowNo(i);
            r.setAccountNo("ACC" + i);
            r.setCustomerId("C" + i);
            r.setCustomerName("Customer " + i);
            r.setEmail("c" + i + "@example.com");
            r.setCurrentType("SAVINGS");
            r.setBalance(new BigDecimal("1234.56"));
            r.setRawRowJson("{\"row\":" + i + ",\"pad\":\"" + "x".repeat(5000) + "\"}");
            return r;
        }).toList();
        staging.saveAll(rows);
        assertThat(staging.countByFileId(file.getId())).isEqualTo(120);
        var back = staging.findAll().stream().filter(r -> r.getFileId().equals(file.getId()) && r.getRowNo() == 7).findFirst().orElseThrow();
        assertThat(back.getRawRowJson()).hasSizeGreaterThan(5000);
        assertThat(back.getBalance()).isEqualByComparingTo("1234.56");
    }

    @Test
    void outboxEventRoundTrips() {
        var e = new OutboxEvent();
        e.setTopic("account.file-loaded");
        e.setEventType("FileLoaded");
        e.setPayload("{\"total\":10}");
        var saved = outbox.save(e);
        assertThat(outbox.findTop100ByStatusOrderByIdAsc(OutboxEvent.NEW)).extracting(OutboxEvent::getId).contains(saved.getId());
    }

    @Test
    void batchAndShedlockTablesExist() {
        for (String t : new String[] {"BATCH_JOB_INSTANCE", "BATCH_STEP_EXECUTION", "SHEDLOCK"}) {
            assertThat(jdbc.queryForObject("select count(*) from " + t, Integer.class)).isNotNegative();
        }
    }
}
