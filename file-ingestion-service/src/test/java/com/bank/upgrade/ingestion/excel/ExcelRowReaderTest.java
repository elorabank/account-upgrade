package com.bank.upgrade.ingestion.excel;

import static com.bank.upgrade.ingestion.XlsxBuilder.HEADER;
import static com.bank.upgrade.ingestion.XlsxBuilder.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.upgrade.ingestion.XlsxBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.item.ExecutionContext;

class ExcelRowReaderTest {

    @TempDir Path dir;

    private Path write(byte[] bytes) throws Exception {
        return Files.write(dir.resolve("f.xlsx"), bytes);
    }

    /** @param alreadyRead rows a previous, failed attempt committed (what Spring Batch saved) */
    private List<RawRow> readAll(Path file, int alreadyRead) throws Exception {
        var reader = new ExcelRowReader(file);
        var saved = new ExecutionContext();
        if (alreadyRead > 0) {
            saved.putInt("excelRowReader.read.count", alreadyRead);
        }
        reader.open(saved);
        try {
            var rows = new ArrayList<RawRow>();
            for (RawRow r; (r = reader.read()) != null; ) {
                rows.add(r);
            }
            return rows;
        } finally {
            reader.close();
        }
    }

    @Test
    void readsRowsByHeaderNameWithExcelRowNumbers() throws Exception {
        var file = write(XlsxBuilder.workbook(HEADER, List.of(
            row("ACC1", "C1", "Asha Rao", "asha@example.com", "savings", 1500.5, "VERIFIED"),
            row("ACC2", "C2", "Ben Cole", "ben@example.com", "current", 20, null))));

        var rows = readAll(file, 0);

        assertThat(rows).extracting(RawRow::rowNo).containsExactly(2, 3);
        assertThat(rows.get(0).values())
            .containsEntry("accountno", "ACC1")
            .containsEntry("customername", "Asha Rao")
            .containsEntry("balance", "1500.5")
            .containsEntry("kycstatus", "VERIFIED");
        assertThat(rows.get(1).values()).containsEntry("balance", "20");
    }

    @Test
    void columnOrderAndHeaderSpellingDoNotMatter() throws Exception {
        var header = List.of("EMAIL", "current_type", "customer name", "Customer-ID", "ACCOUNT NO.");
        var file = write(XlsxBuilder.workbook(header, List.of(Arrays.<Object>asList("a@b.co", "SAVINGS", "A B", "C9", "ACC9"))));

        var rows = readAll(file, 0);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).values()).containsEntry("accountno", "ACC9").containsEntry("customerid", "C9");
    }

    @Test
    void skipsBlankRowsAndOnRestartTheRowsAlreadyCommitted() throws Exception {
        var file = write(XlsxBuilder.workbook(HEADER, List.of(
            row("A1", "C", "N", "a@b.co", "SAVINGS", null, null),
            Arrays.<Object>asList(null, null, null),
            row("A3", "C", "N", "a@b.co", "SAVINGS", null, null),
            row("A4", "C", "N", "a@b.co", "SAVINGS", null, null))));

        assertThat(readAll(file, 0)).extracting(RawRow::rowNo).containsExactly(2, 4, 5);
        // Restart after two rows were committed: the blank row is not counted, so row 5 is next
        assertThat(readAll(file, 2)).extracting(RawRow::rowNo).containsExactly(5);
    }

    @Test
    void missingRequiredColumnFailsTheWholeFile() throws Exception {
        var file = write(XlsxBuilder.workbook(List.of("Account No", "Customer Name"), List.of()));

        // Spring Batch wraps whatever open() throws; the job looks for InvalidFileException in the causes
        assertThatThrownBy(() -> readAll(file, 0))
            .cause().isInstanceOf(InvalidFileException.class)
            .hasMessageContaining("customerid")
            .hasMessageContaining("email");
    }

    @Test
    void aFileThatIsNotAWorkbookIsInvalid() throws Exception {
        var file = write("this is not an excel file".getBytes());

        assertThatThrownBy(() -> readAll(file, 0)).cause().isInstanceOf(InvalidFileException.class);
    }
}
