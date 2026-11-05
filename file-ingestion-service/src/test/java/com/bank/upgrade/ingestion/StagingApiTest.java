package com.bank.upgrade.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.upgrade.common.api.StagedAccount;
import com.bank.upgrade.ingestion.domain.AccountStaging;
import com.bank.upgrade.ingestion.domain.AccountStagingRepository;
import com.bank.upgrade.ingestion.domain.FileIngestionLog;
import com.bank.upgrade.ingestion.domain.FileIngestionLogRepository;
import com.bank.upgrade.test.OracleTestConfig;
import com.bank.upgrade.test.TestJwt;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.blockhound.BlockHound;

/** The streaming endpoint the upgrade service reads, through the real security chain and a real HTTP server. */
@Import(OracleTestConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "bank.ingestion.schedule.enabled=false",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + TestJwt.ISSUER,
    "spring.security.oauth2.resourceserver.jwt.audiences=" + TestJwt.AUDIENCE
})
class StagingApiTest {

    static final TestJwt JWT = new TestJwt();

    @DynamicPropertySource
    static void jwks(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", JWT::jwkSetUri);
    }

    @BeforeAll
    static void installBlockHound() {
        // The endpoint reads the database: it must do so off the event loop
        BlockHound.install();
    }

    @AfterAll
    static void stop() {
        JWT.stop();
    }

    @Autowired WebTestClient client;
    @Autowired FileIngestionLogRepository logs;
    @Autowired AccountStagingRepository staging;

    private final String upgradeService = JWT.service("account-upgrade-service", "staging.read");

    private long file(String status, int rows) {
        var f = new FileIngestionLog();
        f.setFileName("accounts_20261120.xlsx");
        f.setChecksum(UUID.randomUUID().toString().replace("-", "") + "0".repeat(32));
        f.setBusinessDate(LocalDate.of(2026, 11, 20));
        f.setStatus(status);
        long id = logs.save(f).getId();
        var list = new ArrayList<AccountStaging>();
        for (int i = 1; i <= rows; i++) {
            var s = new AccountStaging();
            s.setFileId(id);
            s.setRowNo(i + 1);
            s.setAccountNo("API" + id + "-" + i);
            s.setCustomerId("C" + i);
            s.setCustomerName("Customer " + i);
            s.setEmail("c" + i + "@example.com");
            s.setCurrentType("SAVINGS");
            list.add(s);
        }
        staging.saveAll(list);
        return id;
    }

    private WebTestClient.ResponseSpec get(long fileId, String token) {
        return client.get().uri("/internal/files/{id}/accounts", fileId)
            .accept(MediaType.APPLICATION_NDJSON)
            .headers(h -> { if (token != null) h.setBearerAuth(token); })
            .exchange();
    }

    @Test
    void streamsEveryAccountOfACompletedFileInIdOrderAcrossPages() {
        long fileId = file("COMPLETED", 1_234);   // pages of 500: three database reads

        var accounts = get(fileId, upgradeService)
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
            .returnResult(StagedAccount.class).getResponseBody().collectList().block();

        assertThat(accounts).hasSize(1_234);
        assertThat(accounts).extracting(StagedAccount::id).isSorted().doesNotHaveDuplicates();
        assertThat(accounts.get(0).accountNo()).isEqualTo("API" + fileId + "-1");
        assertThat(accounts.get(0).email()).isEqualTo("c1@example.com");
    }

    @Test
    void aFileThatIsStillLoadingIsAConflict() {
        get(file("RUNNING", 2), upgradeService).expectStatus().isEqualTo(409);
    }

    @Test
    void anUnknownFileIsNotFound() {
        get(987_654_321L, upgradeService).expectStatus().isNotFound();
    }

    @Test
    void onlyTheStagingScopeMayRead() {
        long fileId = file("COMPLETED", 1);
        get(fileId, null).expectStatus().isUnauthorized();
        get(fileId, JWT.service("someone-else", "upgrade.read")).expectStatus().isForbidden();
        get(fileId, JWT.user("AU_OPS_ADMIN")).expectStatus().isForbidden();
    }
}
