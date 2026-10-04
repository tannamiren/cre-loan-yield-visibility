package com.cre.earlywarning.scenario;

import com.cre.earlywarning.alerts.AlertRepository;
import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanRepository;
import com.cre.earlywarning.generator.GeneratedData;
import com.cre.earlywarning.generator.GeneratedLoan;
import com.cre.earlywarning.generator.LoanDataGenerator;
import com.cre.earlywarning.ingest.CsvRow;
import com.cre.earlywarning.ingest.IngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PlantedScenarioTest {

    private static final long SEED = 42L;

    @Autowired
    private LoanDataGenerator generator;

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private IngestService ingestService;

    @Autowired
    private AlertRepository alertRepository;

    private GeneratedData data;

    @BeforeEach
    void seedLoans() {
        data = generator.generate(SEED);
        for (GeneratedLoan loan : data.loans()) {
            loanRepository.save(new Loan(loan.loanId(), loan.propertyType(), loan.originalBalance(),
                loan.rate(), loan.maturityDate(), loan.underwritingNoi()));
        }
    }

    @Test
    void slowSlideScenario_R4FiresAtLeastThreeMonthsBeforeR2() {
        YearMonth r4FiredAt = null;
        YearMonth r2FiredAt = null;

        for (YearMonth month : sortedMonths()) {
            CsvRow row = rowFor("L001", month);
            ingestService.ingest(row);

            if (r4FiredAt == null && alertRepository.findByLoanIdAndRuleId("L001", "R4").isPresent()) {
                r4FiredAt = month;
            }
            if (r2FiredAt == null && alertRepository.findByLoanIdAndRuleId("L001", "R2").isPresent()) {
                r2FiredAt = month;
            }
        }

        assertThat(r4FiredAt).isNotNull();
        assertThat(r2FiredAt).isNotNull();
        long monthsEarly = java.time.temporal.ChronoUnit.MONTHS.between(r4FiredAt, r2FiredAt);
        assertThat(monthsEarly).isGreaterThanOrEqualTo(3);
    }

    @Test
    void weakRefinanceScenario_R5FiresAndR2StaysSilent() {
        for (YearMonth month : sortedMonths()) {
            ingestService.ingest(rowFor("L002", month));
        }

        assertThat(alertRepository.findByLoanIdAndRuleId("L002", "R5")).isPresent();
        assertThat(alertRepository.findByLoanIdAndRuleId("L002", "R2")).isEmpty();
    }

    @Test
    void missedPaymentsScenario_R1FiresThenResolves() {
        for (YearMonth month : sortedMonths()) {
            ingestService.ingest(rowFor("L003", month));
        }

        var alert = alertRepository.findByLoanIdAndRuleId("L003", "R1").orElseThrow();
        assertThat(alert.getState().name()).isEqualTo("RESOLVED");
    }

    private List<YearMonth> sortedMonths() {
        return data.rowsByMonth().keySet().stream().sorted().toList();
    }

    private CsvRow rowFor(String loanId, YearMonth month) {
        Optional<CsvRow> row = data.rowsByMonth().get(month).stream()
            .filter(r -> r.loanId().equals(loanId)).findFirst();
        return row.orElseThrow();
    }
}
