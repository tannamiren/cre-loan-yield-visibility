package com.cre.earlywarning.rules;

import java.util.Map;

public record RuleEvaluation(
    String ruleId,
    RuleType type,
    boolean fired,
    Map<String, String> inputs,
    String limit,
    int ruleVersion
) {
}
