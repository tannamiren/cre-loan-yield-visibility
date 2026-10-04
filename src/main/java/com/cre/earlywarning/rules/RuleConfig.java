package com.cre.earlywarning.rules;

import java.math.BigDecimal;

public record RuleConfig(
    int version,
    int r1LatePaymentsThreshold,
    BigDecimal r2LowDscrThreshold,
    int r3MaturitySoonMonths,
    BigDecimal r4DscrFallDelta,
    int r4DscrFallMonths,
    BigDecimal r5LowDebtYieldThreshold,
    int r5MaturityMonths
) {
}
