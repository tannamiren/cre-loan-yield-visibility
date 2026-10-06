package com.cre.earlywarning.rules;

import com.cre.earlywarning.domain.EventLog;
import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class RuleEngineTest {

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private EventLog eventLog;

    @Autowired
    private RuleEngine ruleEngine;

    @Test
    void evaluatesAllFiveRulesForTheRequestedMonth() {
        loanRepository.save(new Loan("L900", "APARTMENT", new BigDecimal("10000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2030, 1, 1), new BigDecimal("800000.00")));
        eventLog.append("L900", YearMonth.of(2024, 1), new BigDecimal("10000000.00"),
            new BigDecimal("800000.00"), new BigDecimal("650000.00"), 0);

        List<RuleEvaluation> evaluations = ruleEngine.evaluate("L900", YearMonth.of(2024, 1));

        assertThat(evaluations).extracting(RuleEvaluation::ruleId)
            .containsExactlyInAnyOrder("R1", "R2", "R3", "R4", "R5");
        assertThat(evaluations).allMatch(e -> !e.fired());
    }

    @Test
    void r4FiresThroughTheRealPipelineWhenDscrFallsOverSixRealIngestedMonths() {
        // Regression test for the RuleEngine lookback: a hand-built LoanMonthContext (as in
        // R4DscrFallingRuleTest) can hide bugs in how the engine assembles history from the
        // actual event log. This ingests 7 consecutive real months and lets RuleEngine build
        // its own history, so it exercises the same path IngestService/FolderPollerJob use.
        loanRepository.save(new Loan("L950", "APARTMENT", new BigDecimal("20000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2030, 1, 1), new BigDecimal("1600000.00")));

        BigDecimal balance = new BigDecimal("20000000.00");
        BigDecimal yearlyPayments = new BigDecimal("1000000.00");
        YearMonth[] months = {
            YearMonth.of(2024, 1), YearMonth.of(2024, 2), YearMonth.of(2024, 3),
            YearMonth.of(2024, 4), YearMonth.of(2024, 5), YearMonth.of(2024, 6),
            YearMonth.of(2024, 7)
        };
        BigDecimal[] noiByMonth = {
            new BigDecimal("1400000.00"), new BigDecimal("1350000.00"), new BigDecimal("1300000.00"),
            new BigDecimal("1280000.00"), new BigDecimal("1250000.00"), new BigDecimal("1220000.00"),
            new BigDecimal("1200000.00")
        };
        for (int i = 0; i < months.length; i++) {
            eventLog.append("L950", months[i], balance, noiByMonth[i], yearlyPayments, 0);
        }

        List<RuleEvaluation> evaluations = ruleEngine.evaluate("L950", YearMonth.of(2024, 7));

        RuleEvaluation r4 = evaluations.stream()
            .filter(e -> e.ruleId().equals("R4"))
            .findFirst()
            .orElseThrow();
        assertThat(r4.fired()).isTrue();
        assertThat(r4.inputs()).containsEntry("fall", "0.2000");
    }
}
