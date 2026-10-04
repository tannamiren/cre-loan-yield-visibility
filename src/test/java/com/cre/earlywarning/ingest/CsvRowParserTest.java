package com.cre.earlywarning.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvRowParserTest {

    private final CsvRowParser parser = new CsvRowParser();

    @Test
    void parsesHeaderedCsvIntoRows(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("report-2024-01.csv");
        Files.writeString(file, """
            loan_id,month,balance,noi,yearly_payments,payments_late
            L001,2024-01,25000000.00,2000000.00,1600000.00,0
            L002,2024-01,10000000.00,900000.00,700000.00,2
            """);

        List<CsvRow> rows = parser.parse(file);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).isEqualTo(new CsvRow("L001", YearMonth.of(2024, 1),
            new BigDecimal("25000000.00"), new BigDecimal("2000000.00"),
            new BigDecimal("1600000.00"), 0));
        assertThat(rows.get(1).paymentsLate()).isEqualTo(2);
    }
}
