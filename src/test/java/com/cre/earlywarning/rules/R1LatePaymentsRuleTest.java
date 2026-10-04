package com.cre.earlywarning.rules;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.metrics.LoanMetrics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class R1LatePaymentsRuleTest {

    private final RuleConfig config = new RuleConfig(1, 2, new BigDecimal("1.10"), 3,
        new BigDecimal("0.15"), 6, new BigDecimal("0.08"), 18);
    private final R1LatePaymentsRule rule = new R1LatePaymentsRule(config);

    @Test
    void firesWhenTwoOrMorePaymentsAreLate_matchingPlantedMissedPaymentsScenario() {
        // mirrors spec scenario 3: a loan goes 2 payments late, then later pays in full
        Loan loan = loan();
        LoanMetrics twoLate = metrics(YearMonth.of(2024, 6), 2);

        RuleEvaluation evaluation = rule.evaluate(new LoanMonthContext(loan, twoLate, List.of(twoLate)));

        assertThat(evaluation.fired()).isTrue();
        assertThat(evaluation.inputs()).containsEntry("paymentsLate", "2");
    }

    @Test
    void staysSilentWhenNoPaymentsAreLate() {
        Loan loan = loan();
        LoanMetrics current = metrics(YearMonth.of(2024, 7), 0);

        RuleEvaluation evaluation = rule.evaluate(new LoanMonthContext(loan, current, List.of(current)));

        assertThat(evaluation.fired()).isFalse();
    }

    private Loan loan() {
        return new Loan("L003", "APARTMENT", new BigDecimal("5000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2030, 1, 1), new BigDecimal("400000.00"));
    }

    private LoanMetrics metrics(YearMonth month, int paymentsLate) {
        return new LoanMetrics(month, new BigDecimal("5000000.00"), new BigDecimal("400000.00"),
            new BigDecimal("300000.00"), paymentsLate, new BigDecimal("1.3333"),
            new BigDecimal("0.0800"), 60L);
    }
}
