package com.cre.earlywarning.ingest;

import com.cre.earlywarning.alerts.AlertService;
import com.cre.earlywarning.domain.EventLog;
import com.cre.earlywarning.rules.RuleEngine;
import com.cre.earlywarning.rules.RuleEvaluation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class IngestService {

    private final EventLog eventLog;
    private final RuleEngine ruleEngine;
    private final AlertService alertService;

    public IngestService(EventLog eventLog, RuleEngine ruleEngine, AlertService alertService) {
        this.eventLog = eventLog;
        this.ruleEngine = ruleEngine;
        this.alertService = alertService;
    }

    /**
     * Returns true if this row was newly ingested, false if it was a duplicate (loanId+month
     * already in the event log) and therefore a no-op — rules are only re-evaluated on new events.
     */
    @Transactional
    public boolean ingest(CsvRow row) {
        boolean inserted = eventLog.append(row.loanId(), row.month(), row.balance(), row.noi(),
            row.yearlyPayments(), row.paymentsLate());
        if (!inserted) {
            return false;
        }
        List<RuleEvaluation> evaluations = ruleEngine.evaluate(row.loanId(), row.month());
        alertService.applyRuleEvaluations(row.loanId(), row.month(), evaluations);
        return true;
    }
}
