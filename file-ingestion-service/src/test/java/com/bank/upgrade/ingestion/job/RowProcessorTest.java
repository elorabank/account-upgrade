package com.bank.upgrade.ingestion.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.upgrade.ingestion.excel.RawRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RowProcessorTest {

    private final RowProcessor processor = new RowProcessor(7L, new HashSet<>(), new ObjectMapper());

    private static Map<String, String> good() {
        var m = new HashMap<String, String>();
        m.put("accountno", "ACC-001");
        m.put("customerid", "C1");
        m.put("customername", "Asha Rao");
        m.put("email", "asha@example.com");
        m.put("currenttype", "savings");
        m.put("balance", "1,250.50");
        m.put("kycstatus", "verified");
        return m;
    }

    private ParsedRow run(int rowNo, Map<String, String> values) {
        return processor.process(new RawRow(rowNo, values));
    }

    @Test
    void validRowBecomesStagingWithNormalisedValues() {
        var result = (ParsedRow.Valid) run(2, good());

        var s = result.staging();
        assertThat(s.getFileId()).isEqualTo(7L);
        assertThat(s.getRowNo()).isEqualTo(2);
        assertThat(s.getCurrentType()).isEqualTo("SAVINGS");
        assertThat(s.getKycStatus()).isEqualTo("VERIFIED");
        assertThat(s.getBalance()).isEqualByComparingTo("1250.50");
        assertThat(s.getRawRowJson()).contains("ACC-001");
    }

    @Test
    void emailAddressesMustHaveTheShapeOfOne() {
        int row = 2;
        for (String ok : List.of("asha@example.com", "a.b+tag@mail.example.co.in", "x@y.z")) {
            var v = good();
            v.put("accountno", "ACC-" + row++);   // each row its own account: a repeat is rejected as a duplicate
            v.put("email", ok);
            assertThat(run(2, v)).as(ok).isInstanceOf(ParsedRow.Valid.class);
        }
        for (String bad : List.of("not-an-email", "a@b", "a@b..c", "a@.b.c", "a@b.c.", "a b@c.d", "a@@b.c")) {
            var v = good();
            v.put("accountno", "ACC-" + row++);
            v.put("email", bad);
            assertThat(run(2, v)).as(bad).isInstanceOfSatisfying(ParsedRow.Rejected.class,
                r -> assertThat(r.error().getErrorMessage()).contains("email"));
        }
    }

    @Test
    void badRowListsEveryProblem() {
        var v = good();
        v.put("email", "not-an-email");
        v.put("customername", "");
        v.put("balance", "twelve");

        var result = (ParsedRow.Rejected) run(5, v);

        assertThat(result.error().getRowNo()).isEqualTo(5);
        assertThat(result.error().getErrorMessage())
            .contains("email").contains("customer name is missing").contains("balance is not a number");
        assertThat(result.error().getRawRowJson()).contains("not-an-email");
    }

    @Test
    void balanceWithMoreThanTwoDecimalsIsRejected() {
        var v = good();
        v.put("balance", "10.123");

        assertThat(run(2, v)).isInstanceOf(ParsedRow.Rejected.class);
    }

    @Test
    void emptyBalanceAndKycAreAllowed() {
        var v = good();
        v.put("balance", "");
        v.put("kycstatus", "");

        var s = ((ParsedRow.Valid) run(2, v)).staging();
        assertThat(s.getBalance()).isNull();
        assertThat(s.getKycStatus()).isNull();
    }

    @Test
    void secondOccurrenceOfAnAccountIsRejected() {
        assertThat(run(2, good())).isInstanceOf(ParsedRow.Valid.class);

        var second = (ParsedRow.Rejected) run(3, good());

        assertThat(second.error().getErrorMessage()).contains("more than once");
    }

    @Test
    void accountsAlreadyLoadedBeforeARestartCountAsSeen() {
        var restarted = new RowProcessor(7L, new HashSet<>(java.util.Set.of("ACC-001")), new ObjectMapper());

        assertThat(restarted.process(new RawRow(9, good()))).isInstanceOf(ParsedRow.Rejected.class);
    }
}
