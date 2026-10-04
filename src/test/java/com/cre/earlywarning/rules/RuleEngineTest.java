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
}
