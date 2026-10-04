package com.cre.earlywarning.alerts;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanRepository;
import com.cre.earlywarning.rules.RuleType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ScoreCalculatorTest {

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private ScoreCalculator scoreCalculator;

    @Test
    void creditRuleWithinSixMonthsOnLargestLoanScoresMaximum() {
        loanRepository.save(new Loan("SMALL1", "RETAIL", new BigDecimal("1000000.00"),
            new BigDecimal("0.0600"), LocalDate.of(2030, 1, 1), new BigDecimal("90000.00")));
        loanRepository.save(new Loan("BIG1", "OFFICE", new BigDecimal("10000000.00"),
            new BigDecimal("0.0600"), LocalDate.of(2030, 1, 1), new BigDecimal("900000.00")));

        Score score = scoreCalculator.compute(RuleType.CREDIT, 5L, new BigDecimal("10000000.00"));

        assertThat(score.typeScore()).isEqualTo(40);
        assertThat(score.timeScore()).isEqualTo(30);
        assertThat(score.sizeScore()).isEqualTo(20);
        assertThat(score.total()).isEqualTo(90);
    }

    @Test
    void earlyWarningRuleFarFromMaturityOnSmallLoanScoresLow() {
        loanRepository.save(new Loan("SMALL2", "RETAIL", new BigDecimal("1000000.00"),
            new BigDecimal("0.0600"), LocalDate.of(2030, 1, 1), new BigDecimal("90000.00")));
        loanRepository.save(new Loan("BIG2", "OFFICE", new BigDecimal("10000000.00"),
            new BigDecimal("0.0600"), LocalDate.of(2030, 1, 1), new BigDecimal("900000.00")));

        Score score = scoreCalculator.compute(RuleType.EARLY_WARNING, 24L, new BigDecimal("1000000.00"));

        assertThat(score.typeScore()).isEqualTo(25);
        assertThat(score.timeScore()).isEqualTo(0);
        assertThat(score.sizeScore()).isEqualTo(2);
        assertThat(score.total()).isEqualTo(27);
    }
}
