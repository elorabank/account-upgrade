package com.bank.upgrade.communication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.upgrade.common.events.AccountUpgraded;
import com.bank.upgrade.common.events.EventEnvelope;
import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.messaging.EventSubscription;
import com.bank.upgrade.common.messaging.NonRetryableEventException;
import com.bank.upgrade.common.persistence.outbox.OutboxEvent;
import com.bank.upgrade.common.persistence.outbox.OutboxEventRepository;
import com.bank.upgrade.communication.domain.WelcomeLetter;
import com.bank.upgrade.communication.domain.WelcomeLetterRepository;
import com.bank.upgrade.communication.mail.LetterSender;
import com.bank.upgrade.test.OracleTestConfig;
import com.bank.upgrade.test.TestJwt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.MimeMessage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** From AccountUpgraded to an email in a real mail server (Mailpit), with the database and events checked on the way. */
@Import({OracleTestConfig.class, CommunicationFlowTest.MailConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "bank.communication.schedule.enabled=false",
    "bank.communication.retry-delay=0s",
    "bank.communication.max-attempts=3",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + TestJwt.ISSUER,
    "spring.security.oauth2.resourceserver.jwt.audiences=" + TestJwt.AUDIENCE
})
class CommunicationFlowTest {

    static final GenericContainer<?> MAILPIT = new GenericContainer<>("axllent/mailpit:latest")
        .withExposedPorts(1025, 8025)
        .waitingFor(Wait.forHttp("/livez").forPort(8025));
    static final TestJwt JWT = new TestJwt();

    static {
        MAILPIT.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", JWT::jwkSetUri);
    }

    @AfterAll
    static void stop() {
        JWT.stop();
    }

    /** The real SMTP client pointed at Mailpit, with a switch to simulate the mail server being down. */
    static class SwitchableMailSender extends JavaMailSenderImpl {
        final AtomicBoolean down = new AtomicBoolean();

        @Override
        protected void doSend(MimeMessage[] messages, Object[] originals) throws MailException {
            if (down.get()) {
                throw new MailSendException("Connection refused (simulated)");
            }
            super.doSend(messages, originals);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MailConfig {
        @Bean
        SwitchableMailSender mailSender() {
            var sender = new SwitchableMailSender();
            sender.setHost(MAILPIT.getHost());
            sender.setPort(MAILPIT.getMappedPort(1025));
            return sender;
        }
    }

    @Autowired ObjectProvider<EventSubscription> subscriptions;
    @Autowired WelcomeLetterRepository letters;
    @Autowired OutboxEventRepository outbox;
    @Autowired LetterSender sender;
    @Autowired SwitchableMailSender mail;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired WebTestClient client;

    static final AtomicLong requestIds = new AtomicLong(1000);
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clean() throws Exception {
        jdbc.update("DELETE FROM WELCOME_LETTER");
        jdbc.update("DELETE FROM OUTBOX_EVENT");
        mail.down.set(false);
        http.send(HttpRequest.newBuilder(mailpit("/api/v1/messages")).DELETE().build(), HttpResponse.BodyHandlers.discarding());
    }

    // --- helpers ------------------------------------------------------------------------------------

    URI mailpit(String path) {
        return URI.create("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + path);
    }

    JsonNode mailpitJson(String path) throws Exception {
        var body = http.send(HttpRequest.newBuilder(mailpit(path)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        return mapper.readTree(body);
    }

    AccountUpgraded upgrade(String account, String name, String toType) {
        return new AccountUpgraded(requestIds.incrementAndGet(), account, "C-" + account, name,
            account.toLowerCase() + "@example.com", "SAVINGS", toType, LocalDate.of(2026, 11, 10), Instant.now());
    }

    void deliver(AccountUpgraded upgrade) {
        var handler = subscriptions.stream().filter(s -> s.topic().equals(Topics.ACCOUNT_UPGRADED)).findFirst().orElseThrow().handler();
        var envelope = new EventEnvelope(1, UUID.randomUUID().toString(), "AccountUpgraded", "corr-" + upgrade.accountNo(),
            Instant.now(), "system:account-upgrade-service", mapper.valueToTree(upgrade));
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            try {
                handler.handle(envelope);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    WelcomeLetter letterFor(String account) {
        return letters.findByAccountNo(account).get(0);
    }

    List<OutboxEvent> events(String topic) {
        return outbox.findAll().stream().filter(e -> e.getTopic().equals(topic)).toList();
    }

    // --- tests --------------------------------------------------------------------------------------

    @Test
    void anUpgradedAccountGetsItsWelcomeLetterByEmail() throws Exception {
        deliver(upgrade("AC261110000042", "Asha Rao", "SAVINGS_PLUS"));

        var queued = letterFor("AC261110000042");
        assertThat(queued.getStatus()).isEqualTo(WelcomeLetter.PENDING);
        assertThat(queued.getSubject()).isEqualTo("Your account is now Savings Plus");
        assertThat(queued.getHtmlBody()).contains("Asha Rao").contains("ending 0042").contains("10 November 2026");

        assertThat(sender.sendDue()).isEqualTo(1);

        var inbox = mailpitJson("/api/v1/messages");
        assertThat(inbox.get("total").asInt()).isEqualTo(1);
        var message = inbox.get("messages").get(0);
        assertThat(message.get("Subject").asText()).isEqualTo("Your account is now Savings Plus");
        assertThat(message.get("To").get(0).get("Address").asText()).isEqualTo("ac261110000042@example.com");
        assertThat(message.get("From").get("Address").asText()).isEqualTo("no-reply@bank.example");
        var full = mailpitJson("/api/v1/message/" + message.get("ID").asText());
        assertThat(full.get("HTML").asText()).contains("Savings Plus").contains("ending 0042").doesNotContain("AC261110000042");

        var sent = letterFor("AC261110000042");
        assertThat(sent.getStatus()).isEqualTo(WelcomeLetter.SENT);
        assertThat(sent.getAttempts()).isEqualTo(1);
        assertThat(sent.getSentAt()).isNotNull();
        assertThat(events(Topics.NOTIFICATION_SENT)).singleElement().satisfies(e -> {
            assertThat(e.getMsgKey()).isEqualTo("AC261110000042");
            assertThat(e.getCorrelationId()).isEqualTo("corr-AC261110000042");
        });
        assertThat(sender.sendDue()).isZero();   // nothing is sent twice
    }

    @Test
    void theCurrentAccountTemplateIsUsedForCurrentPlus() {
        deliver(upgrade("CU1", "Ben Cole", "CURRENT_PLUS"));

        assertThat(letterFor("CU1").getHtmlBody()).contains("Current Plus").contains("overdraft");
    }

    @Test
    void theSameUpgradeAnnouncedTwiceGivesOneLetter() {
        var upgrade = upgrade("DUP1", "Chen Wei", "SAVINGS_PLUS");

        deliver(upgrade);
        deliver(upgrade);   // a new event id, the same upgrade

        assertThat(letters.findByAccountNo("DUP1")).hasSize(1);
    }

    @Test
    void anAccountTypeWithoutATemplateGoesToTheDeadLetterTopic() {
        assertThatThrownBy(() -> deliver(upgrade("NT1", "Dana Fox", "GOLD")))
            .isInstanceOf(NonRetryableEventException.class)
            .hasMessageContaining("GOLD");
        assertThat(letters.findByAccountNo("NT1")).isEmpty();
    }

    @Test
    void aShortMailOutageIsRetriedAndTheEmailStillGoesOut() throws Exception {
        deliver(upgrade("RT1", "Eli Nash", "SAVINGS_PLUS"));
        mail.down.set(true);

        sender.sendDue();

        var waiting = letterFor("RT1");
        assertThat(waiting.getStatus()).isEqualTo(WelcomeLetter.PENDING);
        assertThat(waiting.getAttempts()).isEqualTo(1);
        assertThat(waiting.getErrorMessage()).contains("Connection refused");
        assertThat(waiting.getNextAttemptAt()).isNotNull();

        mail.down.set(false);
        sender.sendDue();

        var sent = letterFor("RT1");
        assertThat(sent.getStatus()).isEqualTo(WelcomeLetter.SENT);
        assertThat(sent.getAttempts()).isEqualTo(2);
        assertThat(sent.getErrorMessage()).isNull();
        assertThat(mailpitJson("/api/v1/messages").get("total").asInt()).isEqualTo(1);
    }

    @Test
    void aLongOutageFailsTheLetterAfterTheLastAttempt() {
        deliver(upgrade("LO1", "Farah Khan", "SAVINGS_PLUS"));
        mail.down.set(true);

        sender.sendDue();
        sender.sendDue();
        sender.sendDue();   // max-attempts=3

        var failed = letterFor("LO1");
        assertThat(failed.getStatus()).isEqualTo(WelcomeLetter.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(3);
        assertThat(failed.getErrorMessage()).contains("Gave up after 3 attempts");
        assertThat(events(Topics.NOTIFICATION_FAILED)).hasSize(1);
        assertThat(sender.sendDue()).isZero();
    }

    @Test
    void supportCanResendALetterAndTheRequestIsAudited() throws Exception {
        deliver(upgrade("RS1", "Gopal Iyer", "SAVINGS_PLUS"));
        sender.sendDue();
        long id = letterFor("RS1").getId();

        client.post().uri("/api/notifications/{id}/resend", id)
            .headers(h -> h.setBearerAuth(JWT.user("AU_SUPPORT")))
            .exchange()
            .expectStatus().isAccepted()
            .expectBody().jsonPath("$.status").isEqualTo("PENDING");

        var again = letterFor("RS1");
        assertThat(again.getStatus()).isEqualTo(WelcomeLetter.PENDING);
        assertThat(again.getAttempts()).isZero();
        var audit = events(Topics.USER_ACTION);
        assertThat(audit).hasSize(1);
        var envelope = mapper.readTree(audit.get(0).getPayload());
        assertThat(envelope.get("data").get("action").asText()).isEqualTo("RESEND_WELCOME_EMAIL");
        assertThat(envelope.get("actor").asText()).isNotBlank().doesNotStartWith("system:");

        sender.sendDue();
        assertThat(mailpitJson("/api/v1/messages").get("total").asInt()).isEqualTo(2);
    }

    @Test
    void resendIsLimitedAndUnknownLettersAreNotFound() {
        deliver(upgrade("RS2", "Hana Sato", "SAVINGS_PLUS"));
        long id = letterFor("RS2").getId();

        client.post().uri("/api/notifications/{id}/resend", id)
            .headers(h -> h.setBearerAuth(JWT.user("AU_OPS_VIEWER"))).exchange().expectStatus().isForbidden();
        client.post().uri("/api/notifications/{id}/resend", id).exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/notifications/{id}/resend", 987_654_321L)
            .headers(h -> h.setBearerAuth(JWT.user("AU_OPS_ADMIN"))).exchange().expectStatus().isNotFound();
    }
}
