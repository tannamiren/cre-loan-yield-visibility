package com.cre.earlywarning.alerts;

import com.cre.earlywarning.domain.LoanRepository;
import com.cre.earlywarning.rules.RuleType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class ScoreCalculator {

    private final LoanRepository loanRepository;

    public ScoreCalculator(LoanRepository loanRepository) {
        this.loanRepository = loanRepository;
    }

    public Score compute(RuleType type, long monthsToMaturity, BigDecimal loanOriginalBalance) {
        int typeScore = type == RuleType.CREDIT ? 40 : 25;
        int timeScore = timeScore(monthsToMaturity);
        int sizeScore = sizeScore(loanOriginalBalance);
        return new Score(typeScore, timeScore, sizeScore);
    }

    private int timeScore(long monthsToMaturity) {
        if (monthsToMaturity <= 6) return 30;
        if (monthsToMaturity <= 12) return 20;
        if (monthsToMaturity <= 18) return 10;
        return 0;
    }

    private int sizeScore(BigDecimal balance) {
        BigDecimal max = loanRepository.findMaxOriginalBalance();
        if (max == null || max.compareTo(BigDecimal.ZERO) == 0) {
            return 0;
        }
        BigDecimal ratio = balance.divide(max, 4, RoundingMode.HALF_UP);
        return ratio.multiply(BigDecimal.valueOf(20)).setScale(0, RoundingMode.HALF_UP).intValue();
    }
}
