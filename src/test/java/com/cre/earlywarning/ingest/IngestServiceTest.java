package com.cre.earlywarning.ingest;

import com.cre.earlywarning.alerts.AlertRepository;
import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanMonthEventRepository;
import com.cre.earlywarning.domain.LoanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class IngestServiceTest {

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private LoanMonthEventRepository eventRepository;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private IngestService ingestService;

    @Test
    void ingestingANewRowWritesAnEventAndEvaluatesRules() {
        loanRepository.save(new Loan("L800", "APARTMENT", new BigDecimal("5000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2030, 1, 1), new BigDecimal("400000.00")));
        CsvRow row = new CsvRow("L800", YearMonth.of(2024, 1), new BigDecimal("5000000.00"),
            new BigDecimal("400000.00"), new BigDecimal("600000.00"), 2);

        boolean inserted = ingestService.ingest(row);

        assertThat(inserted).isTrue();
        assertThat(eventRepository.findByLoanIdOrderByMonthAsc("L800")).hasSize(1);
        // DSCR = 400000/600000 = 0.6667, which fires R2; paymentsLate=2 fires R1
        assertThat(alertRepository.findByLoanId("L800")).hasSize(2);
    }

    @Test
    void reingestingTheSameRowIsANoOpAndDoesNotChangeAlerts() {
        loanRepository.save(new Loan("L801", "APARTMENT", new BigDecimal("5000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2030, 1, 1), new BigDecimal("400000.00")));
        CsvRow row = new CsvRow("L801", YearMonth.of(2024, 1), new BigDecimal("5000000.00"),
            new BigDecimal("400000.00"), new BigDecimal("600000.00"), 2);

        ingestService.ingest(row);
        int alertCountAfterFirst = alertRepository.findByLoanId("L801").size();
        boolean secondInsert = ingestService.ingest(row);
        int alertCountAfterSecond = alertRepository.findByLoanId("L801").size();

        assertThat(secondInsert).isFalse();
        assertThat(alertCountAfterSecond).isEqualTo(alertCountAfterFirst);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void ingestRollsBackTheEventWhenRuleEvaluationFailsForAnUnknownLoan() {
        // This test deliberately suspends the class-level @Transactional wrapper (which would
        // otherwise nest everything in one outer transaction that only rolls back at the very end
        // of the test, masking the behavior under test). With no outer transaction active here,
        // IngestService.ingest()'s own @Transactional boundary owns a real transaction against the
        // DB: when RuleEngine.evaluate throws IllegalStateException("Unknown loan ...") because
        // loan "L802" was never saved, Spring must physically roll back the event insert performed
        // moments earlier by EventLog.append. Without @Transactional on ingest(), that insert would
        // already be durably committed by the time the exception is thrown, permanently stranding
        // this loan-month (future retries would see EventLog.append return false and silently skip
        // it forever).
        CsvRow row = new CsvRow("L802", YearMonth.of(2024, 1), new BigDecimal("5000000.00"),
            new BigDecimal("400000.00"), new BigDecimal("600000.00"), 2);

        assertThatThrownBy(() -> ingestService.ingest(row))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Unknown loan");

        assertThat(eventRepository.findByLoanIdOrderByMonthAsc("L802")).isEmpty();
    }
}
