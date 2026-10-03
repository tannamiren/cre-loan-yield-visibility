package com.cre.earlywarning.domain;

import java.math.BigDecimal;
import java.time.YearMonth;

public interface EventLog {

    /**
     * Appends one loan-month report row. Returns true if this call inserted a new event,
     * false if an event already existed for this loanId+month (idempotent no-op).
     */
    boolean append(String loanId, YearMonth month, BigDecimal balance, BigDecimal noi,
                    BigDecimal yearlyPayments, int paymentsLate);
}
