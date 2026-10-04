package com.cre.earlywarning.generator;

import com.cre.earlywarning.ingest.CsvRow;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

public record GeneratedData(List<GeneratedLoan> loans, Map<YearMonth, List<CsvRow>> rowsByMonth) {
}
