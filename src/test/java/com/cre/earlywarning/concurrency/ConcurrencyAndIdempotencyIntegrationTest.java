package com.cre.earlywarning.concurrency;

import com.cre.earlywarning.EarlyWarningApplication;
import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanMonthEventRepository;
import com.cre.earlywarning.domain.LoanRepository;
import com.cre.earlywarning.ingest.CsvRow;
import com.cre.earlywarning.ingest.FolderPollerJob;
import com.cre.earlywarning.ingest.IngestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ConcurrencyAndIdempotencyIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("earlywarning")
        .withUsername("earlywarning")
        .withPassword("earlywarning");

    private ConfigurableApplicationContext nodeA;
    private ConfigurableApplicationContext nodeB;

    @AfterEach
    void tearDown() {
        if (nodeA != null) nodeA.close();
        if (nodeB != null) nodeB.close();
    }

    @Test
    void twoNodesPollingTheSameInbox_onlyOneProcessesEachFile(@TempDir Path sharedDirs) throws Exception {
        Path inbox = sharedDirs.resolve("inbox");
        Path processing = sharedDirs.resolve("processing");
        Path done = sharedDirs.resolve("done");
        Path failed = sharedDirs.resolve("failed");
        Files.createDirectories(inbox);

        nodeA = startNode(inbox, processing, done, failed);
        nodeB = startNode(inbox, processing, done, failed);
        seedLoan(nodeA, "L950");

        Path file = inbox.resolve("report-2024-01.csv");
        Files.writeString(file, """
            loan_id,month,balance,noi,yearly_payments,payments_late
            L950,2024-01,5000000.00,400000.00,300000.00,0
            """);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - 10_000));

        FolderPollerJob pollerA = nodeA.getBean(FolderPollerJob.class);
        FolderPollerJob pollerB = nodeB.getBean(FolderPollerJob.class);

        // Both nodes' @Scheduled FolderPollerJob.poll() already starts firing automatically on
        // app startup (fixedDelay = 10s) and can win the ShedLock (lockAtLeastFor = 5s) on an
        // empty inbox before this test writes the file above. A single manual poll() call can
        // therefore race against that already-held lock and no-op. Retrying each node's poll()
        // until the file is gone keeps the real concurrency/ShedLock behavior under test (two
        // nodes genuinely racing for the same file against real MySQL) without depending on exact
        // scheduler timing.
        ExecutorService executor = Executors.newFixedThreadPool(2);
        executor.submit(() -> pollUntilProcessed(pollerA, file));
        executor.submit(() -> pollUntilProcessed(pollerB, file));
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        assertThat(Files.exists(file)).isFalse();
        assertThat(Files.exists(done.resolve("report-2024-01.csv"))).isTrue();
        assertThat(Files.exists(processing.resolve("report-2024-01.csv"))).isFalse();

        LoanMonthEventRepository eventRepository = nodeA.getBean(LoanMonthEventRepository.class);
        assertThat(eventRepository.findByLoanIdOrderByMonthAsc("L950")).hasSize(1);
    }

    @Test
    void doubleIngestAgainstRealMySqlUniqueConstraintIsSafe(@TempDir Path dirs) {
        nodeA = startNode(dirs.resolve("inbox"), dirs.resolve("processing"), dirs.resolve("done"),
            dirs.resolve("failed"));
        seedLoan(nodeA, "L951");
        IngestService ingestService = nodeA.getBean(IngestService.class);
        CsvRow row = new CsvRow("L951", YearMonth.of(2024, 1), new BigDecimal("5000000.00"),
            new BigDecimal("400000.00"), new BigDecimal("300000.00"), 0);

        boolean first = ingestService.ingest(row);
        boolean second = ingestService.ingest(row);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        LoanMonthEventRepository eventRepository = nodeA.getBean(LoanMonthEventRepository.class);
        assertThat(eventRepository.findByLoanIdOrderByMonthAsc("L951")).hasSize(1);
    }

    private void pollUntilProcessed(FolderPollerJob poller, Path file) {
        long deadline = System.currentTimeMillis() + 25_000;
        while (Files.exists(file) && System.currentTimeMillis() < deadline) {
            poller.poll();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private ConfigurableApplicationContext startNode(Path inbox, Path processing, Path done, Path failed) {
        // NOTE: src/test/resources/application.yml is on the test classpath and is loaded by
        // Spring Boot with HIGHER precedence than SpringApplicationBuilder.properties(...) (the
        // latter only populates the low-priority "default properties" source). To genuinely point
        // each node at the shared Testcontainers MySQL instance and the per-test @TempDir
        // directories (rather than silently falling back to the H2 test datasource and the fixed
        // build/test-data directories from application.yml), these overrides are applied as JVM
        // System properties, which Spring Boot resolves ahead of application.yml.
        Properties props = new Properties();
        props.setProperty("spring.datasource.url", mysql.getJdbcUrl());
        props.setProperty("spring.datasource.username", mysql.getUsername());
        props.setProperty("spring.datasource.password", mysql.getPassword());
        props.setProperty("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        props.setProperty("spring.jpa.hibernate.ddl-auto", "validate");
        props.setProperty("spring.main.web-application-type", "none");
        props.setProperty("app.rules.active-version", "1");
        props.setProperty("app.generator.seed", "42");
        props.setProperty("app.intake.inbox-dir", inbox.toString());
        props.setProperty("app.intake.processing-dir", processing.toString());
        props.setProperty("app.intake.done-dir", done.toString());
        props.setProperty("app.intake.failed-dir", failed.toString());
        props.setProperty("app.generator.output-dir", inbox.toString());

        for (String key : props.stringPropertyNames()) {
            System.setProperty(key, props.getProperty(key));
        }

        return new SpringApplicationBuilder(EarlyWarningApplication.class).properties(props).run();
    }

    private void seedLoan(ConfigurableApplicationContext context, String loanId) {
        LoanRepository loanRepository = context.getBean(LoanRepository.class);
        loanRepository.save(new Loan(loanId, "APARTMENT", new BigDecimal("5000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2030, 1, 1), new BigDecimal("400000.00")));
    }
}
