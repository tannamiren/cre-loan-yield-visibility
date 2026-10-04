package com.cre.earlywarning.metrics;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanMonthEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsCalculatorTest {

    private final MetricsCalculator calculator = new MetricsCalculator();

    @Test
    void computesDscrAndDebtYieldRoundedToFourDecimalPlaces() {
        Loan loan = new Loan("L001", "APARTMENT", new BigDecimal("25000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2030, 1, 1), new BigDecimal("2000000.00"));
        LoanMonthEvent event = new LoanMonthEvent(1L, "L001", "2024-01",
            new BigDecimal("25000000.00"), new BigDecimal("2000000.00"),
            new BigDecimal("1600000.00"), 0, Instant.now());

        LoanMetrics metrics = calculator.calculate(loan, event);

        assertThat(metrics.dscr()).isEqualByComparingTo("1.2500");
        assertThat(metrics.debtYield()).isEqualByComparingTo("0.0800");
    }

    @Test
    void computesMonthsToMaturityFromReportMonthToMaturityMonth() {
        Loan loan = new Loan("L002", "OFFICE", new BigDecimal("10000000.00"),
            new BigDecimal("0.0600"), LocalDate.of(2024, 10, 15), new BigDecimal("900000.00"));
        LoanMonthEvent event = new LoanMonthEvent(2L, "L002", "2024-01",
            new BigDecimal("10000000.00"), new BigDecimal("900000.00"),
            new BigDecimal("700000.00"), 0, Instant.now());

        LoanMetrics metrics = calculator.calculate(loan, event);

        // 2024-01-01 to 2024-10-01 is 9 whole months
        assertThat(metrics.monthsToMaturity()).isEqualTo(9L);
        assertThat(metrics.month()).isEqualTo(YearMonth.of(2024, 1));
    }

    @Test
    void roundsNonTerminatingDivisionHalfUp() {
        Loan loan = new Loan("L003", "RETAIL", new BigDecimal("3000000.00"),
            new BigDecimal("0.0650"), LocalDate.of(2030, 1, 1), new BigDecimal("300000.00"));
        LoanMonthEvent event = new LoanMonthEvent(3L, "L003", "2024-06",
            new BigDecimal("3000000.00"), new BigDecimal("100000.00"),
            new BigDecimal("300000.00"), 0, Instant.now());

        LoanMetrics metrics = calculator.calculate(loan, event);

        // 100000 / 300000 = 0.33333... -> 0.3333
        assertThat(metrics.dscr()).isEqualByComparingTo("0.3333");
    }
}
