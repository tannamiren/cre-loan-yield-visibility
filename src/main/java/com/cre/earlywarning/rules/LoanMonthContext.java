package com.cre.earlywarning.rules;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.metrics.LoanMetrics;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

public record LoanMonthContext(Loan loan, LoanMetrics current, List<LoanMetrics> history) {

    public Optional<LoanMetrics> monthsAgo(int n) {
        YearMonth target = current.month().minusMonths(n);
        return history.stream().filter(m -> m.month().equals(target)).findFirst();
    }
}
