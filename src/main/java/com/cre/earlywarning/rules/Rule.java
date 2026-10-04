package com.cre.earlywarning.rules;

public interface Rule {
    String id();

    RuleEvaluation evaluate(LoanMonthContext context);
}
