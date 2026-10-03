package com.cre.earlywarning.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoanMonthEventRepository extends JpaRepository<LoanMonthEvent, Long> {

    List<LoanMonthEvent> findByLoanIdOrderByMonthAsc(String loanId);

    boolean existsByLoanIdAndMonth(String loanId, String month);
}
