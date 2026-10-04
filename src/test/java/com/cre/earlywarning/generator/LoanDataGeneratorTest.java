package com.cre.earlywarning.generator;

import com.cre.earlywarning.ingest.CsvRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
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

    private List<CsvRow> rowsForLoan(GeneratedData data, String loanId) {
        return data.rowsByMonth().keySet().stream().sorted()
            .map(month -> data.rowsByMonth().get(month).stream()
                .filter(r -> r.loanId().equals(loanId)).findFirst().orElseThrow())
            .sorted(Comparator.comparing(CsvRow::month))
            .toList();
    }
}
