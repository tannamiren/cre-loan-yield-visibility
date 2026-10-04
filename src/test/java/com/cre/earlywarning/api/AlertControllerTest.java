package com.cre.earlywarning.api;

import com.cre.earlywarning.alerts.AlertRepository;
import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanRepository;
import com.cre.earlywarning.alerts.Alert;
import com.cre.earlywarning.alerts.AlertState;
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

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AlertControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private IngestService ingestService;

    @Autowired
    private AlertRepository alertRepository;

    @Test
    void getAlertsReturnsOpenQueue() throws Exception {
        loanRepository.save(new Loan("L601", "APARTMENT", new BigDecimal("5000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2030, 1, 1), new BigDecimal("400000.00")));
        ingestService.ingest(new CsvRow("L601", YearMonth.of(2024, 1), new BigDecimal("5000000.00"),
            new BigDecimal("400000.00"), new BigDecimal("600000.00"), 2));

        mockMvc.perform(get("/alerts"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].loanId", is("L601")));
    }

    @Test
    void acknowledgeSetsStateToAcknowledged() throws Exception {
        loanRepository.save(new Loan("L602", "APARTMENT", new BigDecimal("5000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2030, 1, 1), new BigDecimal("400000.00")));
        ingestService.ingest(new CsvRow("L602", YearMonth.of(2024, 1), new BigDecimal("5000000.00"),
            new BigDecimal("400000.00"), new BigDecimal("600000.00"), 2));
        Alert alert = alertRepository.findByLoanIdAndRuleId("L602", "R1").orElseThrow();

        mockMvc.perform(post("/alerts/" + alert.getId() + "/acknowledge"))
            .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(
            alertRepository.findById(alert.getId()).orElseThrow().getState()).isEqualTo(AlertState.ACKNOWLEDGED);
    }
}
