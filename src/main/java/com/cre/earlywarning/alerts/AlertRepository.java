package com.cre.earlywarning.alerts;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    Optional<Alert> findByLoanIdAndRuleId(String loanId, String ruleId);

    List<Alert> findByLoanId(String loanId);

    List<Alert> findByStateOrderByScoreDesc(AlertState state);
}
