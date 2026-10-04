package com.cre.earlywarning.api;

import com.cre.earlywarning.alerts.AlertRepository;
import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanMonthEventRepository;
import com.cre.earlywarning.domain.LoanRepository;
import com.cre.earlywarning.metrics.MetricsCalculator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;

@Service
public class LoanQueryService {

    private final LoanRepository loanRepository;
    private final LoanMonthEventRepository eventRepository;
    private final AlertRepository alertRepository;
    private final MetricsCalculator metricsCalculator;
    private final ObjectMapper objectMapper;

    public LoanQueryService(LoanRepository loanRepository, LoanMonthEventRepository eventRepository,
                             AlertRepository alertRepository, MetricsCalculator metricsCalculator,
                             ObjectMapper objectMapper) {
        this.loanRepository = loanRepository;
        this.eventRepository = eventRepository;
        this.alertRepository = alertRepository;
        this.metricsCalculator = metricsCalculator;
        this.objectMapper = objectMapper;
    }

    public LoanDetailDto getLoanDetail(String loanId) {
        Loan loan = loanRepository.findById(loanId)
            .orElseThrow(() -> new NoSuchElementException("Loan " + loanId + " not found"));

        var history = eventRepository.findByLoanIdOrderByMonthAsc(loanId).stream()
            .map(e -> metricsCalculator.calculate(loan, e))
            .map(m -> new LoanHistoryPointDto(m.month().toString(), m.dscr(), m.debtYield()))
            .toList();

        var alerts = alertRepository.findByLoanId(loanId).stream()
            .map(a -> AlertDto.from(a, objectMapper))
            .toList();

        return new LoanDetailDto(loan.getLoanId(), loan.getPropertyType(), loan.getOriginalBalance(),
            loan.getRate(), loan.getMaturityDate().toString(), history, alerts);
    }
}
