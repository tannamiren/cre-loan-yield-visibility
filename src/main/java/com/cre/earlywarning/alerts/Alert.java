package com.cre.earlywarning.alerts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "alert", uniqueConstraints = @UniqueConstraint(columnNames = {"loan_id", "rule_id"}))
@Getter
@Setter
@NoArgsConstructor
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loan_id", nullable = false)
    private String loanId;

    @Column(name = "rule_id", nullable = false)
    private String ruleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private AlertState state;

    @Column(name = "score", nullable = false)
    private int score;

    @Column(name = "score_type", nullable = false)
    private int scoreType;

    @Column(name = "score_time", nullable = false)
    private int scoreTime;

    @Column(name = "score_size", nullable = false)
    private int scoreSize;

    @Column(name = "rule_version", nullable = false)
    private int ruleVersion;

    @Column(name = "fired_month", nullable = false)
    private String firedMonth;

    @Column(name = "clear_months_count", nullable = false)
    private int clearMonthsCount;

    @Column(name = "inputs_json", nullable = false, length = 1000)
    private String inputsJson;

    @Column(name = "limit_value", nullable = false)
    private String limitValue;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Alert(String loanId, String ruleId) {
        this.loanId = loanId;
        this.ruleId = ruleId;
        this.state = AlertState.OPEN;
        this.clearMonthsCount = 0;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }
}
