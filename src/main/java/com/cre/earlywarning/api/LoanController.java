package com.cre.earlywarning.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/loans")
public class LoanController {

    private final LoanQueryService loanQueryService;

    public LoanController(LoanQueryService loanQueryService) {
        this.loanQueryService = loanQueryService;
    }

    @GetMapping
    public java.util.List<LoanSummaryDto> list() {
        return loanQueryService.getAllLoanSummaries();
    }

    @GetMapping("/{id}")
    public LoanDetailDto detail(@PathVariable String id) {
        return loanQueryService.getLoanDetail(id);
    }
}
