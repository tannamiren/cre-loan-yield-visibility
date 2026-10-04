package com.cre.earlywarning.ingest;

import java.math.BigDecimal;
import java.time.YearMonth;

public record CsvRow(
    String loanId,
    YearMonth month,
    BigDecimal balance,
    BigDecimal noi,
    BigDecimal yearlyPayments,
    int paymentsLate
) {
}
