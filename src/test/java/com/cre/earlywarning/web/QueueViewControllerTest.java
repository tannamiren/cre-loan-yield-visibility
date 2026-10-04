package com.cre.earlywarning.web;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanRepository;
import com.cre.earlywarning.ingest.CsvRow;
import com.cre.earlywarning.ingest.IngestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class QueueViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private IngestService ingestService;

    @Test
    void queuePageListsOpenAlerts() throws Exception {
        loanRepository.save(new Loan("L610", "APARTMENT", new BigDecimal("5000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2030, 1, 1), new BigDecimal("400000.00")));
        ingestService.ingest(new CsvRow("L610", YearMonth.of(2024, 1), new BigDecimal("5000000.00"),
            new BigDecimal("400000.00"), new BigDecimal("600000.00"), 2));

        mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("L610")));
    }
}
