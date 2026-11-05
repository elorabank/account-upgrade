package com.bank.upgrade.ingestion.excel;

import com.github.pjfanning.xlsx.StreamingReader;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.support.AbstractItemCountingItemStreamItemReader;

/**
 * Streams the first sheet of an .xlsx file one row at a time, so a file with millions of rows needs a few MB of memory.
 * Columns are found by header name (case, spaces and punctuation ignored), so their order in the sheet does not matter.
 * <p>
 * Spring Batch saves how many rows were read with every committed chunk (in BATCH_STEP_EXECUTION_CONTEXT, same
 * transaction as the rows). When a failed job is restarted, {@link AbstractItemCountingItemStreamItemReader} reads
 * past that many rows again before handing out the next one, so the file resumes after its last committed chunk.
 */
public class ExcelRowReader extends AbstractItemCountingItemStreamItemReader<RawRow> {

    public static final String ACCOUNT_NO = "accountno";
    public static final String CUSTOMER_ID = "customerid";
    public static final String CUSTOMER_NAME = "customername";
    public static final String EMAIL = "email";
    public static final String CURRENT_TYPE = "currenttype";
    public static final String BALANCE = "balance";
    public static final String KYC_STATUS = "kycstatus";

    private static final Set<String> REQUIRED = Set.of(ACCOUNT_NO, CUSTOMER_ID, CUSTOMER_NAME, EMAIL, CURRENT_TYPE);

    private final Path file;

    private Workbook workbook;
    private Iterator<Row> rows;
    private final Map<Integer, String> headers = new HashMap<>();

    public ExcelRowReader(Path file) {
        this.file = file;
        setName("excelRowReader");   // key of the saved read count
    }

    @Override
    protected void doOpen() {
        try {
            workbook = StreamingReader.builder().rowCacheSize(200).bufferSize(8192).open(file.toFile());
            if (workbook.getNumberOfSheets() == 0) {
                throw new InvalidFileException("The workbook has no sheets");
            }
            rows = workbook.getSheetAt(0).iterator();
            readHeader();
        } catch (InvalidFileException e) {
            doClose();
            throw e;
        } catch (RuntimeException e) {
            doClose();
            // POI reports a corrupt or non-xlsx file as a RuntimeException of some kind
            throw new InvalidFileException("The file cannot be read as an .xlsx workbook: " + e.getMessage(), e);
        }
    }

    private void readHeader() {
        if (!rows.hasNext()) {
            throw new InvalidFileException("The sheet is empty, a header row is required");
        }
        Row header = rows.next();
        for (Cell cell : header) {
            String name = normalise(text(cell));
            if (!name.isEmpty()) {
                headers.put(cell.getColumnIndex(), name);
            }
        }
        var missing = new java.util.TreeSet<>(REQUIRED);
        missing.removeAll(headers.values());
        if (!missing.isEmpty()) {
            throw new InvalidFileException("Missing required column(s): " + missing);
        }
    }

    /** The next non-blank data row, or null at the end of the sheet. Blank rows are not counted. */
    @Override
    protected RawRow doRead() {
        while (rows.hasNext()) {
            Row row = rows.next();
            int rowNo = row.getRowNum() + 1;
            Map<String, String> values = new LinkedHashMap<>();
            boolean blank = true;
            for (Cell cell : row) {
                String name = headers.get(cell.getColumnIndex());
                if (name == null) {
                    continue;
                }
                String value = text(cell);
                if (!value.isEmpty()) {
                    blank = false;
                }
                values.put(name, value);
            }
            if (!blank) {
                return new RawRow(rowNo, values);
            }
        }
        return null;
    }

    @Override
    protected void doClose() {
        if (workbook != null) {
            try {
                workbook.close();
            } catch (IOException e) {
                throw new ItemStreamException("Could not close " + file, e);
            } finally {
                workbook = null;
            }
        }
    }

    /** Lower case letters and digits only: "Account No." and "ACCOUNT_NO" both become {@code accountno}. */
    static String normalise(String header) {
        return header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String text(Cell cell) {
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case STRING -> cell.getStringCellValue().trim();
            // Account numbers should be formatted as text in the sheet; a number cell cannot keep leading zeros or 16+ digits
            case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }
}
