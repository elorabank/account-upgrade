package com.bank.upgrade.ingestion.job;

import static com.bank.upgrade.ingestion.excel.ExcelRowReader.*;

import com.bank.upgrade.ingestion.domain.AccountStaging;
import com.bank.upgrade.ingestion.domain.IngestionError;
import com.bank.upgrade.ingestion.excel.RawRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.batch.item.ItemProcessor;

/**
 * Checks one row. A bad row never stops the file: it becomes an INGESTION_ERROR with every problem listed.
 * Account numbers already loaded for this file (also on a restart) are remembered to catch duplicates.
 */
public class RowProcessor implements ItemProcessor<RawRow, ParsedRow> {

    private static final Pattern ACCOUNT_NO_FORMAT = Pattern.compile("[A-Za-z0-9-]{1,34}");
    private static final Pattern EMAIL_FORMAT = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private final long fileId;
    private final Set<String> seenAccounts;
    private final ObjectMapper mapper;

    public RowProcessor(long fileId, Set<String> alreadyLoadedAccounts, ObjectMapper mapper) {
        this.fileId = fileId;
        this.seenAccounts = alreadyLoadedAccounts;
        this.mapper = mapper;
    }

    @Override
    public ParsedRow process(RawRow row) {
        var v = row.values();
        List<String> problems = new ArrayList<>();

        String accountNo = v.getOrDefault(ACCOUNT_NO, "");
        if (!ACCOUNT_NO_FORMAT.matcher(accountNo).matches()) {
            problems.add("account no must be 1-34 letters, digits or dashes");
        }
        String customerId = required(v.get(CUSTOMER_ID), 36, "customer id", problems);
        String customerName = required(v.get(CUSTOMER_NAME), 200, "customer name", problems);
        String email = v.getOrDefault(EMAIL, "");
        if (email.length() > 254 || !EMAIL_FORMAT.matcher(email).matches()) {
            problems.add("email is missing or not valid");
        }
        String type = required(v.get(CURRENT_TYPE), 30, "current type", problems);
        String kyc = v.getOrDefault(KYC_STATUS, "");
        if (kyc.length() > 20) {
            problems.add("kyc status is longer than 20 characters");
        }
        BigDecimal balance = null;
        String balanceText = v.getOrDefault(BALANCE, "");
        if (!balanceText.isEmpty()) {
            try {
                balance = new BigDecimal(balanceText.replace(",", ""));
                if (balance.stripTrailingZeros().scale() > 2 || balance.precision() - balance.scale() > 16) {
                    problems.add("balance must have at most 2 decimals and 16 integer digits");
                }
            } catch (NumberFormatException e) {
                problems.add("balance is not a number");
            }
        }
        if (problems.isEmpty() && !seenAccounts.add(accountNo)) {
            problems.add("account " + accountNo + " appears more than once in the file");
        }

        String raw = json(v);
        if (!problems.isEmpty()) {
            var error = new IngestionError();
            error.setFileId(fileId);
            error.setRowNo(row.rowNo());
            error.setErrorMessage(truncate(String.join("; ", problems), 2000));
            error.setRawRowJson(raw);
            return new ParsedRow.Rejected(row.rowNo(), error);
        }

        var staging = new AccountStaging();
        staging.setFileId(fileId);
        staging.setRowNo(row.rowNo());
        staging.setAccountNo(accountNo);
        staging.setCustomerId(customerId);
        staging.setCustomerName(customerName);
        staging.setEmail(email);
        staging.setCurrentType(type.toUpperCase(Locale.ROOT));
        staging.setBalance(balance);
        staging.setKycStatus(kyc.isEmpty() ? null : kyc.toUpperCase(Locale.ROOT));
        staging.setRawRowJson(raw);
        return new ParsedRow.Valid(row.rowNo(), staging);
    }

    private static String required(String value, int maxLength, String label, List<String> problems) {
        if (value == null || value.isEmpty()) {
            problems.add(label + " is missing");
            return "";
        }
        if (value.length() > maxLength) {
            problems.add(label + " is longer than " + maxLength + " characters");
        }
        return value;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
