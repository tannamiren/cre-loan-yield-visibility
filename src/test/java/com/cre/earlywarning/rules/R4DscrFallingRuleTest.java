package com.cre.earlywarning.rules;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.metrics.LoanMetrics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class R4DscrFallingRuleTest {

    private final RuleConfig config = new RuleConfig(1, 2, new BigDecimal("1.10"), 3,
        new BigDecimal("0.15"), 6, new BigDecimal("0.08"), 18);
    private final R4DscrFallingRule rule = new R4DscrFallingRule(config);

    @Test
    void firesWhenDscrFellAtLeastPoint15OverSixMonths() {
        Loan loan = loan();
        LoanMetrics sixMonthsAgo = metrics(YearMonth.of(2024, 1), new BigDecimal("1.4000"));
        LoanMetrics current = metrics(YearMonth.of(2024, 7), new BigDecimal("1.2000"));

        RuleEvaluation evaluation = rule.evaluate(
            new LoanMonthContext(loan, current, List.of(sixMonthsAgo, current)));

        assertThat(evaluation.fired()).isTrue();
        assertThat(evaluation.inputs()).containsEntry("fall", "0.2000");
    }

    @Test
    void staysSilentWhenFallIsSmallerThanDelta() {
        Loan loan = loan();
        LoanMetrics sixMonthsAgo = metrics(YearMonth.of(2024, 1), new BigDecimal("1.3000"));
        LoanMetrics current = metrics(YearMonth.of(2024, 7), new BigDecimal("1.2000"));

        RuleEvaluation evaluation = rule.evaluate(
            new LoanMonthContext(loan, current, List.of(sixMonthsAgo, current)));

        assertThat(evaluation.fired()).isFalse();
    }

    @Test
    void staysSilentWhenNoSixMonthHistoryExists() {
        Loan loan = loan();
        LoanMetrics current = metrics(YearMonth.of(2024, 2), new BigDecimal("1.2000"));

        RuleEvaluation evaluation = rule.evaluate(new LoanMonthContext(loan, current, List.of(current)));

        assertThat(evaluation.fired()).isFalse();
        assertThat(evaluation.inputs()).containsEntry("reason", "insufficient history");
    }

    private Loan loan() {
        return new Loan("L001", "APARTMENT", new BigDecimal("20000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2030, 1, 1), new BigDecimal("1600000.00"));
    }

    private LoanMetrics metrics(YearMonth month, BigDecimal dscr) {
        return new LoanMetrics(month, new BigDecimal("20000000.00"), new BigDecimal("1280000.00"),
            new BigDecimal("1280000.00"), 0, dscr, new BigDecimal("0.0640"), 60L);
    }
}
