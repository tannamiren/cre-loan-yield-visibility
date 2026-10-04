package com.cre.earlywarning.api;

import java.math.BigDecimal;

public record LoanHistoryPointDto(String month, BigDecimal dscr, BigDecimal debtYield) {
}
