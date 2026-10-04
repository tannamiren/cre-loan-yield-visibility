package com.cre.earlywarning.ingest;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanMonthEventRepository;
import com.cre.earlywarning.domain.LoanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
    "app.intake.inbox-dir=build/test-data/poller-inbox",
    "app.intake.processing-dir=build/test-data/poller-processing",
    "app.intake.done-dir=build/test-data/poller-done",
    "app.intake.failed-dir=build/test-data/poller-failed"
})
class FolderPollerJobTest {

    // NOTE on a brief inconsistency fixed here (test file only, no production code changed):
    // FolderPollerJob is @Scheduled(fixedDelay = 10_000) and guarded by
    // @SchedulerLock(name = "report-poller", lockAtMostFor = "5m", lockAtLeastFor = "5s").
    // In a full @SpringBootTest, Spring's real scheduler is active and fires the job's first
    // background execution essentially as soon as the context is ready -- i.e. right around
    // when this test method starts running. Because lockAtLeastFor = "5s" holds the
    // "report-poller" lock for at least 5 seconds after that background execution starts, the
    // test's own explicit `pollerJob.poll()` call races the background trigger and can be
    // silently skipped by the ShedLock proxy (lock already held), making the test flaky/failing
    // non-deterministically. The brief's test as written assumes `pollerJob.poll()` is the only
    // invocation of poll() during the test, which isn't guaranteed once real scheduling is on.
    // Fix: disable the live scheduler for this test only by supplying a no-op TaskScheduler bean,
    // so background @Scheduled firing never happens and the test's manual poll() call is the sole
    // invocation, matching the test's original intent. Production code (FolderPollerJob,
    // ShedLockConfig) is unchanged.
    @TestConfiguration
    static class DisableBackgroundSchedulingConfig {
        @Bean
        TaskScheduler taskScheduler() {
            return new TaskScheduler() {
                @Override
                public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
                    return noOpFuture();
                }

                @Override
                public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
                    return noOpFuture();
                }

                @Override
                public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
                    return noOpFuture();
                }

                @Override
                public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
                    return noOpFuture();
                }

                @Override
                public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
                    return noOpFuture();
                }

                @Override
                public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
                    return noOpFuture();
                }

                private ScheduledFuture<Object> noOpFuture() {
                    return new ScheduledFuture<Object>() {
                        @Override
                        public long getDelay(TimeUnit unit) {
                            return Long.MAX_VALUE;
                        }

                        @Override
                        public int compareTo(Delayed o) {
                            return 0;
                        }

                        @Override
                        public boolean cancel(boolean mayInterruptIfRunning) {
                            return true;
                        }

                        @Override
                        public boolean isCancelled() {
                            return true;
                        }

                        @Override
                        public boolean isDone() {
                            return true;
                        }

                        @Override
                        public Object get() {
                            return null;
                        }

                        @Override
                        public Object get(long timeout, TimeUnit unit) {
                            return null;
                        }
                    };
                }
            };
        }
    }

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private LoanMonthEventRepository eventRepository;

    @Autowired
    private FolderPollerJob pollerJob;

    @Autowired
    private DataSource dataSource;

    @Test
    void pollPicksUpAFileAndMovesItToDone() throws IOException, InterruptedException {
        // All test contexts in this project share the same named in-memory H2 database
        // (DB_CLOSE_DELAY=-1, Surefire reuses the JVM fork across test classes), including the
        // shedlock table. Other @SpringBootTest classes run with the real scheduler active
        // (@EnableScheduling is global) and can leave a stale "report-poller" lock row behind if
        // their context is torn down before the lock's lockAtMostFor window naturally expires --
        // ShedLock's expiry is based on the lock_until timestamp in the row, not on the original
        // holder process being alive. If that happens, this test's manual pollerJob.poll() call
        // would see the lock already held and the ShedLock AOP proxy would silently skip the
        // method body, making the test flaky under the full suite despite passing in isolation.
        // Clear any stale lock for this job before invoking it so the test starts from a
        // guaranteed-unlocked state regardless of what earlier test classes left behind.
        new JdbcTemplate(dataSource).update("DELETE FROM shedlock WHERE name = ?", "report-poller");

        loanRepository.save(new Loan("L850", "APARTMENT", new BigDecimal("5000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2030, 1, 1), new BigDecimal("400000.00")));

        Path inbox = Path.of("build/test-data/poller-inbox");
        Files.createDirectories(inbox);
        Path file = inbox.resolve("report-2024-01.csv");
        Files.writeString(file, """
            loan_id,month,balance,noi,yearly_payments,payments_late
            L850,2024-01,5000000.00,400000.00,300000.00,0
            """);
        // backdate the file so the poller's "skip if modified in last 5s" guard doesn't skip it
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
            System.currentTimeMillis() - 10_000));

        pollerJob.poll();

        assertThat(Files.exists(file)).isFalse();
        assertThat(Files.exists(Path.of("build/test-data/poller-done/report-2024-01.csv"))).isTrue();
        assertThat(eventRepository.findByLoanIdOrderByMonthAsc("L850")).hasSize(1);
    }
}
