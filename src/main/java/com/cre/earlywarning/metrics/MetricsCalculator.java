package com.cre.earlywarning.metrics;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanMonthEvent;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

@Component
public class MetricsCalculator {

    public LoanMetrics calculate(Loan loan, LoanMonthEvent event) {
        YearMonth month = YearMonth.parse(event.getMonth());
        BigDecimal dscr = event.getNoi().divide(event.getYearlyPayments(), 4, RoundingMode.HALF_UP);
        BigDecimal debtYield = event.getNoi().divide(event.getBalance(), 4, RoundingMode.HALF_UP);
        long monthsToMaturity = ChronoUnit.MONTHS.between(
            month.atDay(1), loan.getMaturityDate().withDayOfMonth(1));

        return new LoanMetrics(month, event.getBalance(), event.getNoi(), event.getYearlyPayments(),
            event.getPaymentsLate(), dscr, debtYield, monthsToMaturity);
    }
}
