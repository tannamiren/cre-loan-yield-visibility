package com.cre.earlywarning.alerts;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanRepository;
import com.cre.earlywarning.rules.RuleEvaluation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class AlertService {

    private final AlertRepository alertRepository;
    private final LoanRepository loanRepository;
    private final ScoreCalculator scoreCalculator;
    private final ObjectMapper objectMapper;

    public AlertService(AlertRepository alertRepository, LoanRepository loanRepository,
                         ScoreCalculator scoreCalculator, ObjectMapper objectMapper) {
        this.alertRepository = alertRepository;
        this.loanRepository = loanRepository;
        this.scoreCalculator = scoreCalculator;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void applyRuleEvaluations(String loanId, YearMonth month, List<RuleEvaluation> evaluations) {
        Loan loan = loanRepository.findById(loanId)
            .orElseThrow(() -> new IllegalStateException("Unknown loan " + loanId));

        for (RuleEvaluation evaluation : evaluations) {
            Optional<Alert> existing = alertRepository.findByLoanIdAndRuleId(loanId, evaluation.ruleId());

            if (existing.isPresent() && YearMonth.parse(existing.get().getFiredMonth()).isAfter(month)) {
                // Stale/out-of-order evaluation for a month earlier than what's already recorded
                // as this alert's most-recently-applied month; do not let it regress state that a
                // chronologically later month already correctly set (fired or clear-month-count).
                continue;
            }

            if (evaluation.fired()) {
                Alert alert = existing.orElseGet(() -> new Alert(loanId, evaluation.ruleId()));
                alert.setState(AlertState.OPEN);
                alert.setClearMonthsCount(0);
                alert.setRuleVersion(evaluation.ruleVersion());
                alert.setFiredMonth(month.toString());
                alert.setLimitValue(evaluation.limit());
                alert.setInputsJson(writeInputs(evaluation));

                long monthsToMaturity = monthsToMaturityAt(loan, month);
                Score score = scoreCalculator.compute(evaluation.type(), monthsToMaturity, loan.getOriginalBalance());
                alert.setScoreType(score.typeScore());
                alert.setScoreTime(score.timeScore());
                alert.setScoreSize(score.sizeScore());
                alert.setScore(score.total());
                alert.setUpdatedAt(Instant.now());

                alertRepository.save(alert);
            } else if (existing.isPresent() && existing.get().getState() != AlertState.RESOLVED) {
                Alert alert = existing.get();
                alert.setClearMonthsCount(alert.getClearMonthsCount() + 1);
                if (alert.getClearMonthsCount() >= 2) {
                    alert.setState(AlertState.RESOLVED);
                }
                alert.setUpdatedAt(Instant.now());
                alertRepository.save(alert);
            }
        }
    }

    @Transactional
    public void acknowledge(Long alertId) {
        Alert alert = alertRepository.findById(alertId)
            .orElseThrow(() -> new NoSuchElementException("Alert " + alertId + " not found"));
        alert.setState(AlertState.ACKNOWLEDGED);
        alert.setUpdatedAt(Instant.now());
        alertRepository.save(alert);
    }

    public List<Alert> openQueue() {
        return alertRepository.findByStateOrderByScoreDesc(AlertState.OPEN);
    }

    private long monthsToMaturityAt(Loan loan, YearMonth month) {
        return ChronoUnit.MONTHS.between(month.atDay(1), loan.getMaturityDate().withDayOfMonth(1));
    }

    private String writeInputs(RuleEvaluation evaluation) {
        try {
            return objectMapper.writeValueAsString(evaluation.inputs());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize rule inputs for " + evaluation.ruleId(), e);
        }
    }
}
