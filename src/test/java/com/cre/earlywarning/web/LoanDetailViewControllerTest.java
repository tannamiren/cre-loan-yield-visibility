package com.cre.earlywarning.web;

import com.cre.earlywarning.domain.EventLog;
import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanRepository;
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
class LoanDetailViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private EventLog eventLog;

    @Test
    void loanDetailPageShowsChartDataAndAlerts() throws Exception {
        loanRepository.save(new Loan("L611", "OFFICE", new BigDecimal("8000000.00"),
            new BigDecimal("0.0600"), LocalDate.of(2030, 1, 1), new BigDecimal("600000.00")));
        eventLog.append("L611", YearMonth.of(2024, 1), new BigDecimal("8000000.00"),
            new BigDecimal("600000.00"), new BigDecimal("550000.00"), 0);

        mockMvc.perform(get("/loans/L611/detail"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("chart.js")))
            .andExpect(content().string(containsString("2024-01")));
    }
}
