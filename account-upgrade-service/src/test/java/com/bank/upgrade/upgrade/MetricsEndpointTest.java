package com.bank.upgrade.upgrade;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.upgrade.test.OracleTestConfig;
import com.bank.upgrade.test.RedisTestConfig;
import com.bank.upgrade.test.TestJwt;
import com.bank.upgrade.upgrade.domain.UpgradeRequest;
import com.bank.upgrade.upgrade.domain.UpgradeRequestRepository;
import com.bank.upgrade.upgrade.run.UpgradeRunner;
import java.time.LocalDate;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * What Prometheus scrapes: /actuator/prometheus with the metrics.read scope, carrying the run's metrics under the
 * names the Grafana dashboard queries (infra/monitoring/grafana/dashboards).
 */
@Import({OracleTestConfig.class, RedisTestConfig.class})
@AutoConfigureObservability(tracing = false)   // tests normally switch metrics export off
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "bank.upgrade.schedule.enabled=false",
    "bank.upgrade.gateway=simulated",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + TestJwt.ISSUER,
    "spring.security.oauth2.resourceserver.jwt.audiences=" + TestJwt.AUDIENCE
})
class MetricsEndpointTest {

    static final TestJwt JWT = new TestJwt();
    static final LocalDate DAY = LocalDate.of(2027, 3, 1);

    @DynamicPropertySource
    static void jwks(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", JWT::jwkSetUri);
    }

    @AfterAll
    static void stop() {
        JWT.stop();
    }

    @Autowired WebTestClient client;
    @Autowired UpgradeRequestRepository requests;
    @Autowired UpgradeRunner runner;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aRunShowsUpInTheScrape() {
        jdbc.update("DELETE FROM UPGRADE_REQUEST");
        var list = new ArrayList<UpgradeRequest>();
        for (int i = 1; i <= 5; i++) {
            var r = new UpgradeRequest();
            r.setAccountNo("M" + i);
            r.setCustomerId("C" + i);
            r.setCustomerName("Customer " + i);
            r.setEmail("m" + i + "@example.com");
            r.setCurrentType("SAVINGS");
            r.setTargetType("SAVINGS_PLUS");
            r.setBusinessDate(DAY);
            list.add(r);
        }
        requests.saveAll(list);
        assertThat(runner.runOnce().upgraded()).isEqualTo(5);

        String scrape = client.get().uri("/actuator/prometheus")
            .headers(h -> h.setBearerAuth(JWT.service("prometheus", "metrics.read")))
            .exchange().expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();

        assertThat(scrape)
            .contains("upgrade_requests_total{application=\"account-upgrade-service\",result=\"upgraded\"} 5.0")
            .contains("upgrade_core_call_seconds_count{application=\"account-upgrade-service\",outcome=\"ok\"} 5")
            .contains("upgrade_core_call_seconds_bucket{")
            .contains("upgrade_core_limit_wait_seconds_count{")
            .contains("upgrade_requests_held{application=\"account-upgrade-service\"} 0.0")
            .contains("upgrade_run_active{application=\"account-upgrade-service\"} 0.0")
            .contains("upgrade_core_limit_per_second{")
            .contains("upgrade_leases_taken_over_total{")
            .contains("bank_outbox_waiting{")
            .contains("bank_outbox_oldest_age_seconds{")
            .contains("hikaricp_connections_active{");
    }

    @Test
    void otherTokensCannotScrape() {
        client.get().uri("/actuator/prometheus").exchange().expectStatus().isUnauthorized();
        client.get().uri("/actuator/prometheus").headers(h -> h.setBearerAuth(JWT.user("AU_OPS_ADMIN")))
            .exchange().expectStatus().isForbidden();
        client.get().uri("/actuator/prometheus").headers(h -> h.setBearerAuth(JWT.service("perf-runner", "upgrade.admin")))
            .exchange().expectStatus().isForbidden();
    }
}
