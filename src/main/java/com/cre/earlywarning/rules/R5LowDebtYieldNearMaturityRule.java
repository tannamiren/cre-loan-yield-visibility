package com.cre.earlywarning.rules;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class R5LowDebtYieldNearMaturityRule implements Rule {

    private final RuleConfig config;

    public R5LowDebtYieldNearMaturityRule(RuleConfig config) {
        this.config = config;
    }

    @Override
    public String id() {
        return "R5";
    }

    @Override
    public RuleEvaluation evaluate(LoanMonthContext context) {
        var debtYield = context.current().debtYield();
        long monthsToMaturity = context.current().monthsToMaturity();
        boolean fired = debtYield.compareTo(config.r5LowDebtYieldThreshold()) < 0
            && monthsToMaturity <= config.r5MaturityMonths();
        Map<String, String> inputs = Map.of(
            "debtYield", debtYield.toString(),
            "monthsToMaturity", String.valueOf(monthsToMaturity));
        String limit = "debtYield<" + config.r5LowDebtYieldThreshold()
            + " and monthsToMaturity<=" + config.r5MaturityMonths();
        return new RuleEvaluation(id(), RuleType.EARLY_WARNING, fired, inputs, limit, config.version());
    }
}
