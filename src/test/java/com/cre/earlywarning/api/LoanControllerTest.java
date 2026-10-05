package com.cre.earlywarning.api;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanRepository;
import com.cre.earlywarning.ingest.CsvRow;
import com.cre.earlywarning.ingest.IngestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class LoanControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private IngestService ingestService;

    @BeforeEach
    void setUp() {
        loanRepository.deleteAll();
    }

    @Test
    void getLoanReturnsHistoryAndAlerts() throws Exception {
        loanRepository.save(new Loan("L600", "APARTMENT", new BigDecimal("5000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2030, 1, 1), new BigDecimal("400000.00")));
        ingestService.ingest(new CsvRow("L600", YearMonth.of(2024, 1), new BigDecimal("5000000.00"),
            new BigDecimal("400000.00"), new BigDecimal("600000.00"), 2));

        mockMvc.perform(get("/loans/L600"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.loanId", is("L600")))
            .andExpect(jsonPath("$.history.length()", is(1)))
            .andExpect(jsonPath("$.alerts.length()", is(2)));
    }

    @Test
    void getUnknownLoanReturns404() throws Exception {
        mockMvc.perform(get("/loans/DOES-NOT-EXIST"))
            .andExpect(status().isNotFound());
    }

    @Test
    void listReturnsAllLoansWithLatestAndHistoricalMetrics() throws Exception {
        loanRepository.save(new Loan("L700", "OFFICE", new BigDecimal("8000000.00"),
            new BigDecimal("0.0600"), LocalDate.of(2030, 1, 1), new BigDecimal("600000.00")));
        ingestService.ingest(new CsvRow("L700", YearMonth.of(2024, 1), new BigDecimal("8000000.00"),
            new BigDecimal("550000.00"), new BigDecimal("500000.00"), 0));
        ingestService.ingest(new CsvRow("L700", YearMonth.of(2024, 2), new BigDecimal("8000000.00"),
            new BigDecimal("600000.00"), new BigDecimal("500000.00"), 0));

        mockMvc.perform(get("/loans"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()", is(1)))
            .andExpect(jsonPath("$[0].loanId", is("L700")))
            .andExpect(jsonPath("$[0].history.length()", is(2)))
            .andExpect(jsonPath("$[0].latestDscr").exists())
            .andExpect(jsonPath("$[0].latestDebtYield").exists());
    }
}
