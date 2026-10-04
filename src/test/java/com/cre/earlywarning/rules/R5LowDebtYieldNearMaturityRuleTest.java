package com.cre.earlywarning.rules;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.metrics.LoanMetrics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class R5LowDebtYieldNearMaturityRuleTest {

    private final RuleConfig config = new RuleConfig(1, 2, new BigDecimal("1.10"), 3,
        new BigDecimal("0.15"), 6, new BigDecimal("0.08"), 18);
    private final R5LowDebtYieldNearMaturityRule rule = new R5LowDebtYieldNearMaturityRule(config);

    @Test
    void firesWhenDebtYieldBelow8PercentAndMaturityWithin18Months_matchingWeakRefinanceScenario() {
        Loan loan = loan();
        LoanMetrics current = metrics(new BigDecimal("0.0700"), 9L);

        RuleEvaluation evaluation = rule.evaluate(new LoanMonthContext(loan, current, List.of(current)));

        assertThat(evaluation.fired()).isTrue();
    }

    @Test
    void staysSilentWhenDebtYieldIsHealthyEvenIfMaturityIsSoon() {
        Loan loan = loan();
        LoanMetrics current = metrics(new BigDecimal("0.1000"), 9L);

        RuleEvaluation evaluation = rule.evaluate(new LoanMonthContext(loan, current, List.of(current)));

        assertThat(evaluation.fired()).isFalse();
    }

    @Test
    void staysSilentWhenDebtYieldIsLowButMaturityIsFarOut() {
        Loan loan = loan();
        LoanMetrics current = metrics(new BigDecimal("0.0700"), 24L);

        RuleEvaluation evaluation = rule.evaluate(new LoanMonthContext(loan, current, List.of(current)));

        assertThat(evaluation.fired()).isFalse();
    }

    private Loan loan() {
        return new Loan("L002", "OFFICE", new BigDecimal("15000000.00"),
            new BigDecimal("0.0625"), LocalDate.of(2025, 1, 1), new BigDecimal("1100000.00"));
    }

    private LoanMetrics metrics(BigDecimal debtYield, long monthsToMaturity) {
        return new LoanMetrics(YearMonth.of(2024, 4), new BigDecimal("15000000.00"),
            new BigDecimal("1050000.00"), new BigDecimal("1050000.00"), 0, new BigDecimal("1.3000"),
            debtYield, monthsToMaturity);
    }
}
