package com.cre.earlywarning.generator;

import com.cre.earlywarning.ingest.CsvRow;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Component
public class LoanDataGenerator {

    private static final int LOAN_COUNT = 100;
    private static final int MONTH_COUNT = 24;
    private static final YearMonth FIRST_MONTH = YearMonth.of(2024, 1);

    public GeneratedData generate(long seed) {
        Random random = new Random(seed);
        List<GeneratedLoan> loans = new ArrayList<>();
        Map<String, List<CsvRow>> rowsByLoan = new HashMap<>();

        loans.add(scriptedLoan("L001", "APARTMENT", 36));
        rowsByLoan.put("L001", slowSlideScenario());
        loans.add(scriptedLoan("L002", "OFFICE", 9));
        rowsByLoan.put("L002", weakRefinanceScenario());
        loans.add(scriptedLoan("L003", "RETAIL", 36));
        rowsByLoan.put("L003", missedPaymentsScenario());

        for (int i = 4; i <= LOAN_COUNT; i++) {
            String loanId = String.format("L%03d", i);
            String propertyType = propertyTypeFor(i);
            BigDecimal originalBalance = randomBalance(random);
            BigDecimal rate = randomRate(random);
            int maturityMonthsOut = 24 + random.nextInt(37); // 24..60 months out, stays quiet for R3/R5
            LocalDate maturityDate = FIRST_MONTH.plusMonths(maturityMonthsOut).atDay(1);
            BigDecimal yearlyPayments = amortizedAnnualPayment(originalBalance, rate);
            BigDecimal underwritingNoi = yearlyPayments.multiply(new BigDecimal("1.35"));

            loans.add(new GeneratedLoan(loanId, propertyType, originalBalance, rate, maturityDate, underwritingNoi));
            rowsByLoan.put(loanId, normalWalk(loanId, originalBalance, yearlyPayments, underwritingNoi, random));
        }

        Map<YearMonth, List<CsvRow>> rowsByMonth = new HashMap<>();
        for (int m = 0; m < MONTH_COUNT; m++) {
            YearMonth month = FIRST_MONTH.plusMonths(m);
            List<CsvRow> rowsForMonth = new ArrayList<>();
            for (GeneratedLoan loan : loans) {
                rowsForMonth.add(rowsByLoan.get(loan.loanId()).get(m));
            }
            rowsByMonth.put(month, rowsForMonth);
        }

        return new GeneratedData(loans, rowsByMonth);
    }

    private GeneratedLoan scriptedLoan(String loanId, String propertyType, int maturityMonthsOut) {
        BigDecimal originalBalance = new BigDecimal("20000000.00");
        BigDecimal rate = new BigDecimal("0.0550");
        LocalDate maturityDate = FIRST_MONTH.plusMonths(maturityMonthsOut).atDay(1);
        BigDecimal yearlyPayments = amortizedAnnualPayment(originalBalance, rate);
        BigDecimal underwritingNoi = yearlyPayments.multiply(new BigDecimal("1.45")).setScale(2, RoundingMode.HALF_UP);
        return new GeneratedLoan(loanId, propertyType, originalBalance, rate, maturityDate, underwritingNoi);
    }

    private List<CsvRow> slowSlideScenario() {
        BigDecimal balance = new BigDecimal("20000000.00");
        BigDecimal yearlyPayments = amortizedAnnualPayment(balance, new BigDecimal("0.0550"));
        List<CsvRow> rows = new ArrayList<>();
        for (int m = 0; m < MONTH_COUNT; m++) {
            YearMonth month = FIRST_MONTH.plusMonths(m);
            // dscr linearly interpolates 1.45 -> 1.05 over months 0..11, then holds at 1.05
            BigDecimal dscr = m <= 11
                ? new BigDecimal("1.45").subtract(new BigDecimal("0.40").multiply(BigDecimal.valueOf(m))
                    .divide(BigDecimal.valueOf(11), 4, RoundingMode.HALF_UP))
                : new BigDecimal("1.05");
            BigDecimal noi = dscr.multiply(yearlyPayments).setScale(2, RoundingMode.HALF_UP);
            rows.add(new CsvRow("L001", month, balance, noi, yearlyPayments, 0));
        }
        return rows;
    }

    private List<CsvRow> weakRefinanceScenario() {
        BigDecimal yearlyPayments = amortizedAnnualPayment(new BigDecimal("20000000.00"), new BigDecimal("0.0550"));
        BigDecimal dscr = new BigDecimal("1.30");
        BigDecimal noi = dscr.multiply(yearlyPayments).setScale(2, RoundingMode.HALF_UP);
        // debtYield = noi/balance must be 0.07 -> balance = noi/0.07
        BigDecimal balance = noi.divide(new BigDecimal("0.07"), 2, RoundingMode.HALF_UP);
        List<CsvRow> rows = new ArrayList<>();
        for (int m = 0; m < MONTH_COUNT; m++) {
            rows.add(new CsvRow("L002", FIRST_MONTH.plusMonths(m), balance, noi, yearlyPayments, 0));
        }
        return rows;
    }

    private List<CsvRow> missedPaymentsScenario() {
        BigDecimal balance = new BigDecimal("20000000.00");
        BigDecimal yearlyPayments = amortizedAnnualPayment(balance, new BigDecimal("0.0550"));
        BigDecimal noi = new BigDecimal("1.30").multiply(yearlyPayments).setScale(2, RoundingMode.HALF_UP);
        List<CsvRow> rows = new ArrayList<>();
        for (int m = 0; m < MONTH_COUNT; m++) {
            int paymentsLate = (m == 5 || m == 6) ? 2 : 0; // late at months 6-7 (0-indexed 5,6), clear after
            rows.add(new CsvRow("L003", FIRST_MONTH.plusMonths(m), balance, noi, yearlyPayments, paymentsLate));
        }
        return rows;
    }

    private List<CsvRow> normalWalk(String loanId, BigDecimal balance, BigDecimal yearlyPayments,
                                     BigDecimal startingNoi, Random random) {
        List<CsvRow> rows = new ArrayList<>();
        BigDecimal noi = startingNoi;
        for (int m = 0; m < MONTH_COUNT; m++) {
            double drift = (random.nextDouble() - 0.5) * 0.02; // +-1% monthly drift
            noi = noi.multiply(BigDecimal.valueOf(1 + drift)).setScale(2, RoundingMode.HALF_UP);
            rows.add(new CsvRow(loanId, FIRST_MONTH.plusMonths(m), balance, noi, yearlyPayments, 0));
        }
        return rows;
    }

    private String propertyTypeFor(int index) {
        int bucket = index % 10;
        if (bucket < 7) return "APARTMENT";
        if (bucket < 9) return "OFFICE";
        return "RETAIL";
    }

    private BigDecimal randomBalance(Random random) {
        double balance = 3_000_000 + random.nextDouble() * 27_000_000; // $3M-$30M
        return BigDecimal.valueOf(balance).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal randomRate(Random random) {
        double rate = 0.05 + random.nextDouble() * 0.02; // 5%-7%
        return BigDecimal.valueOf(rate).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal amortizedAnnualPayment(BigDecimal balance, BigDecimal annualRate) {
        double p = balance.doubleValue();
        double r = annualRate.doubleValue() / 12.0;
        int n = 30 * 12;
        double monthly = p * r / (1 - Math.pow(1 + r, -n));
        return BigDecimal.valueOf(monthly * 12).setScale(2, RoundingMode.HALF_UP);
    }
}
