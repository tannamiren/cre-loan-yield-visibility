package com.cre.earlywarning.rules;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.metrics.LoanMetrics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class R3MaturitySoonRuleTest {

    private final RuleConfig config = new RuleConfig(1, 2, new BigDecimal("1.10"), 3,
        new BigDecimal("0.15"), 6, new BigDecimal("0.08"), 18);
    private final R3MaturitySoonRule rule = new R3MaturitySoonRule(config);

    @Test
    void firesWhenMaturityIsWithinThreeMonths() {
        Loan loan = loan();
        LoanMetrics current = metrics(2L);

        RuleEvaluation evaluation = rule.evaluate(new LoanMonthContext(loan, current, List.of(current)));

        assertThat(evaluation.fired()).isTrue();
    }

    @Test
    void staysSilentWhenMaturityIsFourMonthsOut() {
        Loan loan = loan();
        LoanMetrics current = metrics(4L);

        RuleEvaluation evaluation = rule.evaluate(new LoanMonthContext(loan, current, List.of(current)));

        assertThat(evaluation.fired()).isFalse();
    }

    private Loan loan() {
        return new Loan("L005", "RETAIL", new BigDecimal("4000000.00"),
            new BigDecimal("0.0575"), LocalDate.of(2024, 9, 1), new BigDecimal("350000.00"));
    }

    private LoanMetrics metrics(long monthsToMaturity) {
        return new LoanMetrics(YearMonth.of(2024, 7), new BigDecimal("4000000.00"),
            new BigDecimal("350000.00"), new BigDecimal("280000.00"), 0, new BigDecimal("1.2500"),
            new BigDecimal("0.0875"), monthsToMaturity);
    }
}
