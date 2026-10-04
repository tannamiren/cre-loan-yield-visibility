package com.cre.earlywarning.scenario;

import com.cre.earlywarning.alerts.Alert;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;

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

    // Regression test for the FINAL whole-branch review finding (CRITICAL): FolderPollerJob used
    // to process inbox CSVs in arbitrary filesystem order, and AlertService had no guard against
    // applying a stale/out-of-order evaluation for an earlier month after a later month's
    // evaluation had already been applied. In the slow-slide scenario, L001's DSCR falls from
    // 1.45 to 1.05 over months 0-11 (2024-01..2024-12) then holds flat at 1.05 for months 12-23
    // (2025-01..2025-12). Since R2's threshold is 1.10 and DSCR never climbs back above it once
    // it first crosses (around 2024-11), R2 fires every single month from the crossing onward and
    // must NEVER reach RESOLVED (RESOLVED requires 2 consecutive non-firing months, which never
    // happens here). Before the fix, ingesting the 24 months out of chronological order could
    // interleave an early (pre-crossing, non-firing) month's evaluation after a later firing
    // month's evaluation had already opened the alert, incrementing clearMonthsCount and
    // incorrectly RESOLVING it -- this is exactly what happened in Task 15's real manual
    // verification run. This test proves the fix: ingesting the same 24 months in shuffled order
    // yields the same final R2 state (OPEN) as ingesting them in chronological order.
    @Test
    void slowSlideScenario_R2StaysOpenRegardlessOfIngestOrder_shuffled() {
        List<YearMonth> shuffledMonths = new ArrayList<>(sortedMonths());
        Collections.shuffle(shuffledMonths, new Random(7));
        // Sanity check the shuffle actually produces a non-chronological order; otherwise this
        // test wouldn't be exercising the regression at all.
        assertThat(shuffledMonths).isNotEqualTo(sortedMonths());

        for (YearMonth month : shuffledMonths) {
            ingestService.ingest(rowFor("L001", month));
        }

        Alert r2 = alertRepository.findByLoanIdAndRuleId("L001", "R2").orElseThrow();
        assertThat(r2.getState().name()).isEqualTo("OPEN");
    }

    // Cross-check for the test above: chronological ingestion of the exact same 24 months must
    // produce the same final R2 state (OPEN) -- proving that ingest order no longer matters, which
    // is the actual property the fix establishes.
    @Test
    void slowSlideScenario_R2StaysOpenInChronologicalOrder() {
        for (YearMonth month : sortedMonths()) {
            ingestService.ingest(rowFor("L001", month));
        }

        Alert r2 = alertRepository.findByLoanIdAndRuleId("L001", "R2").orElseThrow();
        assertThat(r2.getState().name()).isEqualTo("OPEN");
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
