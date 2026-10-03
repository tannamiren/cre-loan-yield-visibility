package com.cre.earlywarning.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class JpaEventLogTest {

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private EventLog eventLog;

    @Autowired
    private LoanMonthEventRepository eventRepository;

    @Test
    void firstAppendInsertsEvent() {
        loanRepository.save(new Loan("L999", "APARTMENT", new BigDecimal("1000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2027, 1, 1), new BigDecimal("90000.00")));

        boolean inserted = eventLog.append("L999", YearMonth.of(2024, 1),
            new BigDecimal("1000000.00"), new BigDecimal("90000.00"),
            new BigDecimal("68000.00"), 0);

        assertThat(inserted).isTrue();
        assertThat(eventRepository.findByLoanIdOrderByMonthAsc("L999")).hasSize(1);
    }

    @Test
    void duplicateAppendIsNoOpAndReportsFalse() {
        loanRepository.save(new Loan("L998", "OFFICE", new BigDecimal("2000000.00"),
            new BigDecimal("0.0600"), LocalDate.of(2027, 1, 1), new BigDecimal("150000.00")));

        boolean first = eventLog.append("L998", YearMonth.of(2024, 3),
            new BigDecimal("2000000.00"), new BigDecimal("150000.00"),
            new BigDecimal("140000.00"), 0);
        boolean second = eventLog.append("L998", YearMonth.of(2024, 3),
            new BigDecimal("2000000.00"), new BigDecimal("999999.00"), // different NOI on purpose
            new BigDecimal("140000.00"), 0);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(eventRepository.findByLoanIdOrderByMonthAsc("L998")).hasSize(1);
        // the first write wins; the duplicate's different NOI must not have overwritten it
        assertThat(eventRepository.findByLoanIdOrderByMonthAsc("L998").get(0).getNoi())
            .isEqualByComparingTo("150000.00");
    }

    @Test
    void concurrentAppendOfSameLoanMonthOnlySucceedsOnce() throws Exception {
        String loanId = "L997";
        YearMonth month = YearMonth.of(2024, 6);
        int threadCount = 2;

        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<Boolean>> futures = new ArrayList<>();
        CountDownLatch readyLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                return eventLog.append(loanId, month,
                    new BigDecimal("3000000.00"), new BigDecimal("200000.00"),
                    new BigDecimal("180000.00"), 0);
            }));
        }

        // make sure both worker threads are parked on the latch before releasing them,
        // so both race to call append() for the same loanId+month at effectively the same time
        readyLatch.await();
        startLatch.countDown();

        int successCount = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(10, TimeUnit.SECONDS)) {
                successCount++;
            }
        }
        executor.shutdown();

        assertThat(successCount).isEqualTo(1);
        assertThat(eventRepository.findByLoanIdOrderByMonthAsc(loanId)).hasSize(1);
    }
}
