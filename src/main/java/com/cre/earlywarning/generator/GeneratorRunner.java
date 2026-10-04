package com.cre.earlywarning.generator;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanRepository;
import com.cre.earlywarning.ingest.CsvRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

@Component
@Profile("generator")
public class GeneratorRunner implements CommandLineRunner {

    private final LoanDataGenerator generator;
    private final LoanRepository loanRepository;
    private final long seed;
    private final Path outputDir;

    public GeneratorRunner(LoanDataGenerator generator, LoanRepository loanRepository,
                            @Value("${app.generator.seed}") long seed,
                            @Value("${app.generator.output-dir}") String outputDir) {
        this.generator = generator;
        this.loanRepository = loanRepository;
        this.seed = seed;
        this.outputDir = Path.of(outputDir);
    }

    @Override
    public void run(String... args) throws IOException {
        GeneratedData data = generator.generate(seed);

        for (GeneratedLoan loan : data.loans()) {
            loanRepository.save(new Loan(loan.loanId(), loan.propertyType(), loan.originalBalance(),
                loan.rate(), loan.maturityDate(), loan.underwritingNoi()));
        }

        Files.createDirectories(outputDir);
        for (Map.Entry<YearMonth, List<CsvRow>> entry : data.rowsByMonth().entrySet()) {
            writeMonthFile(entry.getKey(), entry.getValue());
        }
    }

    private void writeMonthFile(YearMonth month, List<CsvRow> rows) throws IOException {
        StringBuilder sb = new StringBuilder("loan_id,month,balance,noi,yearly_payments,payments_late\n");
        for (CsvRow row : rows) {
            sb.append(row.loanId()).append(',')
                .append(row.month()).append(',')
                .append(row.balance()).append(',')
                .append(row.noi()).append(',')
                .append(row.yearlyPayments()).append(',')
                .append(row.paymentsLate()).append('\n');
        }
        Files.writeString(outputDir.resolve("report-" + month + ".csv"), sb.toString());
    }
}
