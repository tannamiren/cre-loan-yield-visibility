package com.cre.earlywarning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "loan_month_event", uniqueConstraints = @UniqueConstraint(columnNames = {"loan_id", "month"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LoanMonthEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loan_id", nullable = false)
    private String loanId;

    @Column(name = "month", nullable = false)
    private String month;

    @Column(name = "balance", nullable = false)
    private BigDecimal balance;

    @Column(name = "noi", nullable = false)
    private BigDecimal noi;

    @Column(name = "yearly_payments", nullable = false)
    private BigDecimal yearlyPayments;

    @Column(name = "payments_late", nullable = false)
    private int paymentsLate;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
}
