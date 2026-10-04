package com.cre.earlywarning.web;

import com.cre.earlywarning.api.LoanDetailDto;
import com.cre.earlywarning.api.LoanQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.Map;

@Controller
public class LoanDetailViewController {

    private final LoanQueryService loanQueryService;
    private final ObjectMapper objectMapper;

    public LoanDetailViewController(LoanQueryService loanQueryService, ObjectMapper objectMapper) {
        this.loanQueryService = loanQueryService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/loans/{id}/detail")
    public String detail(@PathVariable String id, Model model) throws Exception {
        LoanDetailDto loan = loanQueryService.getLoanDetail(id);

        List<String> months = loan.history().stream().map(h -> h.month()).toList();
        List<java.math.BigDecimal> dscr = loan.history().stream().map(h -> h.dscr()).toList();
        List<java.math.BigDecimal> debtYield = loan.history().stream().map(h -> h.debtYield()).toList();
        String chartDataJson = objectMapper.writeValueAsString(
            Map.of("labels", months, "dscr", dscr, "debtYield", debtYield));

        model.addAttribute("loan", loan);
        model.addAttribute("chartDataJson", chartDataJson);
        return "loan-detail";
    }
}
