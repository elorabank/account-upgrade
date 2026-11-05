package com.bank.upgrade.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.upgrade.test.OracleTestConfig;
import com.bank.upgrade.upgrade.domain.UpgradeRequest;
import com.bank.upgrade.upgrade.domain.UpgradeRequestRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@Import(OracleTestConfig.class)
@SpringBootTest
class UpgradePersistenceTest {

    @Autowired UpgradeRequestRepository repo;

    UpgradeRequest request(String account, LocalDate date) {
        var r = new UpgradeRequest();
        r.setAccountNo(account);
        r.setCustomerId("C1");
        r.setCustomerName("Test Customer");
        r.setEmail("test@example.com");
        r.setCurrentType("SAVINGS");
        r.setBusinessDate(date);
        return r;
    }

    @Test
    void sameAccountCannotBeQueuedTwiceForOneDay() {
        var day = LocalDate.of(2026, 11, 2);
        repo.saveAndFlush(request("ACC-1", day));
        assertThat(repo.saveAndFlush(request("ACC-1", day.plusDays(1))).getId()).isNotNull();
        assertThatThrownBy(() -> repo.saveAndFlush(request("ACC-1", day))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void statusChangeBumpsVersionAndClaimsByRunId() {
        var saved = repo.saveAndFlush(request("ACC-2", LocalDate.of(2026, 11, 3)));
        saved.setStatus(UpgradeRequest.IN_PROGRESS);
        saved.setRunId("run-1");
        var updated = repo.saveAndFlush(saved);
        assertThat(updated.getVersion()).isEqualTo(1);
        assertThat(repo.findByRunId("run-1")).hasSize(1);
    }
}
