package com.cre.earlywarning.metrics;

import java.math.BigDecimal;
import java.time.YearMonth;

public record LoanMetrics(
    YearMonth month,
    BigDecimal balance,
    BigDecimal noi,
    BigDecimal yearlyPayments,
    int paymentsLate,
    BigDecimal dscr,
    BigDecimal debtYield,
    long monthsToMaturity
) {
}
