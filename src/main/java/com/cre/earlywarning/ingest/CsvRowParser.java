package com.cre.earlywarning.ingest;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;

@Component
public class CsvRowParser {

    public List<CsvRow> parse(Path csvFile) throws IOException {
        List<String> lines = Files.readAllLines(csvFile);
        return lines.stream()
            .skip(1) // header
            .filter(line -> !line.isBlank())
            .map(this::parseLine)
            .toList();
    }

    private CsvRow parseLine(String line) {
        String[] columns = line.split(",");
        return new CsvRow(
            columns[0].trim(),
            YearMonth.parse(columns[1].trim()),
            new BigDecimal(columns[2].trim()),
            new BigDecimal(columns[3].trim()),
            new BigDecimal(columns[4].trim()),
            Integer.parseInt(columns[5].trim())
        );
    }
}
