package com.cre.earlywarning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "loan")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Loan {

    @Id
    @Column(name = "loan_id")
    private String loanId;

    @Column(name = "property_type", nullable = false)
    private String propertyType;

    @Column(name = "original_balance", nullable = false)
    private BigDecimal originalBalance;

    @Column(name = "rate", nullable = false)
    private BigDecimal rate;

    @Column(name = "maturity_date", nullable = false)
    private LocalDate maturityDate;

    @Column(name = "underwriting_noi", nullable = false)
    private BigDecimal underwritingNoi;
}
