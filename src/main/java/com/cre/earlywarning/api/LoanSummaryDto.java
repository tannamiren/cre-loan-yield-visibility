package com.cre.earlywarning.api;

import java.math.BigDecimal;
import java.util.List;

public record LoanSummaryDto(
    String loanId, String propertyType, BigDecimal originalBalance, BigDecimal rate,
    String maturityDate, BigDecimal latestDscr, BigDecimal latestDebtYield,
    List<LoanHistoryPointDto> history
) {
}
