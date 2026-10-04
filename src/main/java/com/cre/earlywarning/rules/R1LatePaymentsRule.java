package com.cre.earlywarning.rules;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class R1LatePaymentsRule implements Rule {

    private final RuleConfig config;

    public R1LatePaymentsRule(RuleConfig config) {
        this.config = config;
    }

    @Override
    public String id() {
        return "R1";
    }

    @Override
    public RuleEvaluation evaluate(LoanMonthContext context) {
        int paymentsLate = context.current().paymentsLate();
        boolean fired = paymentsLate >= config.r1LatePaymentsThreshold();
        return new RuleEvaluation(id(), RuleType.CREDIT, fired,
            Map.of("paymentsLate", String.valueOf(paymentsLate)),
            String.valueOf(config.r1LatePaymentsThreshold()), config.version());
    }
}
