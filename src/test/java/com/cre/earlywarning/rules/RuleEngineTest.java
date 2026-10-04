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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void throwsIllegalStateWhenNoEventExistsAtOrBeforeRequestedMonth() {
        loanRepository.save(new Loan("L901", "APARTMENT", new BigDecimal("10000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2030, 1, 1), new BigDecimal("800000.00")));
        eventLog.append("L901", YearMonth.of(2024, 6), new BigDecimal("10000000.00"),
            new BigDecimal("800000.00"), new BigDecimal("650000.00"), 0);

        assertThatThrownBy(() -> ruleEngine.evaluate("L901", YearMonth.of(2024, 1)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No event found for loan L901 at or before 2024-01");
    }

    @Test
    void r4FiresWhenDscrFallsAtLeastPoint15OverSixMonthsThroughTheRealPipeline() {
        loanRepository.save(new Loan("L902", "APARTMENT", new BigDecimal("20000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2032, 1, 1), new BigDecimal("1600000.00")));

        BigDecimal yearlyPayments = new BigDecimal("1000000.00");
        BigDecimal balance = new BigDecimal("20000000.00");
        String[] noiByMonth = {
            "1400000.00", // Jan: dscr 1.4000
            "1366667.00", // Feb
            "1333333.00", // Mar
            "1300000.00", // Apr
            "1266667.00", // May
            "1233333.00", // Jun
            "1200000.00"  // Jul: dscr 1.2000 -> fall of 0.2000 vs Jan
        };
        for (int i = 0; i < noiByMonth.length; i++) {
            eventLog.append("L902", YearMonth.of(2024, 1).plusMonths(i), balance,
                new BigDecimal(noiByMonth[i]), yearlyPayments, 0);
        }

        List<RuleEvaluation> evaluations = ruleEngine.evaluate("L902", YearMonth.of(2024, 7));

        RuleEvaluation r4 = evaluations.stream()
            .filter(e -> e.ruleId().equals("R4"))
            .findFirst()
            .orElseThrow();
        assertThat(r4.fired()).isTrue();
    }
}
