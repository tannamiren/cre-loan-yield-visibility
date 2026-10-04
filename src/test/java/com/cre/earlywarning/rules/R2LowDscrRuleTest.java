package com.cre.earlywarning.rules;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.metrics.LoanMetrics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class R2LowDscrRuleTest {

    private final RuleConfig config = new RuleConfig(1, 2, new BigDecimal("1.10"), 3,
        new BigDecimal("0.15"), 6, new BigDecimal("0.08"), 18);
    private final R2LowDscrRule rule = new R2LowDscrRule(config);

    @Test
    void firesWhenDscrBelow1_10() {
        Loan loan = loan();
        LoanMetrics current = metrics(new BigDecimal("1.0500"));

        RuleEvaluation evaluation = rule.evaluate(new LoanMonthContext(loan, current, List.of(current)));

        assertThat(evaluation.fired()).isTrue();
    }

    @Test
    void staysSilentWhenDscrAtOrAboveThreshold() {
        Loan loan = loan();
        LoanMetrics current = metrics(new BigDecimal("1.1000"));

        RuleEvaluation evaluation = rule.evaluate(new LoanMonthContext(loan, current, List.of(current)));

        assertThat(evaluation.fired()).isFalse();
    }

    private Loan loan() {
        return new Loan("L004", "OFFICE", new BigDecimal("8000000.00"),
            new BigDecimal("0.0600"), LocalDate.of(2030, 1, 1), new BigDecimal("600000.00"));
    }

    private LoanMetrics metrics(BigDecimal dscr) {
        return new LoanMetrics(YearMonth.of(2024, 5), new BigDecimal("8000000.00"),
            new BigDecimal("600000.00"), new BigDecimal("550000.00"), 0, dscr,
            new BigDecimal("0.0750"), 60L);
    }
}
