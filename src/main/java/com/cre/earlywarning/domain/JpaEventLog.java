package com.cre.earlywarning.domain;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;

@Component
public class JpaEventLog implements EventLog {

    private final LoanMonthEventRepository repository;

    public JpaEventLog(LoanMonthEventRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean append(String loanId, YearMonth month, BigDecimal balance, BigDecimal noi,
                           BigDecimal yearlyPayments, int paymentsLate) {
        String monthKey = month.toString();
        if (repository.existsByLoanIdAndMonth(loanId, monthKey)) {
            return false;
        }
        LoanMonthEvent event = new LoanMonthEvent(null, loanId, monthKey, balance, noi,
            yearlyPayments, paymentsLate, Instant.now());
        try {
            repository.save(event);
            return true;
        } catch (DataIntegrityViolationException raceLoserUniqueConstraintViolation) {
            // another writer inserted the same loanId+month between our existsBy check and save;
            // the unique constraint is the real idempotency guarantee, this check is just the fast path
            return false;
        }
    }
}
