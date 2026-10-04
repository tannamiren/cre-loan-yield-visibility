package com.cre.earlywarning.rules;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanMonthEvent;
import com.cre.earlywarning.domain.LoanMonthEventRepository;
import com.cre.earlywarning.domain.LoanRepository;
import com.cre.earlywarning.metrics.LoanMetrics;
import com.cre.earlywarning.metrics.MetricsCalculator;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;

@Component
public class RuleEngine {

    private final List<Rule> rules;
    private final LoanRepository loanRepository;
    private final LoanMonthEventRepository eventRepository;
    private final MetricsCalculator metricsCalculator;

    public RuleEngine(List<Rule> rules, LoanRepository loanRepository,
                       LoanMonthEventRepository eventRepository, MetricsCalculator metricsCalculator) {
        this.rules = rules;
        this.loanRepository = loanRepository;
        this.eventRepository = eventRepository;
        this.metricsCalculator = metricsCalculator;
    }

    public List<RuleEvaluation> evaluate(String loanId, YearMonth month) {
        Loan loan = loanRepository.findById(loanId)
            .orElseThrow(() -> new IllegalStateException("Unknown loan " + loanId));

        List<LoanMonthEvent> events = eventRepository.findByLoanIdOrderByMonthAsc(loanId);

        List<LoanMetrics> history = events.stream()
            .map(e -> metricsCalculator.calculate(loan, e))
            .filter(m -> !m.month().isAfter(month))
            .sorted(Comparator.comparing(LoanMetrics::month))
            .toList();

        LoanMetrics current = history.get(history.size() - 1);
        LoanMonthContext context = new LoanMonthContext(loan, current, history);

        return rules.stream().map(rule -> rule.evaluate(context)).toList();
    }
}
