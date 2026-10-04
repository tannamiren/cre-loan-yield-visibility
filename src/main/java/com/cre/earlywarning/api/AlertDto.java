package com.cre.earlywarning.api;

import com.cre.earlywarning.alerts.Alert;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public record AlertDto(
    Long id, String loanId, String ruleId, String state, int score,
    int scoreType, int scoreTime, int scoreSize, int ruleVersion,
    String firedMonth, String limitValue, Map<String, String> inputs
) {
    public static AlertDto from(Alert a, ObjectMapper mapper) {
        Map<String, String> inputs;
        try {
            inputs = mapper.readValue(a.getInputsJson(), new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            inputs = Map.of();
        }
        return new AlertDto(a.getId(), a.getLoanId(), a.getRuleId(), a.getState().name(), a.getScore(),
            a.getScoreType(), a.getScoreTime(), a.getScoreSize(), a.getRuleVersion(), a.getFiredMonth(),
            a.getLimitValue(), inputs);
    }
}
