package com.cre.earlywarning.api;

import java.math.BigDecimal;
import java.util.List;

public record LoanDetailDto(
    String loanId, String propertyType, BigDecimal originalBalance, BigDecimal rate,
    String maturityDate, List<LoanHistoryPointDto> history, List<AlertDto> alerts
) {
}
