package com.cre.earlywarning.api;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanRepository;
import com.cre.earlywarning.ingest.CsvRow;
import com.cre.earlywarning.ingest.IngestService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LoanControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private IngestService ingestService;

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

        // Scope assertions to this test's own loan ID rather than the raw list length: other
        // non-transactional test classes in this suite (e.g. FolderPollerJobTest) commit Loan
        // rows directly to the shared H2 database that persist across test classes, so the full
        // /loans list can legitimately contain more than just L700 depending on run order.
        // Parsed in Java rather than via a raw-length jsonPath, since a single-match jsonPath
        // filter result gets unwrapped by Jayway JsonPath and ".length()" then returns the
        // matched object's field count instead of a match count.
        String body = mockMvc.perform(get("/loans"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        JsonNode all = new ObjectMapper().readTree(body);
        List<JsonNode> matches = new ArrayList<>();
        all.forEach(node -> {
            if ("L700".equals(node.path("loanId").asText())) {
                matches.add(node);
            }
        });

        assertThat(matches).hasSize(1);
        JsonNode loan700 = matches.get(0);
        assertThat(loan700.path("history")).hasSize(2);
        assertThat(loan700.hasNonNull("latestDscr")).isTrue();
        assertThat(loan700.hasNonNull("latestDebtYield")).isTrue();
    }
}
