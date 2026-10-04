package com.cre.earlywarning.generator;

import java.math.BigDecimal;
import java.time.LocalDate;

public record GeneratedLoan(
    String loanId,
    String propertyType,
    BigDecimal originalBalance,
    BigDecimal rate,
    LocalDate maturityDate,
    BigDecimal underwritingNoi
) {
}
