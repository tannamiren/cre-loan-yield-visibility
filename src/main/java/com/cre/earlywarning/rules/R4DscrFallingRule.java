package com.cre.earlywarning.rules;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

import com.cre.earlywarning.metrics.LoanMetrics;

@Component
public class R4DscrFallingRule implements Rule {

    private final RuleConfig config;

    public R4DscrFallingRule(RuleConfig config) {
        this.config = config;
    }

    @Override
    public String id() {
        return "R4";
    }

    @Override
    public RuleEvaluation evaluate(LoanMonthContext context) {
        Optional<LoanMetrics> past = context.monthsAgo(config.r4DscrFallMonths());
        if (past.isEmpty()) {
            return new RuleEvaluation(id(), RuleType.EARLY_WARNING, false,
                Map.of("reason", "insufficient history"),
                config.r4DscrFallDelta().toString(), config.version());
        }
        var fall = past.get().dscr().subtract(context.current().dscr());
        boolean fired = fall.compareTo(config.r4DscrFallDelta()) >= 0;
        Map<String, String> inputs = Map.of(
            "dscrNow", context.current().dscr().toString(),
            "dscrMonthsAgo", past.get().dscr().toString(),
            "fall", fall.toString());
        return new RuleEvaluation(id(), RuleType.EARLY_WARNING, fired, inputs,
            config.r4DscrFallDelta().toString(), config.version());
    }
}
