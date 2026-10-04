package com.cre.earlywarning.alerts;

public record Score(int typeScore, int timeScore, int sizeScore) {
    public int total() {
        return typeScore + timeScore + sizeScore;
    }
}
