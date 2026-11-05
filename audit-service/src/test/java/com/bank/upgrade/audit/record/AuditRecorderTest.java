package com.bank.upgrade.audit.record;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class AuditRecorderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode data() {
        return mapper.createObjectNode();
    }

    @Test
    void summariesUseCodesAndCountsOnly() {
        var upgraded = data().put("accountNo", "AC1").put("customerName", "Asha Rao").put("email", "asha@example.com")
            .put("fromType", "SAVINGS").put("toType", "SAVINGS_PLUS").put("businessDate", "2026-11-10");

        String summary = AuditRecorder.summary("AccountUpgraded", upgraded);

        assertThat(summary).isEqualTo("Upgraded SAVINGS -> SAVINGS_PLUS for 2026-11-10");
        assertThat(summary).doesNotContain("Asha").doesNotContain("@");
    }

    @Test
    void everyKnownTypeHasItsOwnSummary() {
        assertThat(AuditRecorder.summary("FileLoaded", data().put("fileName", "accounts_20261110.xlsx")
            .put("businessDate", "2026-11-10").put("totalRows", 10000).put("loadedRows", 9916).put("rejectedRows", 84)))
            .isEqualTo("accounts_20261110.xlsx for 2026-11-10: 10000 rows, 9916 loaded, 84 rejected");
        assertThat(AuditRecorder.summary("UpgradeRejected", data().put("currentType", "LOAN").put("reason", "no upgrade path")))
            .isEqualTo("Not upgraded (LOAN): no upgrade path");
        assertThat(AuditRecorder.summary("UpgradeFailed", data().put("fromType", "SAVINGS").put("toType", "SAVINGS_PLUS")
            .put("attempts", 3).put("reason", "core down")))
            .isEqualTo("Upgrade SAVINGS -> SAVINGS_PLUS failed after 3 attempt(s): core down");
        assertThat(AuditRecorder.summary("NotificationSent", data().put("attempts", 2))).isEqualTo("Welcome email sent (attempt 2)");
        assertThat(AuditRecorder.summary("UserAction", data().put("action", "RESEND_WELCOME_EMAIL")
            .put("targetType", "WELCOME_LETTER").put("targetId", "42")))
            .isEqualTo("RESEND_WELCOME_EMAIL on WELCOME_LETTER 42");
        assertThat(AuditRecorder.summary("SomethingNew", data())).isEqualTo("SomethingNew");
    }

    @Test
    void anEmailQuotedInAnErrorIsMasked() {
        String summary = AuditRecorder.clean(AuditRecorder.summary("NotificationFailed", data().put("attempts", 1)
            .put("reason", "550 Invalid Addresses: <asha.rao@example.com>")));

        assertThat(summary).isEqualTo("Welcome email failed after 1 attempt(s): 550 Invalid Addresses: <[email removed]>");
    }

    @Test
    void longSummariesFitTheColumn() {
        assertThat(AuditRecorder.clean("x".repeat(5000))).hasSize(1000).endsWith("...");
    }
}
