package com.cre.earlywarning.alerts;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanRepository;
import com.cre.earlywarning.rules.RuleEvaluation;
import com.cre.earlywarning.rules.RuleType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class AlertServiceTest {

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private AlertService alertService;

    @Test
    void firingCreatesOneOpenAlertWithAuditTrail() {
        loanRepository.save(loan("L700"));

        apply("L700", YearMonth.of(2024, 1), fired("R2", RuleType.CREDIT, "1.10"));

        Alert alert = alertRepository.findByLoanIdAndRuleId("L700", "R2").orElseThrow();
        assertThat(alert.getState()).isEqualTo(AlertState.OPEN);
        assertThat(alert.getRuleVersion()).isEqualTo(1);
        assertThat(alert.getFiredMonth()).isEqualTo("2024-01");
        assertThat(alert.getLimitValue()).isEqualTo("1.10");
        assertThat(alert.getInputsJson()).contains("dscr");
        assertThat(alert.getScore()).isGreaterThan(0);
    }

    @Test
    void repeatedFiringUpdatesTheSameAlertInstead_ofCreatingADuplicate() {
        loanRepository.save(loan("L701"));

        apply("L701", YearMonth.of(2024, 1), fired("R2", RuleType.CREDIT, "1.10"));
        apply("L701", YearMonth.of(2024, 2), fired("R2", RuleType.CREDIT, "1.10"));

        assertThat(alertRepository.findByLoanId("L701")).hasSize(1);
        assertThat(alertRepository.findByLoanIdAndRuleId("L701", "R2").orElseThrow().getFiredMonth())
            .isEqualTo("2024-02");
    }

    @Test
    void resolvesAfterTwoConsecutiveClearMonths_matchingMissedPaymentsScenario() {
        loanRepository.save(loan("L702"));

        apply("L702", YearMonth.of(2024, 6), fired("R1", RuleType.CREDIT, "2"));
        apply("L702", YearMonth.of(2024, 7), notFired("R1", RuleType.CREDIT, "2"));
        Alert afterOneClearMonth = alertRepository.findByLoanIdAndRuleId("L702", "R1").orElseThrow();
        assertThat(afterOneClearMonth.getState()).isEqualTo(AlertState.OPEN);

        apply("L702", YearMonth.of(2024, 8), notFired("R1", RuleType.CREDIT, "2"));
        Alert afterTwoClearMonths = alertRepository.findByLoanIdAndRuleId("L702", "R1").orElseThrow();
        assertThat(afterTwoClearMonths.getState()).isEqualTo(AlertState.RESOLVED);
    }

    @Test
    void acknowledgingAnAlertSetsItToAcknowledged() {
        loanRepository.save(loan("L703"));
        apply("L703", YearMonth.of(2024, 1), fired("R2", RuleType.CREDIT, "1.10"));
        Alert alert = alertRepository.findByLoanIdAndRuleId("L703", "R2").orElseThrow();

        alertService.acknowledge(alert.getId());

        assertThat(alertRepository.findById(alert.getId()).orElseThrow().getState())
            .isEqualTo(AlertState.ACKNOWLEDGED);
    }

    @Test
    void reFiringAnAcknowledgedAlertReopensItToOpen() {
        loanRepository.save(loan("L706"));
        apply("L706", YearMonth.of(2024, 1), fired("R2", RuleType.CREDIT, "1.10"));
        Alert alert = alertRepository.findByLoanIdAndRuleId("L706", "R2").orElseThrow();
        alertService.acknowledge(alert.getId());
        assertThat(alertRepository.findById(alert.getId()).orElseThrow().getState())
            .isEqualTo(AlertState.ACKNOWLEDGED);

        apply("L706", YearMonth.of(2024, 2), fired("R2", RuleType.CREDIT, "1.10"));

        Alert reopened = alertRepository.findByLoanIdAndRuleId("L706", "R2").orElseThrow();
        assertThat(reopened.getState()).isEqualTo(AlertState.OPEN);
        assertThat(reopened.getFiredMonth()).isEqualTo("2024-02");
    }

    @Test
    void openQueueOnlyReturnsOpenAlertsRankedByScoreDescending() {
        loanRepository.save(loan("L704"));
        loanRepository.save(loan("L705"));
        apply("L704", YearMonth.of(2024, 1), fired("R1", RuleType.CREDIT, "2"));
        apply("L705", YearMonth.of(2024, 1), fired("R4", RuleType.EARLY_WARNING, "0.15"));
        Alert acknowledged = alertRepository.findByLoanIdAndRuleId("L704", "R1").orElseThrow();
        alertService.acknowledge(acknowledged.getId());

        List<Alert> queue = alertService.openQueue();

        assertThat(queue).extracting(Alert::getLoanId).containsExactly("L705");
    }

    private void apply(String loanId, YearMonth month, RuleEvaluation evaluation) {
        alertService.applyRuleEvaluations(loanId, month, List.of(evaluation));
    }

    private RuleEvaluation fired(String ruleId, RuleType type, String limit) {
        return new RuleEvaluation(ruleId, type, true, Map.of("dscr", "test"), limit, 1);
    }

    private RuleEvaluation notFired(String ruleId, RuleType type, String limit) {
        return new RuleEvaluation(ruleId, type, false, Map.of(), limit, 1);
    }

    private Loan loan(String loanId) {
        return new Loan(loanId, "APARTMENT", new BigDecimal("5000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2030, 1, 1), new BigDecimal("400000.00"));
    }
}
