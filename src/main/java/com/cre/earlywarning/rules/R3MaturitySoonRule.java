package com.cre.earlywarning.rules;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class R3MaturitySoonRule implements Rule {

    private final RuleConfig config;

    public R3MaturitySoonRule(RuleConfig config) {
        this.config = config;
    }

    @Override
    public String id() {
        return "R3";
    }

    @Override
    public RuleEvaluation evaluate(LoanMonthContext context) {
        long monthsToMaturity = context.current().monthsToMaturity();
        boolean fired = monthsToMaturity <= config.r3MaturitySoonMonths();
        return new RuleEvaluation(id(), RuleType.CREDIT, fired,
            Map.of("monthsToMaturity", String.valueOf(monthsToMaturity)),
            String.valueOf(config.r3MaturitySoonMonths()), config.version());
    }
}
