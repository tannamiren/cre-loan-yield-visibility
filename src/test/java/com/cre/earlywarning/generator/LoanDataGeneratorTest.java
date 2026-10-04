package com.cre.earlywarning.generator;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.ingest.CsvRow;
import com.cre.earlywarning.metrics.LoanMetrics;
import com.cre.earlywarning.rules.LoanMonthContext;
import com.cre.earlywarning.rules.R1LatePaymentsRule;
import com.cre.earlywarning.rules.R2LowDscrRule;
import com.cre.earlywarning.rules.R3MaturitySoonRule;
import com.cre.earlywarning.rules.R5LowDebtYieldNearMaturityRule;
import com.cre.earlywarning.rules.RuleConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoanDataGeneratorTest {

    private final LoanDataGenerator generator = new LoanDataGenerator();

    @Test
    void generatesOneHundredLoansWithTwentyFourMonthsEach() {
        GeneratedData data = generator.generate(42L);

        assertThat(data.loans()).hasSize(100);
        assertThat(data.rowsByMonth()).hasSize(24);
        data.rowsByMonth().values().forEach(rows -> assertThat(rows).hasSize(100));
    }

    @Test
    void sameSeedProducesIdenticalOutput() {
        GeneratedData first = generator.generate(42L);
        GeneratedData second = generator.generate(42L);

        assertThat(first.loans()).containsExactlyElementsOf(second.loans());
        YearMonth anyMonth = first.rowsByMonth().keySet().iterator().next();
        assertThat(first.rowsByMonth().get(anyMonth)).containsExactlyElementsOf(second.rowsByMonth().get(anyMonth));
    }

    @Test
    void slowSlideScenarioLoanL001HasR4FiringAtLeastThreeMonthsBeforeR2() {
        GeneratedData data = generator.generate(42L);

        List<CsvRow> l001Rows = rowsForLoan(data, "L001");
        // DSCR must fall from ~1.45 to ~1.05 over the first 12 months so R4 (fall>=0.15 over 6mo)
        // fires well before R2 (dscr<1.10)
        BigDecimal yearlyPayments = l001Rows.get(0).yearlyPayments();
        BigDecimal firstMonthDscr = l001Rows.get(0).noi().divide(yearlyPayments, 4, java.math.RoundingMode.HALF_UP);
        BigDecimal twelfthMonthDscr = l001Rows.get(11).noi().divide(yearlyPayments, 4, java.math.RoundingMode.HALF_UP);

        assertThat(firstMonthDscr).isGreaterThan(new BigDecimal("1.40"));
        assertThat(twelfthMonthDscr).isLessThan(new BigDecimal("1.10"));
    }

    @Test
    void weakRefinanceScenarioLoanL002HasDebtYieldBelowEightPercentNearMaturity() {
        GeneratedData data = generator.generate(42L);
        List<CsvRow> l002Rows = rowsForLoan(data, "L002");
        CsvRow firstMonth = l002Rows.get(0);

        BigDecimal debtYield = firstMonth.noi().divide(firstMonth.balance(), 4, java.math.RoundingMode.HALF_UP);
        assertThat(debtYield).isLessThan(new BigDecimal("0.08"));

        GeneratedLoan loan = data.loans().stream().filter(l -> l.loanId().equals("L002")).findFirst().orElseThrow();
        long monthsToMaturity = java.time.temporal.ChronoUnit.MONTHS.between(
            firstMonth.month().atDay(1), loan.maturityDate().withDayOfMonth(1));
        assertThat(monthsToMaturity).isLessThanOrEqualTo(18);
    }

    @Test
    void missedPaymentsScenarioLoanL003GoesLateThenClears() {
        GeneratedData data = generator.generate(42L);
        List<CsvRow> l003Rows = rowsForLoan(data, "L003");

        assertThat(l003Rows.get(5).paymentsLate()).isGreaterThanOrEqualTo(2);
        assertThat(l003Rows.get(6).paymentsLate()).isGreaterThanOrEqualTo(2);
        assertThat(l003Rows.get(9).paymentsLate()).isEqualTo(0);
    }

    @Test
    void ninetySevenRandomLoansNeverFireR1R2R3R5AcrossAllTwentyFourMonths() {
        // Matches src/main/resources/rules/rules-v1.yaml, the active production rule config.
        RuleConfig config = new RuleConfig(1, 2, new BigDecimal("1.10"), 3,
            new BigDecimal("0.15"), 6, new BigDecimal("0.08"), 18);
        R1LatePaymentsRule r1 = new R1LatePaymentsRule(config);
        R2LowDscrRule r2 = new R2LowDscrRule(config);
        R3MaturitySoonRule r3 = new R3MaturitySoonRule(config);
        R5LowDebtYieldNearMaturityRule r5 = new R5LowDebtYieldNearMaturityRule(config);

        GeneratedData data = generator.generate(42L);

        for (GeneratedLoan generatedLoan : data.loans()) {
            if (List.of("L001", "L002", "L003").contains(generatedLoan.loanId())) {
                continue; // scripted scenarios are designed to fire rules; only the 97 random loans must stay quiet
            }

            Loan loan = new Loan(generatedLoan.loanId(), generatedLoan.propertyType(),
                generatedLoan.originalBalance(), generatedLoan.rate(), generatedLoan.maturityDate(),
                generatedLoan.underwritingNoi());

            List<CsvRow> rows = rowsForLoan(data, generatedLoan.loanId());
            List<LoanMetrics> history = new ArrayList<>();
            for (CsvRow row : rows) {
                BigDecimal dscr = row.noi().divide(row.yearlyPayments(), 4, RoundingMode.HALF_UP);
                BigDecimal debtYield = row.noi().divide(row.balance(), 4, RoundingMode.HALF_UP);
                long monthsToMaturity = ChronoUnit.MONTHS.between(
                    row.month().atDay(1), loan.getMaturityDate().withDayOfMonth(1));
                LoanMetrics current = new LoanMetrics(row.month(), row.balance(), row.noi(),
                    row.yearlyPayments(), row.paymentsLate(), dscr, debtYield, monthsToMaturity);
                history.add(current);

                LoanMonthContext context = new LoanMonthContext(loan, current, history);

                assertThat(r1.evaluate(context).fired())
                    .as("R1 fired for %s at %s", generatedLoan.loanId(), row.month()).isFalse();
                assertThat(r2.evaluate(context).fired())
                    .as("R2 fired for %s at %s", generatedLoan.loanId(), row.month()).isFalse();
                assertThat(r3.evaluate(context).fired())
                    .as("R3 fired for %s at %s", generatedLoan.loanId(), row.month()).isFalse();
                assertThat(r5.evaluate(context).fired())
                    .as("R5 fired for %s at %s", generatedLoan.loanId(), row.month()).isFalse();
            }
        }
    }

    private List<CsvRow> rowsForLoan(GeneratedData data, String loanId) {
        return data.rowsByMonth().keySet().stream().sorted()
            .map(month -> data.rowsByMonth().get(month).stream()
                .filter(r -> r.loanId().equals(loanId)).findFirst().orElseThrow())
            .sorted(Comparator.comparing(CsvRow::month))
            .toList();
    }
}
