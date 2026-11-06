package com.bank.upgrade.communication.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.messaging.OutboxService;
import com.bank.upgrade.communication.config.CommunicationProperties;
import com.bank.upgrade.communication.domain.WelcomeLetter;
import com.bank.upgrade.communication.domain.WelcomeLetterRepository;
import com.bank.upgrade.test.NoOpTransactionManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.mail.Address;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

/** Sending due letters, with the database, the mail server and the outbox mocked. */
class LetterSenderTest {

    static final Instant NOW = Instant.parse("2026-11-10T22:00:00Z");

    final WelcomeLetterRepository letters = mock(WelcomeLetterRepository.class);
    final JavaMailSender mail = mock(JavaMailSender.class);
    final OutboxService outbox = mock(OutboxService.class);
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final List<MimeMessage> sent = new CopyOnWriteArrayList<>();
    final List<WelcomeLetter> due = new ArrayList<>();
    final AtomicLong ids = new AtomicLong();

    final LetterSender sender = new LetterSender(letters, mail, outbox,
        new CommunicationProperties("no-reply@bank.example", "Example Bank", 3, Duration.ofMinutes(1), 100, 2),
        new NoOpTransactionManager(), Clock.fixed(NOW, ZoneOffset.UTC), meters);

    @BeforeEach
    void mocks() {
        when(mail.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        doAnswer(inv -> {
            sent.addAll(List.of((MimeMessage[]) inv.getRawArguments()[0]));
            return null;
        }).when(mail).send(any(MimeMessage[].class));
        when(letters.lockDue(any(), anyInt())).thenAnswer(inv -> List.copyOf(due));
    }

    private WelcomeLetter letter(String account, int attemptsSoFar) {
        var l = new WelcomeLetter();
        l.setId(ids.incrementAndGet());
        l.setUpgradeRequestId(100 + l.getId());
        l.setAccountNo(account);
        l.setRecipient(account.toLowerCase() + "@example.com");
        l.setSubject("Welcome");
        l.setHtmlBody("<p>Hello</p>");
        l.setAttempts(attemptsSoFar);
        due.add(l);
        return l;
    }

    private double counted(String result) {
        var counter = meters.find("mail.letters").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void dueLettersAreSentAndEachRecordedWithItsEvent() throws Exception {
        var a = letter("A1", 0);
        var b = letter("B2", 0);

        assertThat(sender.sendDue()).isEqualTo(2);

        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).getFrom()[0].toString()).contains("no-reply@bank.example");
        assertThat(sent).extracting(m -> m.getHeader("X-Letter-Id")[0]).containsExactlyInAnyOrder("" + a.getId(), "" + b.getId());
        assertThat(List.of(a, b)).allSatisfy(l -> {
            assertThat(l.getStatus()).isEqualTo(WelcomeLetter.SENT);
            assertThat(l.getSentAt()).isEqualTo(NOW);
            assertThat(l.getAttempts()).isEqualTo(1);
        });
        verify(outbox).publish(eq(Topics.NOTIFICATION_SENT), eq("A1"), anyString(), anyString(), any(), any());
        assertThat(counted("sent")).isEqualTo(2);
    }

    @Test
    void aMailOutageIsRetriedLaterWithGrowingWaits() {
        doThrow(new MailSendException("connection refused")).when(mail).send(any(MimeMessage[].class));
        var l = letter("O1", 0);

        sender.sendDue();
        assertThat(l.getStatus()).isEqualTo(WelcomeLetter.PENDING);
        assertThat(l.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));

        sender.sendDue();
        assertThat(l.getAttempts()).isEqualTo(2);
        assertThat(l.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(2)));
        assertThat(counted("retry")).isEqualTo(2);
        verify(outbox, never()).publish(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void aLongOutageFailsTheLetterAfterTheLastAttempt() {
        doThrow(new MailSendException("connection refused")).when(mail).send(any(MimeMessage[].class));
        var l = letter("L1", 2);

        sender.sendDue();

        assertThat(l.getStatus()).isEqualTo(WelcomeLetter.FAILED);
        assertThat(l.getErrorMessage()).contains("Gave up after 3 attempts");
        verify(outbox).publish(eq(Topics.NOTIFICATION_FAILED), eq("L1"), anyString(), anyString(), any(), any());
    }

    @Test
    void anAddressTheServerRejectsFailsAtOnceAndTheOthersStillGo() throws Exception {
        var bad = letter("BAD", 0);
        var good = letter("GOOD", 0);
        doAnswer(inv -> {
            Map<Object, Exception> failed = new HashMap<>();
            for (MimeMessage m : (MimeMessage[]) inv.getRawArguments()[0]) {
                if (m.getAllRecipients()[0].toString().startsWith("bad")) {
                    failed.put(m, new SendFailedException("550 no such user", null, new Address[0], new Address[0],
                        new Address[] {new InternetAddress("bad@example.com")}));
                }
            }
            if (!failed.isEmpty()) {
                throw new MailSendException("some failed", null, failed);
            }
            return null;
        }).when(mail).send(any(MimeMessage[].class));

        sender.sendDue();

        assertThat(bad.getStatus()).isEqualTo(WelcomeLetter.FAILED);
        assertThat(bad.getAttempts()).isEqualTo(1);
        assertThat(good.getStatus()).isEqualTo(WelcomeLetter.SENT);
    }

    @Test
    void whatCountsAsPermanent() throws Exception {
        assertThat(LetterSender.permanent(new MailParseException("bad header"))).isTrue();
        assertThat(LetterSender.permanent(new SendFailedException("550", null, new Address[0], new Address[0],
            new Address[] {new InternetAddress("x@example.com")}))).isTrue();
        assertThat(LetterSender.permanent(new MailSendException("connection refused"))).isFalse();
    }

    @Test
    void nothingDueMeansNothingSent() {
        assertThat(sender.sendDue()).isZero();
        verify(mail, never()).send(any(MimeMessage[].class));
    }
}
