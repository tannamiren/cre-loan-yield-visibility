package com.cre.earlywarning.rules;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class R2LowDscrRule implements Rule {

    private final RuleConfig config;

    public R2LowDscrRule(RuleConfig config) {
        this.config = config;
    }

    @Override
    public String id() {
        return "R2";
    }

    @Override
    public RuleEvaluation evaluate(LoanMonthContext context) {
        var dscr = context.current().dscr();
        boolean fired = dscr.compareTo(config.r2LowDscrThreshold()) < 0;
        return new RuleEvaluation(id(), RuleType.CREDIT, fired,
            Map.of("dscr", dscr.toString()),
            config.r2LowDscrThreshold().toString(), config.version());
    }
}
