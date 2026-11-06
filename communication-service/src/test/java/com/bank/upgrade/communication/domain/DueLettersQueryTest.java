package com.bank.upgrade.communication.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * The due-letter query names its index in a hint, and Oracle ignores a hint whose index does not exist, without an
 * error: the query would quietly go back to reading the whole table. This keeps the hint and the migrations in step,
 * without a database.
 */
class DueLettersQueryTest {

    private static String lockDueSql() throws NoSuchMethodException {
        return WelcomeLetterRepository.class.getMethod("lockDue", Instant.class, int.class).getAnnotation(Query.class).value();
    }

    /** The changelog files in the order Liquibase runs them. */
    private static List<String> changelog() throws IOException {
        String master = read("db/changelog/db.changelog-master.yaml");
        Matcher file = Pattern.compile("file:\\s*(\\S+)").matcher(master);
        List<String> scripts = new ArrayList<>();
        while (file.find()) {
            scripts.add(read(file.group(1)));
        }
        return scripts;
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = DueLettersQueryTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theHintNamesTheIndexTheMigrationsLeaveInPlace() throws Exception {
        Matcher hint = Pattern.compile("/\\*\\+ INDEX\\(d (\\w+)\\) \\*/").matcher(lockDueSql());
        assertThat(hint.find()).as("the inner select names its index").isTrue();
        String index = hint.group(1);
        assertThat(index).isEqualTo(WelcomeLetterRepository.DUE_INDEX);

        boolean exists = false;
        for (String script : changelog()) {
            if (script.matches("(?s).*CREATE INDEX " + index + " ON WELCOME_LETTER \\(STATUS, ID\\).*")) {
                exists = true;
            }
            if (script.contains("DROP INDEX " + index)) {
                exists = false;
            }
        }
        assertThat(exists).as(index + " on (STATUS, ID) is created and not dropped later").isTrue();
    }

    @Test
    void theInnerSelectReadsInIdOrderAndStopsAtTheLimit() throws Exception {
        assertThat(lockDueSql()).contains("d.STATUS = 'PENDING'").contains("ORDER BY d.ID FETCH FIRST :limit ROWS ONLY")
            .contains("FOR UPDATE SKIP LOCKED");
    }
}
