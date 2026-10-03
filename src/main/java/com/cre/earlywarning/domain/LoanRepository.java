package com.cre.earlywarning.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;

public interface LoanRepository extends JpaRepository<Loan, String> {

    @Query("select max(l.originalBalance) from Loan l")
    BigDecimal findMaxOriginalBalance();
}
