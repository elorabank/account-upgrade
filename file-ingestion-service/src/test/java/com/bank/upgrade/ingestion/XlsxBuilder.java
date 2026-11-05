package com.bank.upgrade.ingestion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Builds small .xlsx files for tests. A cell value that is a Number is written as a number cell, anything else as text. */
public final class XlsxBuilder {

    public static final List<String> HEADER =
        List.of("Account No", "Customer Id", "Customer Name", "Email", "Current Type", "Balance", "KYC Status");

    private XlsxBuilder() {}

    public static byte[] workbook(List<String> header, List<List<Object>> rows) {
        try (var wb = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("accounts");
            write(sheet.createRow(0), header);
            for (int i = 0; i < rows.size(); i++) {
                write(sheet.createRow(i + 1), rows.get(i));
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<Object> row(String account, String customer, String name, String email, String type, Object balance, String kyc) {
        return java.util.Arrays.asList(account, customer, name, email, type, balance, kyc);
    }

    private static void write(org.apache.poi.ss.usermodel.Row row, List<?> values) {
        for (int c = 0; c < values.size(); c++) {
            Object v = values.get(c);
            if (v == null) {
                continue;
            }
            var cell = row.createCell(c);
            if (v instanceof Number n) {
                cell.setCellValue(n.doubleValue());
            } else {
                cell.setCellType(CellType.STRING);
                cell.setCellValue(v.toString());
            }
        }
    }
}
