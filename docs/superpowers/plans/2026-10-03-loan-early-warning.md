# Loan Surveillance Early-Warning Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Spring Boot service that ingests monthly CRE loan reports, runs 5 fire-explainable rules (no LLM/ML), and surfaces ranked, auditable alerts via a REST API and two Thymeleaf screens.

**Architecture:** One Java 21 / Spring Boot 3 service implementing an append-only event pipeline: seeded data generator -> CSV files -> ShedLock-guarded folder poller -> idempotent ingest -> event log -> metrics -> rule engine -> alert service (scoring + 3-state lifecycle) -> REST API -> Thymeleaf screens.

**Tech Stack:** Java 21, Spring Boot 3.3.4, Maven, MySQL 8 (Docker Compose), H2 (MySQL mode, unit/scenario tests), Testcontainers-MySQL (concurrency/idempotency integration tests), Flyway, ShedLock (JDBC provider), Lombok, Thymeleaf, Chart.js (CDN).

## Global Constraints

- No LLM, no ML. Every alert must trace to a rule ID, rule version, and the exact input values that fired it. (spec section 1)
- DSCR and debt yield are computed with `BigDecimal`, rounded to 4 decimal places. (spec section 4.3)
- Event key is `loan_id + month`; re-ingesting the same key is a no-op, enforced by a DB unique constraint, not just application logic. (spec section 11 "Safe intake"; design doc section 2)
- ShedLock (JDBC provider on the MySQL `shedlock` table) ensures exactly one node runs the folder poller at a time; `lockAtMostFor` 5m, `lockAtLeastFor` 5s. (spec section 4, "ShedLock")
- Poller runs every 10 seconds, skips files modified in the last 5 seconds, moves files `inbox/` -> `processing/` -> `done/`/`failed/` atomically. (spec section 4, "Poller steps")
- Rules are plain Java classes (no rules framework); limits live in versioned YAML; every alert stores which rule version fired it. (spec section 5; design doc section 2)
- Do not build: broker view, tenant-lease rules, refinance gap, loan-to-value, alert escalation, analyst comment form, real SEC data import, Kafka, login. (spec section 10)
- H2 (MySQL mode) is used for fast unit/rule/scenario tests; Testcontainers-MySQL is additive, used only for the two concurrency-sensitive integration tests (two-node poller, double-ingest). (design doc section 3)
- Chart.js is loaded via CDN `<script>` tag only — no bundler, no npm step. (design doc section 1)
- Screens are server-rendered Thymeleaf; one deployable unit. (spec section 8)
- REST contracts are fixed: `GET /alerts`, `GET /loans/{id}`, `POST /alerts/{id}/acknowledge`. (spec section 7)

## Declared Assumptions (not in spec, needed to make it buildable)

- **Amortization**: all 100 loans fully amortize over 30 years (no interest-only branch) — the spec's IO carve-out needs a per-loan flag the spec never defines, so it's dropped as unused optionality.
- **Balance over time**: for the 97 "normal" loans, `balance` is held constant at `original_balance` across the 24-month horizon (no amortization curve simulated) — debt yield math is unaffected since it operates on whatever balance value is in the row; this only affects data realism, not rule correctness.
- **R3 "90 days"**: approximated as 3 months, since the system only has monthly-granularity data and mixing day/month units would be ambiguous.
- **Alert re-opening**: if a rule fires again for an alert that is currently `ACKNOWLEDGED` or `RESOLVED`, the alert reopens to `OPEN` (spec says "update the alert," doesn't say what happens to its state across firings — reopening matches the intent that recurring risk should resurface in the queue).
- **Score "loan size"**: scaled against `original_balance`, not current month's balance (loan size is a static property of the loan).

---

## Task 1: Project Scaffolding & Build Config

**Files:**
- Create: `pom.xml`
- Create: `docker-compose.yml`
- Create: `src/main/resources/application.yml`
- Create: `src/main/java/com/cre/earlywarning/EarlyWarningApplication.java`
- Create: `src/test/resources/application.yml`
- Create: `src/test/java/com/cre/earlywarning/EarlyWarningApplicationTests.java`
- Create: `.gitignore`

**Interfaces:**
- Produces: a bootable Spring Boot app on port 8080, package base `com.cre.earlywarning`, with H2 active in the test classpath and MySQL active at runtime.

- [ ] **Step 1: Write `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.4</version>
    <relativePath/>
  </parent>

  <groupId>com.cre</groupId>
  <artifactId>earlywarning</artifactId>
  <version>0.1.0</version>
  <name>earlywarning</name>
  <description>Loan Surveillance Early-Warning Engine</description>

  <properties>
    <java.version>21</java.version>
    <shedlock.version>5.13.0</shedlock.version>
    <testcontainers.version>1.20.1</testcontainers.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-thymeleaf</artifactId>
    </dependency>
    <dependency>
      <groupId>com.mysql</groupId>
      <artifactId>mysql-connector-j</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>com.h2database</groupId>
      <artifactId>h2</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-mysql</artifactId>
    </dependency>
    <dependency>
      <groupId>net.javacrumbs.shedlock</groupId>
      <artifactId>shedlock-spring</artifactId>
      <version>${shedlock.version}</version>
    </dependency>
    <dependency>
      <groupId>net.javacrumbs.shedlock</groupId>
      <artifactId>shedlock-provider-jdbc-template</artifactId>
      <version>${shedlock.version}</version>
    </dependency>
    <dependency>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
      <optional>true</optional>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>${testcontainers.version}</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>mysql</artifactId>
      <version>${testcontainers.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Write `docker-compose.yml`**

```yaml
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_DATABASE: earlywarning
      MYSQL_USER: earlywarning
      MYSQL_PASSWORD: earlywarning
      MYSQL_ROOT_PASSWORD: root
    ports:
      - "3306:3306"
    volumes:
      - mysql_data:/var/lib/mysql
volumes:
  mysql_data:
```

- [ ] **Step 3: Write `src/main/resources/application.yml`**

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/earlywarning
    username: earlywarning
    password: earlywarning
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
server:
  port: 8080
app:
  intake:
    inbox-dir: inbox
    processing-dir: processing
    done-dir: done
    failed-dir: failed
  rules:
    active-version: 1
  generator:
    seed: 42
    output-dir: inbox
```

- [ ] **Step 4: Write `src/main/java/com/cre/earlywarning/EarlyWarningApplication.java`**

```java
package com.cre.earlywarning;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EarlyWarningApplication {
    public static void main(String[] args) {
        SpringApplication.run(EarlyWarningApplication.class, args);
    }
}
```

- [ ] **Step 5: Write `src/test/resources/application.yml`**

```yaml
spring:
  datasource:
    url: "jdbc:h2:mem:testdb;MODE=MySQL;DB_CLOSE_DELAY=-1"
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
    locations: classpath:db/migration
app:
  intake:
    inbox-dir: build/test-data/inbox
    processing-dir: build/test-data/processing
    done-dir: build/test-data/done
    failed-dir: build/test-data/failed
  rules:
    active-version: 1
  generator:
    seed: 42
    output-dir: build/test-data/inbox
```

- [ ] **Step 6: Write the smoke test `src/test/java/com/cre/earlywarning/EarlyWarningApplicationTests.java`**

```java
package com.cre.earlywarning;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class EarlyWarningApplicationTests {
    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 7: Write `.gitignore`**

```
target/
build/
*.class
.idea/
*.iml
inbox/*.csv
processing/*.csv
done/*.csv
failed/*.csv
```

- [ ] **Step 8: Run the build and confirm the app context loads**

Run: `mvn -q test -Dtest=EarlyWarningApplicationTests`
Expected: BUILD SUCCESS (this will fail until Task 2 adds the Flyway migration, since Flyway is enabled with no migrations yet — if it fails with a Flyway/schema error at this step, that's expected; proceed to Task 2 before re-running. If it instead fails to even start Spring context for an unrelated reason, stop and fix before continuing.)

- [ ] **Step 9: Commit**

```bash
git add pom.xml docker-compose.yml src .gitignore
git commit -m "chore: scaffold Spring Boot project, Docker Compose, and test config"
```

---

## Task 2: Database Schema & Rule Config YAML

**Files:**
- Create: `src/main/resources/db/migration/V1__init_schema.sql`
- Create: `src/main/resources/rules/rules-v1.yaml`

**Interfaces:**
- Produces: tables `loan`, `loan_month_event`, `alert`, `shedlock`, applied identically on MySQL (runtime) and H2 MySQL-mode (tests) via Flyway. Produces `rules-v1.yaml` on the classpath, loaded by Task 5's `RuleConfigLoader`.

- [ ] **Step 1: Write `src/main/resources/db/migration/V1__init_schema.sql`**

```sql
CREATE TABLE loan (
    loan_id VARCHAR(20) NOT NULL PRIMARY KEY,
    property_type VARCHAR(20) NOT NULL,
    original_balance DECIMAL(15,2) NOT NULL,
    rate DECIMAL(6,4) NOT NULL,
    maturity_date DATE NOT NULL,
    underwriting_noi DECIMAL(15,2) NOT NULL
);

CREATE TABLE loan_month_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    loan_id VARCHAR(20) NOT NULL,
    month VARCHAR(7) NOT NULL,
    balance DECIMAL(15,2) NOT NULL,
    noi DECIMAL(15,2) NOT NULL,
    yearly_payments DECIMAL(15,2) NOT NULL,
    payments_late INT NOT NULL,
    received_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_loan_month UNIQUE (loan_id, month)
);

CREATE TABLE alert (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    loan_id VARCHAR(20) NOT NULL,
    rule_id VARCHAR(10) NOT NULL,
    state VARCHAR(20) NOT NULL,
    score INT NOT NULL,
    score_type INT NOT NULL,
    score_time INT NOT NULL,
    score_size INT NOT NULL,
    rule_version INT NOT NULL,
    fired_month VARCHAR(7) NOT NULL,
    clear_months_count INT NOT NULL DEFAULT 0,
    inputs_json VARCHAR(1000) NOT NULL,
    limit_value VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_loan_rule UNIQUE (loan_id, rule_id)
);

CREATE TABLE shedlock (
    name VARCHAR(64) NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at TIMESTAMP(3) NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);
```

- [ ] **Step 2: Write `src/main/resources/rules/rules-v1.yaml`**

```yaml
version: 1
limits:
  r1LatePaymentsThreshold: 2
  r2LowDscrThreshold: 1.10
  r3MaturitySoonMonths: 3
  r4DscrFallDelta: 0.15
  r4DscrFallMonths: 6
  r5LowDebtYieldThreshold: 0.08
  r5MaturityMonths: 18
```

- [ ] **Step 3: Run the smoke test again now that a migration exists**

Run: `mvn -q test -Dtest=EarlyWarningApplicationTests`
Expected: PASS — Flyway applies `V1__init_schema.sql` against the H2 (MySQL mode) test database and the Spring context loads cleanly.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/db src/main/resources/rules
git commit -m "feat: add initial schema migration and rules-v1 config"
```

---

## Task 3: Domain Model & Event Log

**Files:**
- Create: `src/main/java/com/cre/earlywarning/domain/Loan.java`
- Create: `src/main/java/com/cre/earlywarning/domain/LoanRepository.java`
- Create: `src/main/java/com/cre/earlywarning/domain/LoanMonthEvent.java`
- Create: `src/main/java/com/cre/earlywarning/domain/LoanMonthEventRepository.java`
- Create: `src/main/java/com/cre/earlywarning/domain/EventLog.java`
- Create: `src/main/java/com/cre/earlywarning/domain/JpaEventLog.java`
- Test: `src/test/java/com/cre/earlywarning/domain/JpaEventLogTest.java`

**Interfaces:**
- Produces: `Loan` (JPA entity, PK `loanId`), `LoanMonthEvent` (JPA entity), `LoanRepository extends JpaRepository<Loan,String>` with `findMaxOriginalBalance()`, `LoanMonthEventRepository extends JpaRepository<LoanMonthEvent,Long>` with `findByLoanIdOrderByMonthAsc(String)` and `existsByLoanIdAndMonth(String,String)`, `EventLog` interface with `boolean append(String loanId, YearMonth month, BigDecimal balance, BigDecimal noi, BigDecimal yearlyPayments, int paymentsLate)` — returns `true` if newly inserted, `false` if it was already present (the idempotency signal Task 7's `IngestService` uses to skip rule evaluation on duplicates).
- Consumes: nothing from earlier tasks (first domain code).

- [ ] **Step 1: Write the failing test `src/test/java/com/cre/earlywarning/domain/JpaEventLogTest.java`**

```java
package com.cre.earlywarning.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class JpaEventLogTest {

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private EventLog eventLog;

    @Autowired
    private LoanMonthEventRepository eventRepository;

    @Test
    void firstAppendInsertsEvent() {
        loanRepository.save(new Loan("L999", "APARTMENT", new BigDecimal("1000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2027, 1, 1), new BigDecimal("90000.00")));

        boolean inserted = eventLog.append("L999", YearMonth.of(2024, 1),
            new BigDecimal("1000000.00"), new BigDecimal("90000.00"),
            new BigDecimal("68000.00"), 0);

        assertThat(inserted).isTrue();
        assertThat(eventRepository.findByLoanIdOrderByMonthAsc("L999")).hasSize(1);
    }

    @Test
    void duplicateAppendIsNoOpAndReportsFalse() {
        loanRepository.save(new Loan("L998", "OFFICE", new BigDecimal("2000000.00"),
            new BigDecimal("0.0600"), LocalDate.of(2027, 1, 1), new BigDecimal("150000.00")));

        boolean first = eventLog.append("L998", YearMonth.of(2024, 3),
            new BigDecimal("2000000.00"), new BigDecimal("150000.00"),
            new BigDecimal("140000.00"), 0);
        boolean second = eventLog.append("L998", YearMonth.of(2024, 3),
            new BigDecimal("2000000.00"), new BigDecimal("999999.00"), // different NOI on purpose
            new BigDecimal("140000.00"), 0);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(eventRepository.findByLoanIdOrderByMonthAsc("L998")).hasSize(1);
        // the first write wins; the duplicate's different NOI must not have overwritten it
        assertThat(eventRepository.findByLoanIdOrderByMonthAsc("L998").get(0).getNoi())
            .isEqualByComparingTo("150000.00");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile (the classes don't exist yet)**

Run: `mvn -q test -Dtest=JpaEventLogTest`
Expected: COMPILATION ERROR — `Loan`, `LoanRepository`, `EventLog`, `LoanMonthEventRepository` are not yet defined.

- [ ] **Step 3: Write `src/main/java/com/cre/earlywarning/domain/Loan.java`**

```java
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
```

- [ ] **Step 4: Write `src/main/java/com/cre/earlywarning/domain/LoanRepository.java`**

```java
package com.cre.earlywarning.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;

public interface LoanRepository extends JpaRepository<Loan, String> {

    @Query("select max(l.originalBalance) from Loan l")
    BigDecimal findMaxOriginalBalance();
}
```

- [ ] **Step 5: Write `src/main/java/com/cre/earlywarning/domain/LoanMonthEvent.java`**

```java
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
```

- [ ] **Step 6: Write `src/main/java/com/cre/earlywarning/domain/LoanMonthEventRepository.java`**

```java
package com.cre.earlywarning.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoanMonthEventRepository extends JpaRepository<LoanMonthEvent, Long> {

    List<LoanMonthEvent> findByLoanIdOrderByMonthAsc(String loanId);

    boolean existsByLoanIdAndMonth(String loanId, String month);
}
```

- [ ] **Step 7: Write `src/main/java/com/cre/earlywarning/domain/EventLog.java`**

```java
package com.cre.earlywarning.domain;

import java.math.BigDecimal;
import java.time.YearMonth;

public interface EventLog {

    /**
     * Appends one loan-month report row. Returns true if this call inserted a new event,
     * false if an event already existed for this loanId+month (idempotent no-op).
     */
    boolean append(String loanId, YearMonth month, BigDecimal balance, BigDecimal noi,
                    BigDecimal yearlyPayments, int paymentsLate);
}
```

- [ ] **Step 8: Write `src/main/java/com/cre/earlywarning/domain/JpaEventLog.java`**

```java
package com.cre.earlywarning.domain;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;

@Component
public class JpaEventLog implements EventLog {

    private final LoanMonthEventRepository repository;

    public JpaEventLog(LoanMonthEventRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean append(String loanId, YearMonth month, BigDecimal balance, BigDecimal noi,
                           BigDecimal yearlyPayments, int paymentsLate) {
        String monthKey = month.toString();
        if (repository.existsByLoanIdAndMonth(loanId, monthKey)) {
            return false;
        }
        LoanMonthEvent event = new LoanMonthEvent(null, loanId, monthKey, balance, noi,
            yearlyPayments, paymentsLate, Instant.now());
        try {
            repository.save(event);
            return true;
        } catch (DataIntegrityViolationException raceLoserUniqueConstraintViolation) {
            // another writer inserted the same loanId+month between our existsBy check and save;
            // the unique constraint is the real idempotency guarantee, this check is just the fast path
            return false;
        }
    }
}
```

- [ ] **Step 9: Run the test to verify it passes**

Run: `mvn -q test -Dtest=JpaEventLogTest`
Expected: PASS (2 tests)

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/cre/earlywarning/domain src/test/java/com/cre/earlywarning/domain
git commit -m "feat: add Loan/LoanMonthEvent entities and idempotent EventLog"
```

---

## Task 4: Metrics Calculator

**Files:**
- Create: `src/main/java/com/cre/earlywarning/metrics/LoanMetrics.java`
- Create: `src/main/java/com/cre/earlywarning/metrics/MetricsCalculator.java`
- Test: `src/test/java/com/cre/earlywarning/metrics/MetricsCalculatorTest.java`

**Interfaces:**
- Consumes: `Loan` and `LoanMonthEvent` from Task 3 (`loan.getMaturityDate()`, `event.getMonth()/getBalance()/getNoi()/getYearlyPayments()/getPaymentsLate()`).
- Produces: `LoanMetrics` record `(YearMonth month, BigDecimal balance, BigDecimal noi, BigDecimal yearlyPayments, int paymentsLate, BigDecimal dscr, BigDecimal debtYield, long monthsToMaturity)`, and `MetricsCalculator.calculate(Loan loan, LoanMonthEvent event): LoanMetrics` — this is the exact signature Task 5's `RuleEngine` calls.

- [ ] **Step 1: Write the failing test `src/test/java/com/cre/earlywarning/metrics/MetricsCalculatorTest.java`**

```java
package com.cre.earlywarning.metrics;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanMonthEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsCalculatorTest {

    private final MetricsCalculator calculator = new MetricsCalculator();

    @Test
    void computesDscrAndDebtYieldRoundedToFourDecimalPlaces() {
        Loan loan = new Loan("L001", "APARTMENT", new BigDecimal("25000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2030, 1, 1), new BigDecimal("2000000.00"));
        LoanMonthEvent event = new LoanMonthEvent(1L, "L001", "2024-01",
            new BigDecimal("25000000.00"), new BigDecimal("2000000.00"),
            new BigDecimal("1600000.00"), 0, Instant.now());

        LoanMetrics metrics = calculator.calculate(loan, event);

        assertThat(metrics.dscr()).isEqualByComparingTo("1.2500");
        assertThat(metrics.debtYield()).isEqualByComparingTo("0.0800");
    }

    @Test
    void computesMonthsToMaturityFromReportMonthToMaturityMonth() {
        Loan loan = new Loan("L002", "OFFICE", new BigDecimal("10000000.00"),
            new BigDecimal("0.0600"), LocalDate.of(2024, 10, 15), new BigDecimal("900000.00"));
        LoanMonthEvent event = new LoanMonthEvent(2L, "L002", "2024-01",
            new BigDecimal("10000000.00"), new BigDecimal("900000.00"),
            new BigDecimal("700000.00"), 0, Instant.now());

        LoanMetrics metrics = calculator.calculate(loan, event);

        // 2024-01-01 to 2024-10-01 is 9 whole months
        assertThat(metrics.monthsToMaturity()).isEqualTo(9L);
        assertThat(metrics.month()).isEqualTo(YearMonth.of(2024, 1));
    }

    @Test
    void roundsNonTerminatingDivisionHalfUp() {
        Loan loan = new Loan("L003", "RETAIL", new BigDecimal("3000000.00"),
            new BigDecimal("0.0650"), LocalDate.of(2030, 1, 1), new BigDecimal("300000.00"));
        LoanMonthEvent event = new LoanMonthEvent(3L, "L003", "2024-06",
            new BigDecimal("3000000.00"), new BigDecimal("100000.00"),
            new BigDecimal("300000.00"), 0, Instant.now());

        LoanMetrics metrics = calculator.calculate(loan, event);

        // 100000 / 300000 = 0.33333... -> 0.3333
        assertThat(metrics.dscr()).isEqualByComparingTo("0.3333");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `mvn -q test -Dtest=MetricsCalculatorTest`
Expected: COMPILATION ERROR — `LoanMetrics` and `MetricsCalculator` don't exist yet.

- [ ] **Step 3: Write `src/main/java/com/cre/earlywarning/metrics/LoanMetrics.java`**

```java
package com.cre.earlywarning.metrics;

import java.math.BigDecimal;
import java.time.YearMonth;

public record LoanMetrics(
    YearMonth month,
    BigDecimal balance,
    BigDecimal noi,
    BigDecimal yearlyPayments,
    int paymentsLate,
    BigDecimal dscr,
    BigDecimal debtYield,
    long monthsToMaturity
) {
}
```

- [ ] **Step 4: Write `src/main/java/com/cre/earlywarning/metrics/MetricsCalculator.java`**

```java
package com.cre.earlywarning.metrics;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanMonthEvent;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

@Component
public class MetricsCalculator {

    public LoanMetrics calculate(Loan loan, LoanMonthEvent event) {
        YearMonth month = YearMonth.parse(event.getMonth());
        BigDecimal dscr = event.getNoi().divide(event.getYearlyPayments(), 4, RoundingMode.HALF_UP);
        BigDecimal debtYield = event.getNoi().divide(event.getBalance(), 4, RoundingMode.HALF_UP);
        long monthsToMaturity = ChronoUnit.MONTHS.between(
            month.atDay(1), loan.getMaturityDate().withDayOfMonth(1));

        return new LoanMetrics(month, event.getBalance(), event.getNoi(), event.getYearlyPayments(),
            event.getPaymentsLate(), dscr, debtYield, monthsToMaturity);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -q test -Dtest=MetricsCalculatorTest`
Expected: PASS (3 tests)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/cre/earlywarning/metrics src/test/java/com/cre/earlywarning/metrics
git commit -m "feat: add MetricsCalculator for DSCR, debt yield, months-to-maturity"
```

---

## Task 5: Rule Config Loading

**Files:**
- Create: `src/main/java/com/cre/earlywarning/rules/RuleConfig.java`
- Create: `src/main/java/com/cre/earlywarning/rules/RuleConfigLoader.java`
- Create: `src/main/java/com/cre/earlywarning/rules/RuleConfigConfiguration.java`
- Test: `src/test/java/com/cre/earlywarning/rules/RuleConfigLoaderTest.java`

**Interfaces:**
- Consumes: `src/main/resources/rules/rules-v1.yaml` from Task 2.
- Produces: `RuleConfig` record `(int version, int r1LatePaymentsThreshold, BigDecimal r2LowDscrThreshold, int r3MaturitySoonMonths, BigDecimal r4DscrFallDelta, int r4DscrFallMonths, BigDecimal r5LowDebtYieldThreshold, int r5MaturityMonths)`, loaded once at startup as a Spring bean — Task 6's rule classes take `RuleConfig` as a constructor dependency.

- [ ] **Step 1: Write the failing test `src/test/java/com/cre/earlywarning/rules/RuleConfigLoaderTest.java`**

```java
package com.cre.earlywarning.rules;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RuleConfigLoaderTest {

    @Test
    void loadsVersion1LimitsFromClasspathYaml() {
        RuleConfigLoader loader = new RuleConfigLoader(new DefaultResourceLoader(), 1);

        RuleConfig config = loader.loadActive();

        assertThat(config.version()).isEqualTo(1);
        assertThat(config.r1LatePaymentsThreshold()).isEqualTo(2);
        assertThat(config.r2LowDscrThreshold()).isEqualByComparingTo("1.10");
        assertThat(config.r3MaturitySoonMonths()).isEqualTo(3);
        assertThat(config.r4DscrFallDelta()).isEqualByComparingTo("0.15");
        assertThat(config.r4DscrFallMonths()).isEqualTo(6);
        assertThat(config.r5LowDebtYieldThreshold()).isEqualByComparingTo("0.08");
        assertThat(config.r5MaturityMonths()).isEqualTo(18);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `mvn -q test -Dtest=RuleConfigLoaderTest`
Expected: COMPILATION ERROR — `RuleConfig` and `RuleConfigLoader` don't exist yet.

- [ ] **Step 3: Write `src/main/java/com/cre/earlywarning/rules/RuleConfig.java`**

```java
package com.cre.earlywarning.rules;

import java.math.BigDecimal;

public record RuleConfig(
    int version,
    int r1LatePaymentsThreshold,
    BigDecimal r2LowDscrThreshold,
    int r3MaturitySoonMonths,
    BigDecimal r4DscrFallDelta,
    int r4DscrFallMonths,
    BigDecimal r5LowDebtYieldThreshold,
    int r5MaturityMonths
) {
}
```

- [ ] **Step 4: Write `src/main/java/com/cre/earlywarning/rules/RuleConfigLoader.java`**

```java
package com.cre.earlywarning.rules;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Map;

public class RuleConfigLoader {

    private final ResourceLoader resourceLoader;
    private final int activeVersion;

    public RuleConfigLoader(ResourceLoader resourceLoader, int activeVersion) {
        this.resourceLoader = resourceLoader;
        this.activeVersion = activeVersion;
    }

    public RuleConfig loadActive() {
        return load(activeVersion);
    }

    @SuppressWarnings("unchecked")
    public RuleConfig load(int version) {
        Resource resource = resourceLoader.getResource("classpath:rules/rules-v" + version + ".yaml");
        try (InputStream in = resource.getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> limits = (Map<String, Object>) root.get("limits");
            return new RuleConfig(
                (Integer) root.get("version"),
                (Integer) limits.get("r1LatePaymentsThreshold"),
                new BigDecimal(limits.get("r2LowDscrThreshold").toString()),
                (Integer) limits.get("r3MaturitySoonMonths"),
                new BigDecimal(limits.get("r4DscrFallDelta").toString()),
                (Integer) limits.get("r4DscrFallMonths"),
                new BigDecimal(limits.get("r5LowDebtYieldThreshold").toString()),
                (Integer) limits.get("r5MaturityMonths")
            );
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load rule config version " + version, e);
        }
    }
}
```

- [ ] **Step 5: Write `src/main/java/com/cre/earlywarning/rules/RuleConfigConfiguration.java`**

```java
package com.cre.earlywarning.rules;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

@Configuration
public class RuleConfigConfiguration {

    @Bean
    public RuleConfig ruleConfig(ResourceLoader resourceLoader,
                                  @Value("${app.rules.active-version}") int activeVersion) {
        return new RuleConfigLoader(resourceLoader, activeVersion).loadActive();
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn -q test -Dtest=RuleConfigLoaderTest`
Expected: PASS (1 test)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/cre/earlywarning/rules src/test/java/com/cre/earlywarning/rules
git commit -m "feat: load versioned rule config from classpath YAML"
```

---

## Task 6: Rules R1-R5 and the Rule Engine

**Files:**
- Create: `src/main/java/com/cre/earlywarning/rules/RuleType.java`
- Create: `src/main/java/com/cre/earlywarning/rules/RuleEvaluation.java`
- Create: `src/main/java/com/cre/earlywarning/rules/LoanMonthContext.java`
- Create: `src/main/java/com/cre/earlywarning/rules/Rule.java`
- Create: `src/main/java/com/cre/earlywarning/rules/R1LatePaymentsRule.java`
- Create: `src/main/java/com/cre/earlywarning/rules/R2LowDscrRule.java`
- Create: `src/main/java/com/cre/earlywarning/rules/R3MaturitySoonRule.java`
- Create: `src/main/java/com/cre/earlywarning/rules/R4DscrFallingRule.java`
- Create: `src/main/java/com/cre/earlywarning/rules/R5LowDebtYieldNearMaturityRule.java`
- Create: `src/main/java/com/cre/earlywarning/rules/RuleEngine.java`
- Test: `src/test/java/com/cre/earlywarning/rules/R1LatePaymentsRuleTest.java`
- Test: `src/test/java/com/cre/earlywarning/rules/R2LowDscrRuleTest.java`
- Test: `src/test/java/com/cre/earlywarning/rules/R3MaturitySoonRuleTest.java`
- Test: `src/test/java/com/cre/earlywarning/rules/R4DscrFallingRuleTest.java`
- Test: `src/test/java/com/cre/earlywarning/rules/R5LowDebtYieldNearMaturityRuleTest.java`
- Test: `src/test/java/com/cre/earlywarning/rules/RuleEngineTest.java`

**Interfaces:**
- Consumes: `LoanMetrics`/`MetricsCalculator` (Task 4), `Loan`/`LoanMonthEventRepository`/`LoanRepository` (Task 3), `RuleConfig` bean (Task 5).
- Produces: `Rule` interface (`String id()`, `RuleEvaluation evaluate(LoanMonthContext context)`), `RuleEvaluation` record `(String ruleId, RuleType type, boolean fired, Map<String,String> inputs, String limit, int ruleVersion)`, `RuleType` enum `{CREDIT, EARLY_WARNING}`, `LoanMonthContext` record `(Loan loan, LoanMetrics current, List<LoanMetrics> history)` with `Optional<LoanMetrics> monthsAgo(int n)`, and `RuleEngine.evaluate(String loanId, YearMonth month): List<RuleEvaluation>` — this exact signature is what Task 7's `IngestService` calls.

- [ ] **Step 1: Write `src/main/java/com/cre/earlywarning/rules/RuleType.java`**

```java
package com.cre.earlywarning.rules;

public enum RuleType {
    CREDIT,
    EARLY_WARNING
}
```

- [ ] **Step 2: Write `src/main/java/com/cre/earlywarning/rules/RuleEvaluation.java`**

```java
package com.cre.earlywarning.rules;

import java.util.Map;

public record RuleEvaluation(
    String ruleId,
    RuleType type,
    boolean fired,
    Map<String, String> inputs,
    String limit,
    int ruleVersion
) {
}
```

- [ ] **Step 3: Write `src/main/java/com/cre/earlywarning/rules/LoanMonthContext.java`**

```java
package com.cre.earlywarning.rules;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.metrics.LoanMetrics;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

public record LoanMonthContext(Loan loan, LoanMetrics current, List<LoanMetrics> history) {

    public Optional<LoanMetrics> monthsAgo(int n) {
        YearMonth target = current.month().minusMonths(n);
        return history.stream().filter(m -> m.month().equals(target)).findFirst();
    }
}
```

- [ ] **Step 4: Write `src/main/java/com/cre/earlywarning/rules/Rule.java`**

```java
package com.cre.earlywarning.rules;

public interface Rule {
    String id();

    RuleEvaluation evaluate(LoanMonthContext context);
}
```

- [ ] **Step 5: Write `src/main/java/com/cre/earlywarning/rules/R1LatePaymentsRule.java`**

```java
package com.cre.earlywarning.rules;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class R1LatePaymentsRule implements Rule {

    private final RuleConfig config;

    public R1LatePaymentsRule(RuleConfig config) {
        this.config = config;
    }

    @Override
    public String id() {
        return "R1";
    }

    @Override
    public RuleEvaluation evaluate(LoanMonthContext context) {
        int paymentsLate = context.current().paymentsLate();
        boolean fired = paymentsLate >= config.r1LatePaymentsThreshold();
        return new RuleEvaluation(id(), RuleType.CREDIT, fired,
            Map.of("paymentsLate", String.valueOf(paymentsLate)),
            String.valueOf(config.r1LatePaymentsThreshold()), config.version());
    }
}
```

- [ ] **Step 6: Write `src/main/java/com/cre/earlywarning/rules/R2LowDscrRule.java`**

```java
package com.cre.earlywarning.rules;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class R2LowDscrRule implements Rule {

    private final RuleConfig config;

    public R2LowDscrRule(RuleConfig config) {
        this.config = config;
    }

    @Override
    public String id() {
        return "R2";
    }

    @Override
    public RuleEvaluation evaluate(LoanMonthContext context) {
        var dscr = context.current().dscr();
        boolean fired = dscr.compareTo(config.r2LowDscrThreshold()) < 0;
        return new RuleEvaluation(id(), RuleType.CREDIT, fired,
            Map.of("dscr", dscr.toString()),
            config.r2LowDscrThreshold().toString(), config.version());
    }
}
```

- [ ] **Step 7: Write `src/main/java/com/cre/earlywarning/rules/R3MaturitySoonRule.java`**

```java
package com.cre.earlywarning.rules;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class R3MaturitySoonRule implements Rule {

    private final RuleConfig config;

    public R3MaturitySoonRule(RuleConfig config) {
        this.config = config;
    }

    @Override
    public String id() {
        return "R3";
    }

    @Override
    public RuleEvaluation evaluate(LoanMonthContext context) {
        long monthsToMaturity = context.current().monthsToMaturity();
        boolean fired = monthsToMaturity <= config.r3MaturitySoonMonths();
        return new RuleEvaluation(id(), RuleType.CREDIT, fired,
            Map.of("monthsToMaturity", String.valueOf(monthsToMaturity)),
            String.valueOf(config.r3MaturitySoonMonths()), config.version());
    }
}
```

- [ ] **Step 8: Write `src/main/java/com/cre/earlywarning/rules/R4DscrFallingRule.java`**

```java
package com.cre.earlywarning.rules;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

import com.cre.earlywarning.metrics.LoanMetrics;

@Component
public class R4DscrFallingRule implements Rule {

    private final RuleConfig config;

    public R4DscrFallingRule(RuleConfig config) {
        this.config = config;
    }

    @Override
    public String id() {
        return "R4";
    }

    @Override
    public RuleEvaluation evaluate(LoanMonthContext context) {
        Optional<LoanMetrics> past = context.monthsAgo(config.r4DscrFallMonths());
        if (past.isEmpty()) {
            return new RuleEvaluation(id(), RuleType.EARLY_WARNING, false,
                Map.of("reason", "insufficient history"),
                config.r4DscrFallDelta().toString(), config.version());
        }
        var fall = past.get().dscr().subtract(context.current().dscr());
        boolean fired = fall.compareTo(config.r4DscrFallDelta()) >= 0;
        Map<String, String> inputs = Map.of(
            "dscrNow", context.current().dscr().toString(),
            "dscrMonthsAgo", past.get().dscr().toString(),
            "fall", fall.toString());
        return new RuleEvaluation(id(), RuleType.EARLY_WARNING, fired, inputs,
            config.r4DscrFallDelta().toString(), config.version());
    }
}
```

- [ ] **Step 9: Write `src/main/java/com/cre/earlywarning/rules/R5LowDebtYieldNearMaturityRule.java`**

```java
package com.cre.earlywarning.rules;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class R5LowDebtYieldNearMaturityRule implements Rule {

    private final RuleConfig config;

    public R5LowDebtYieldNearMaturityRule(RuleConfig config) {
        this.config = config;
    }

    @Override
    public String id() {
        return "R5";
    }

    @Override
    public RuleEvaluation evaluate(LoanMonthContext context) {
        var debtYield = context.current().debtYield();
        long monthsToMaturity = context.current().monthsToMaturity();
        boolean fired = debtYield.compareTo(config.r5LowDebtYieldThreshold()) < 0
            && monthsToMaturity <= config.r5MaturityMonths();
        Map<String, String> inputs = Map.of(
            "debtYield", debtYield.toString(),
            "monthsToMaturity", String.valueOf(monthsToMaturity));
        String limit = "debtYield<" + config.r5LowDebtYieldThreshold()
            + " and monthsToMaturity<=" + config.r5MaturityMonths();
        return new RuleEvaluation(id(), RuleType.EARLY_WARNING, fired, inputs, limit, config.version());
    }
}
```

- [ ] **Step 10: Write `src/main/java/com/cre/earlywarning/rules/RuleEngine.java`**

```java
package com.cre.earlywarning.rules;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanMonthEvent;
import com.cre.earlywarning.domain.LoanMonthEventRepository;
import com.cre.earlywarning.domain.LoanRepository;
import com.cre.earlywarning.metrics.LoanMetrics;
import com.cre.earlywarning.metrics.MetricsCalculator;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;

@Component
public class RuleEngine {

    private final List<Rule> rules;
    private final LoanRepository loanRepository;
    private final LoanMonthEventRepository eventRepository;
    private final MetricsCalculator metricsCalculator;

    public RuleEngine(List<Rule> rules, LoanRepository loanRepository,
                       LoanMonthEventRepository eventRepository, MetricsCalculator metricsCalculator) {
        this.rules = rules;
        this.loanRepository = loanRepository;
        this.eventRepository = eventRepository;
        this.metricsCalculator = metricsCalculator;
    }

    public List<RuleEvaluation> evaluate(String loanId, YearMonth month) {
        Loan loan = loanRepository.findById(loanId)
            .orElseThrow(() -> new IllegalStateException("Unknown loan " + loanId));

        List<LoanMonthEvent> events = eventRepository.findByLoanIdOrderByMonthAsc(loanId);

        List<LoanMetrics> history = events.stream()
            .map(e -> metricsCalculator.calculate(loan, e))
            .filter(m -> !m.month().isAfter(month))
            .sorted(Comparator.comparing(LoanMetrics::month))
            .toList();

        LoanMetrics current = history.get(history.size() - 1);
        LoanMonthContext context = new LoanMonthContext(loan, current, history);

        return rules.stream().map(rule -> rule.evaluate(context)).toList();
    }
}
```

- [ ] **Step 11: Write `src/test/java/com/cre/earlywarning/rules/R1LatePaymentsRuleTest.java`**

```java
package com.cre.earlywarning.rules;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.metrics.LoanMetrics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class R1LatePaymentsRuleTest {

    private final RuleConfig config = new RuleConfig(1, 2, new BigDecimal("1.10"), 3,
        new BigDecimal("0.15"), 6, new BigDecimal("0.08"), 18);
    private final R1LatePaymentsRule rule = new R1LatePaymentsRule(config);

    @Test
    void firesWhenTwoOrMorePaymentsAreLate_matchingPlantedMissedPaymentsScenario() {
        // mirrors spec scenario 3: a loan goes 2 payments late, then later pays in full
        Loan loan = loan();
        LoanMetrics twoLate = metrics(YearMonth.of(2024, 6), 2);

        RuleEvaluation evaluation = rule.evaluate(new LoanMonthContext(loan, twoLate, List.of(twoLate)));

        assertThat(evaluation.fired()).isTrue();
        assertThat(evaluation.inputs()).containsEntry("paymentsLate", "2");
    }

    @Test
    void staysSilentWhenNoPaymentsAreLate() {
        Loan loan = loan();
        LoanMetrics current = metrics(YearMonth.of(2024, 7), 0);

        RuleEvaluation evaluation = rule.evaluate(new LoanMonthContext(loan, current, List.of(current)));

        assertThat(evaluation.fired()).isFalse();
    }

    private Loan loan() {
        return new Loan("L003", "APARTMENT", new BigDecimal("5000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2030, 1, 1), new BigDecimal("400000.00"));
    }

    private LoanMetrics metrics(YearMonth month, int paymentsLate) {
        return new LoanMetrics(month, new BigDecimal("5000000.00"), new BigDecimal("400000.00"),
            new BigDecimal("300000.00"), paymentsLate, new BigDecimal("1.3333"),
            new BigDecimal("0.0800"), 60L);
    }
}
```

- [ ] **Step 12: Write `src/test/java/com/cre/earlywarning/rules/R2LowDscrRuleTest.java`**

```java
package com.cre.earlywarning.rules;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.metrics.LoanMetrics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class R2LowDscrRuleTest {

    private final RuleConfig config = new RuleConfig(1, 2, new BigDecimal("1.10"), 3,
        new BigDecimal("0.15"), 6, new BigDecimal("0.08"), 18);
    private final R2LowDscrRule rule = new R2LowDscrRule(config);

    @Test
    void firesWhenDscrBelow1_10() {
        Loan loan = loan();
        LoanMetrics current = metrics(new BigDecimal("1.0500"));

        RuleEvaluation evaluation = rule.evaluate(new LoanMonthContext(loan, current, List.of(current)));

        assertThat(evaluation.fired()).isTrue();
    }

    @Test
    void staysSilentWhenDscrAtOrAboveThreshold() {
        Loan loan = loan();
        LoanMetrics current = metrics(new BigDecimal("1.1000"));

        RuleEvaluation evaluation = rule.evaluate(new LoanMonthContext(loan, current, List.of(current)));

        assertThat(evaluation.fired()).isFalse();
    }

    private Loan loan() {
        return new Loan("L004", "OFFICE", new BigDecimal("8000000.00"),
            new BigDecimal("0.0600"), LocalDate.of(2030, 1, 1), new BigDecimal("600000.00"));
    }

    private LoanMetrics metrics(BigDecimal dscr) {
        return new LoanMetrics(YearMonth.of(2024, 5), new BigDecimal("8000000.00"),
            new BigDecimal("600000.00"), new BigDecimal("550000.00"), 0, dscr,
            new BigDecimal("0.0750"), 60L);
    }
}
```

- [ ] **Step 13: Write `src/test/java/com/cre/earlywarning/rules/R3MaturitySoonRuleTest.java`**

```java
package com.cre.earlywarning.rules;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.metrics.LoanMetrics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class R3MaturitySoonRuleTest {

    private final RuleConfig config = new RuleConfig(1, 2, new BigDecimal("1.10"), 3,
        new BigDecimal("0.15"), 6, new BigDecimal("0.08"), 18);
    private final R3MaturitySoonRule rule = new R3MaturitySoonRule(config);

    @Test
    void firesWhenMaturityIsWithinThreeMonths() {
        Loan loan = loan();
        LoanMetrics current = metrics(2L);

        RuleEvaluation evaluation = rule.evaluate(new LoanMonthContext(loan, current, List.of(current)));

        assertThat(evaluation.fired()).isTrue();
    }

    @Test
    void staysSilentWhenMaturityIsFourMonthsOut() {
        Loan loan = loan();
        LoanMetrics current = metrics(4L);

        RuleEvaluation evaluation = rule.evaluate(new LoanMonthContext(loan, current, List.of(current)));

        assertThat(evaluation.fired()).isFalse();
    }

    private Loan loan() {
        return new Loan("L005", "RETAIL", new BigDecimal("4000000.00"),
            new BigDecimal("0.0575"), LocalDate.of(2024, 9, 1), new BigDecimal("350000.00"));
    }

    private LoanMetrics metrics(long monthsToMaturity) {
        return new LoanMetrics(YearMonth.of(2024, 7), new BigDecimal("4000000.00"),
            new BigDecimal("350000.00"), new BigDecimal("280000.00"), 0, new BigDecimal("1.2500"),
            new BigDecimal("0.0875"), monthsToMaturity);
    }
}
```

- [ ] **Step 14: Write `src/test/java/com/cre/earlywarning/rules/R4DscrFallingRuleTest.java`**

```java
package com.cre.earlywarning.rules;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.metrics.LoanMetrics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class R4DscrFallingRuleTest {

    private final RuleConfig config = new RuleConfig(1, 2, new BigDecimal("1.10"), 3,
        new BigDecimal("0.15"), 6, new BigDecimal("0.08"), 18);
    private final R4DscrFallingRule rule = new R4DscrFallingRule(config);

    @Test
    void firesWhenDscrFellAtLeastPoint15OverSixMonths() {
        Loan loan = loan();
        LoanMetrics sixMonthsAgo = metrics(YearMonth.of(2024, 1), new BigDecimal("1.4000"));
        LoanMetrics current = metrics(YearMonth.of(2024, 7), new BigDecimal("1.2000"));

        RuleEvaluation evaluation = rule.evaluate(
            new LoanMonthContext(loan, current, List.of(sixMonthsAgo, current)));

        assertThat(evaluation.fired()).isTrue();
        assertThat(evaluation.inputs()).containsEntry("fall", "0.2000");
    }

    @Test
    void staysSilentWhenFallIsSmallerThanDelta() {
        Loan loan = loan();
        LoanMetrics sixMonthsAgo = metrics(YearMonth.of(2024, 1), new BigDecimal("1.3000"));
        LoanMetrics current = metrics(YearMonth.of(2024, 7), new BigDecimal("1.2000"));

        RuleEvaluation evaluation = rule.evaluate(
            new LoanMonthContext(loan, current, List.of(sixMonthsAgo, current)));

        assertThat(evaluation.fired()).isFalse();
    }

    @Test
    void staysSilentWhenNoSixMonthHistoryExists() {
        Loan loan = loan();
        LoanMetrics current = metrics(YearMonth.of(2024, 2), new BigDecimal("1.2000"));

        RuleEvaluation evaluation = rule.evaluate(new LoanMonthContext(loan, current, List.of(current)));

        assertThat(evaluation.fired()).isFalse();
        assertThat(evaluation.inputs()).containsEntry("reason", "insufficient history");
    }

    private Loan loan() {
        return new Loan("L001", "APARTMENT", new BigDecimal("20000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2030, 1, 1), new BigDecimal("1600000.00"));
    }

    private LoanMetrics metrics(YearMonth month, BigDecimal dscr) {
        return new LoanMetrics(month, new BigDecimal("20000000.00"), new BigDecimal("1280000.00"),
            new BigDecimal("1280000.00"), 0, dscr, new BigDecimal("0.0640"), 60L);
    }
}
```

- [ ] **Step 15: Write `src/test/java/com/cre/earlywarning/rules/R5LowDebtYieldNearMaturityRuleTest.java`**

```java
package com.cre.earlywarning.rules;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.metrics.LoanMetrics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class R5LowDebtYieldNearMaturityRuleTest {

    private final RuleConfig config = new RuleConfig(1, 2, new BigDecimal("1.10"), 3,
        new BigDecimal("0.15"), 6, new BigDecimal("0.08"), 18);
    private final R5LowDebtYieldNearMaturityRule rule = new R5LowDebtYieldNearMaturityRule(config);

    @Test
    void firesWhenDebtYieldBelow8PercentAndMaturityWithin18Months_matchingWeakRefinanceScenario() {
        Loan loan = loan();
        LoanMetrics current = metrics(new BigDecimal("0.0700"), 9L);

        RuleEvaluation evaluation = rule.evaluate(new LoanMonthContext(loan, current, List.of(current)));

        assertThat(evaluation.fired()).isTrue();
    }

    @Test
    void staysSilentWhenDebtYieldIsHealthyEvenIfMaturityIsSoon() {
        Loan loan = loan();
        LoanMetrics current = metrics(new BigDecimal("0.1000"), 9L);

        RuleEvaluation evaluation = rule.evaluate(new LoanMonthContext(loan, current, List.of(current)));

        assertThat(evaluation.fired()).isFalse();
    }

    @Test
    void staysSilentWhenDebtYieldIsLowButMaturityIsFarOut() {
        Loan loan = loan();
        LoanMetrics current = metrics(new BigDecimal("0.0700"), 24L);

        RuleEvaluation evaluation = rule.evaluate(new LoanMonthContext(loan, current, List.of(current)));

        assertThat(evaluation.fired()).isFalse();
    }

    private Loan loan() {
        return new Loan("L002", "OFFICE", new BigDecimal("15000000.00"),
            new BigDecimal("0.0625"), LocalDate.of(2025, 1, 1), new BigDecimal("1100000.00"));
    }

    private LoanMetrics metrics(BigDecimal debtYield, long monthsToMaturity) {
        return new LoanMetrics(YearMonth.of(2024, 4), new BigDecimal("15000000.00"),
            new BigDecimal("1050000.00"), new BigDecimal("1050000.00"), 0, new BigDecimal("1.3000"),
            debtYield, monthsToMaturity);
    }
}
```

- [ ] **Step 16: Write `src/test/java/com/cre/earlywarning/rules/RuleEngineTest.java`**

```java
package com.cre.earlywarning.rules;

import com.cre.earlywarning.domain.EventLog;
import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class RuleEngineTest {

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private EventLog eventLog;

    @Autowired
    private RuleEngine ruleEngine;

    @Test
    void evaluatesAllFiveRulesForTheRequestedMonth() {
        loanRepository.save(new Loan("L900", "APARTMENT", new BigDecimal("10000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2030, 1, 1), new BigDecimal("800000.00")));
        eventLog.append("L900", YearMonth.of(2024, 1), new BigDecimal("10000000.00"),
            new BigDecimal("800000.00"), new BigDecimal("650000.00"), 0);

        List<RuleEvaluation> evaluations = ruleEngine.evaluate("L900", YearMonth.of(2024, 1));

        assertThat(evaluations).extracting(RuleEvaluation::ruleId)
            .containsExactlyInAnyOrder("R1", "R2", "R3", "R4", "R5");
        assertThat(evaluations).allMatch(e -> !e.fired());
    }
}
```

- [ ] **Step 17: Run all rule tests to verify they pass**

Run: `mvn -q test -Dtest=R1LatePaymentsRuleTest,R2LowDscrRuleTest,R3MaturitySoonRuleTest,R4DscrFallingRuleTest,R5LowDebtYieldNearMaturityRuleTest,RuleEngineTest`
Expected: PASS (all tests green)

- [ ] **Step 18: Commit**

```bash
git add src/main/java/com/cre/earlywarning/rules src/test/java/com/cre/earlywarning/rules
git commit -m "feat: implement rules R1-R5 and the rule engine"
```

---

## Task 7: Alert Service & Scoring

**Files:**
- Create: `src/main/java/com/cre/earlywarning/alerts/AlertState.java`
- Create: `src/main/java/com/cre/earlywarning/alerts/Alert.java`
- Create: `src/main/java/com/cre/earlywarning/alerts/AlertRepository.java`
- Create: `src/main/java/com/cre/earlywarning/alerts/Score.java`
- Create: `src/main/java/com/cre/earlywarning/alerts/ScoreCalculator.java`
- Create: `src/main/java/com/cre/earlywarning/alerts/AlertService.java`
- Test: `src/test/java/com/cre/earlywarning/alerts/ScoreCalculatorTest.java`
- Test: `src/test/java/com/cre/earlywarning/alerts/AlertServiceTest.java`

**Interfaces:**
- Consumes: `Loan`/`LoanRepository` (Task 3), `RuleEvaluation`/`RuleType` (Task 6).
- Produces: `AlertState` enum `{OPEN, ACKNOWLEDGED, RESOLVED}`, `Alert` JPA entity, `AlertRepository` with `findByLoanIdAndRuleId`, `findByLoanId`, `findByStateOrderByScoreDesc`, and `AlertService` with `applyRuleEvaluations(String loanId, YearMonth month, List<RuleEvaluation> evaluations)`, `acknowledge(Long alertId)`, `openQueue(): List<Alert>` — Task 8's `IngestService` calls `applyRuleEvaluations`; Task 10's API controllers call `acknowledge` and `openQueue`.

- [ ] **Step 1: Write the failing test `src/test/java/com/cre/earlywarning/alerts/ScoreCalculatorTest.java`**

```java
package com.cre.earlywarning.alerts;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanRepository;
import com.cre.earlywarning.rules.RuleType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ScoreCalculatorTest {

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private ScoreCalculator scoreCalculator;

    @Test
    void creditRuleWithinSixMonthsOnLargestLoanScoresMaximum() {
        loanRepository.save(new Loan("SMALL1", "RETAIL", new BigDecimal("1000000.00"),
            new BigDecimal("0.0600"), LocalDate.of(2030, 1, 1), new BigDecimal("90000.00")));
        loanRepository.save(new Loan("BIG1", "OFFICE", new BigDecimal("10000000.00"),
            new BigDecimal("0.0600"), LocalDate.of(2030, 1, 1), new BigDecimal("900000.00")));

        Score score = scoreCalculator.compute(RuleType.CREDIT, 5L, new BigDecimal("10000000.00"));

        assertThat(score.typeScore()).isEqualTo(40);
        assertThat(score.timeScore()).isEqualTo(30);
        assertThat(score.sizeScore()).isEqualTo(20);
        assertThat(score.total()).isEqualTo(90);
    }

    @Test
    void earlyWarningRuleFarFromMaturityOnSmallLoanScoresLow() {
        loanRepository.save(new Loan("SMALL2", "RETAIL", new BigDecimal("1000000.00"),
            new BigDecimal("0.0600"), LocalDate.of(2030, 1, 1), new BigDecimal("90000.00")));
        loanRepository.save(new Loan("BIG2", "OFFICE", new BigDecimal("10000000.00"),
            new BigDecimal("0.0600"), LocalDate.of(2030, 1, 1), new BigDecimal("900000.00")));

        Score score = scoreCalculator.compute(RuleType.EARLY_WARNING, 24L, new BigDecimal("1000000.00"));

        assertThat(score.typeScore()).isEqualTo(25);
        assertThat(score.timeScore()).isEqualTo(0);
        assertThat(score.sizeScore()).isEqualTo(2);
        assertThat(score.total()).isEqualTo(27);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `mvn -q test -Dtest=ScoreCalculatorTest`
Expected: COMPILATION ERROR — `Score` and `ScoreCalculator` don't exist yet.

- [ ] **Step 3: Write `src/main/java/com/cre/earlywarning/alerts/AlertState.java`**

```java
package com.cre.earlywarning.alerts;

public enum AlertState {
    OPEN,
    ACKNOWLEDGED,
    RESOLVED
}
```

- [ ] **Step 4: Write `src/main/java/com/cre/earlywarning/alerts/Alert.java`**

```java
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
```

- [ ] **Step 5: Write `src/main/java/com/cre/earlywarning/alerts/AlertRepository.java`**

```java
package com.cre.earlywarning.alerts;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    Optional<Alert> findByLoanIdAndRuleId(String loanId, String ruleId);

    List<Alert> findByLoanId(String loanId);

    List<Alert> findByStateOrderByScoreDesc(AlertState state);
}
```

- [ ] **Step 6: Write `src/main/java/com/cre/earlywarning/alerts/Score.java`**

```java
package com.cre.earlywarning.alerts;

public record Score(int typeScore, int timeScore, int sizeScore) {
    public int total() {
        return typeScore + timeScore + sizeScore;
    }
}
```

- [ ] **Step 7: Write `src/main/java/com/cre/earlywarning/alerts/ScoreCalculator.java`**

```java
package com.cre.earlywarning.alerts;

import com.cre.earlywarning.domain.LoanRepository;
import com.cre.earlywarning.rules.RuleType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class ScoreCalculator {

    private final LoanRepository loanRepository;

    public ScoreCalculator(LoanRepository loanRepository) {
        this.loanRepository = loanRepository;
    }

    public Score compute(RuleType type, long monthsToMaturity, BigDecimal loanOriginalBalance) {
        int typeScore = type == RuleType.CREDIT ? 40 : 25;
        int timeScore = timeScore(monthsToMaturity);
        int sizeScore = sizeScore(loanOriginalBalance);
        return new Score(typeScore, timeScore, sizeScore);
    }

    private int timeScore(long monthsToMaturity) {
        if (monthsToMaturity <= 6) return 30;
        if (monthsToMaturity <= 12) return 20;
        if (monthsToMaturity <= 18) return 10;
        return 0;
    }

    private int sizeScore(BigDecimal balance) {
        BigDecimal max = loanRepository.findMaxOriginalBalance();
        if (max == null || max.compareTo(BigDecimal.ZERO) == 0) {
            return 0;
        }
        BigDecimal ratio = balance.divide(max, 4, RoundingMode.HALF_UP);
        return ratio.multiply(BigDecimal.valueOf(20)).setScale(0, RoundingMode.HALF_UP).intValue();
    }
}
```

- [ ] **Step 8: Run the score calculator test to verify it passes**

Run: `mvn -q test -Dtest=ScoreCalculatorTest`
Expected: PASS (2 tests)

- [ ] **Step 9: Write the failing test `src/test/java/com/cre/earlywarning/alerts/AlertServiceTest.java`**

```java
package com.cre.earlywarning.alerts;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanRepository;
import com.cre.earlywarning.rules.RuleEvaluation;
import com.cre.earlywarning.rules.RuleType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class AlertServiceTest {

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private AlertService alertService;

    @Test
    void firingCreatesOneOpenAlertWithAuditTrail() {
        loanRepository.save(loan("L700"));

        apply("L700", YearMonth.of(2024, 1), fired("R2", RuleType.CREDIT, "1.10"));

        Alert alert = alertRepository.findByLoanIdAndRuleId("L700", "R2").orElseThrow();
        assertThat(alert.getState()).isEqualTo(AlertState.OPEN);
        assertThat(alert.getRuleVersion()).isEqualTo(1);
        assertThat(alert.getFiredMonth()).isEqualTo("2024-01");
        assertThat(alert.getLimitValue()).isEqualTo("1.10");
        assertThat(alert.getInputsJson()).contains("dscr");
        assertThat(alert.getScore()).isGreaterThan(0);
    }

    @Test
    void repeatedFiringUpdatesTheSameAlertInstead_ofCreatingADuplicate() {
        loanRepository.save(loan("L701"));

        apply("L701", YearMonth.of(2024, 1), fired("R2", RuleType.CREDIT, "1.10"));
        apply("L701", YearMonth.of(2024, 2), fired("R2", RuleType.CREDIT, "1.10"));

        assertThat(alertRepository.findByLoanId("L701")).hasSize(1);
        assertThat(alertRepository.findByLoanIdAndRuleId("L701", "R2").orElseThrow().getFiredMonth())
            .isEqualTo("2024-02");
    }

    @Test
    void resolvesAfterTwoConsecutiveClearMonths_matchingMissedPaymentsScenario() {
        loanRepository.save(loan("L702"));

        apply("L702", YearMonth.of(2024, 6), fired("R1", RuleType.CREDIT, "2"));
        apply("L702", YearMonth.of(2024, 7), notFired("R1", RuleType.CREDIT, "2"));
        Alert afterOneClearMonth = alertRepository.findByLoanIdAndRuleId("L702", "R1").orElseThrow();
        assertThat(afterOneClearMonth.getState()).isEqualTo(AlertState.OPEN);

        apply("L702", YearMonth.of(2024, 8), notFired("R1", RuleType.CREDIT, "2"));
        Alert afterTwoClearMonths = alertRepository.findByLoanIdAndRuleId("L702", "R1").orElseThrow();
        assertThat(afterTwoClearMonths.getState()).isEqualTo(AlertState.RESOLVED);
    }

    @Test
    void acknowledgingAnAlertSetsItToAcknowledged() {
        loanRepository.save(loan("L703"));
        apply("L703", YearMonth.of(2024, 1), fired("R2", RuleType.CREDIT, "1.10"));
        Alert alert = alertRepository.findByLoanIdAndRuleId("L703", "R2").orElseThrow();

        alertService.acknowledge(alert.getId());

        assertThat(alertRepository.findById(alert.getId()).orElseThrow().getState())
            .isEqualTo(AlertState.ACKNOWLEDGED);
    }

    @Test
    void openQueueOnlyReturnsOpenAlertsRankedByScoreDescending() {
        loanRepository.save(loan("L704"));
        loanRepository.save(loan("L705"));
        apply("L704", YearMonth.of(2024, 1), fired("R1", RuleType.CREDIT, "2"));
        apply("L705", YearMonth.of(2024, 1), fired("R4", RuleType.EARLY_WARNING, "0.15"));
        Alert acknowledged = alertRepository.findByLoanIdAndRuleId("L704", "R1").orElseThrow();
        alertService.acknowledge(acknowledged.getId());

        List<Alert> queue = alertService.openQueue();

        assertThat(queue).extracting(Alert::getLoanId).containsExactly("L705");
    }

    private void apply(String loanId, YearMonth month, RuleEvaluation evaluation) {
        alertService.applyRuleEvaluations(loanId, month, List.of(evaluation));
    }

    private RuleEvaluation fired(String ruleId, RuleType type, String limit) {
        return new RuleEvaluation(ruleId, type, true, Map.of("value", "test"), limit, 1);
    }

    private RuleEvaluation notFired(String ruleId, RuleType type, String limit) {
        return new RuleEvaluation(ruleId, type, false, Map.of(), limit, 1);
    }

    private Loan loan(String loanId) {
        return new Loan(loanId, "APARTMENT", new BigDecimal("5000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2030, 1, 1), new BigDecimal("400000.00"));
    }
}
```

- [ ] **Step 10: Run the test to verify it fails to compile**

Run: `mvn -q test -Dtest=AlertServiceTest`
Expected: COMPILATION ERROR — `AlertService` doesn't exist yet.

- [ ] **Step 11: Write `src/main/java/com/cre/earlywarning/alerts/AlertService.java`**

```java
package com.cre.earlywarning.alerts;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanRepository;
import com.cre.earlywarning.rules.RuleEvaluation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class AlertService {

    private final AlertRepository alertRepository;
    private final LoanRepository loanRepository;
    private final ScoreCalculator scoreCalculator;
    private final ObjectMapper objectMapper;

    public AlertService(AlertRepository alertRepository, LoanRepository loanRepository,
                         ScoreCalculator scoreCalculator, ObjectMapper objectMapper) {
        this.alertRepository = alertRepository;
        this.loanRepository = loanRepository;
        this.scoreCalculator = scoreCalculator;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void applyRuleEvaluations(String loanId, YearMonth month, List<RuleEvaluation> evaluations) {
        Loan loan = loanRepository.findById(loanId)
            .orElseThrow(() -> new IllegalStateException("Unknown loan " + loanId));

        for (RuleEvaluation evaluation : evaluations) {
            Optional<Alert> existing = alertRepository.findByLoanIdAndRuleId(loanId, evaluation.ruleId());

            if (evaluation.fired()) {
                Alert alert = existing.orElseGet(() -> new Alert(loanId, evaluation.ruleId()));
                alert.setState(AlertState.OPEN);
                alert.setClearMonthsCount(0);
                alert.setRuleVersion(evaluation.ruleVersion());
                alert.setFiredMonth(month.toString());
                alert.setLimitValue(evaluation.limit());
                alert.setInputsJson(writeInputs(evaluation));

                long monthsToMaturity = monthsToMaturityAt(loan, month);
                Score score = scoreCalculator.compute(evaluation.type(), monthsToMaturity, loan.getOriginalBalance());
                alert.setScoreType(score.typeScore());
                alert.setScoreTime(score.timeScore());
                alert.setScoreSize(score.sizeScore());
                alert.setScore(score.total());
                alert.setUpdatedAt(Instant.now());

                alertRepository.save(alert);
            } else if (existing.isPresent() && existing.get().getState() != AlertState.RESOLVED) {
                Alert alert = existing.get();
                alert.setClearMonthsCount(alert.getClearMonthsCount() + 1);
                if (alert.getClearMonthsCount() >= 2) {
                    alert.setState(AlertState.RESOLVED);
                }
                alert.setUpdatedAt(Instant.now());
                alertRepository.save(alert);
            }
        }
    }

    @Transactional
    public void acknowledge(Long alertId) {
        Alert alert = alertRepository.findById(alertId)
            .orElseThrow(() -> new NoSuchElementException("Alert " + alertId + " not found"));
        alert.setState(AlertState.ACKNOWLEDGED);
        alert.setUpdatedAt(Instant.now());
        alertRepository.save(alert);
    }

    public List<Alert> openQueue() {
        return alertRepository.findByStateOrderByScoreDesc(AlertState.OPEN);
    }

    private long monthsToMaturityAt(Loan loan, YearMonth month) {
        return ChronoUnit.MONTHS.between(month.atDay(1), loan.getMaturityDate().withDayOfMonth(1));
    }

    private String writeInputs(RuleEvaluation evaluation) {
        try {
            return objectMapper.writeValueAsString(evaluation.inputs());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize rule inputs for " + evaluation.ruleId(), e);
        }
    }
}
```

- [ ] **Step 12: Run the test to verify it passes**

Run: `mvn -q test -Dtest=AlertServiceTest`
Expected: PASS (5 tests)

- [ ] **Step 13: Commit**

```bash
git add src/main/java/com/cre/earlywarning/alerts src/test/java/com/cre/earlywarning/alerts
git commit -m "feat: add Alert entity, scoring, and AlertService lifecycle"
```

---

## Task 8: Ingest Pipeline

**Files:**
- Create: `src/main/java/com/cre/earlywarning/ingest/CsvRow.java`
- Create: `src/main/java/com/cre/earlywarning/ingest/CsvRowParser.java`
- Create: `src/main/java/com/cre/earlywarning/ingest/IngestService.java`
- Test: `src/test/java/com/cre/earlywarning/ingest/CsvRowParserTest.java`
- Test: `src/test/java/com/cre/earlywarning/ingest/IngestServiceTest.java`

**Interfaces:**
- Consumes: `EventLog` (Task 3), `RuleEngine` (Task 6), `AlertService` (Task 7).
- Produces: `CsvRow` record `(String loanId, YearMonth month, BigDecimal balance, BigDecimal noi, BigDecimal yearlyPayments, int paymentsLate)`, `CsvRowParser.parse(Path csvFile): List<CsvRow>`, `IngestService.ingest(CsvRow row): boolean` (true if newly ingested) — Task 9's `FolderPollerJob` calls `CsvRowParser.parse` then `IngestService.ingest` per row.

- [ ] **Step 1: Write `src/main/java/com/cre/earlywarning/ingest/CsvRow.java`**

```java
package com.cre.earlywarning.ingest;

import java.math.BigDecimal;
import java.time.YearMonth;

public record CsvRow(
    String loanId,
    YearMonth month,
    BigDecimal balance,
    BigDecimal noi,
    BigDecimal yearlyPayments,
    int paymentsLate
) {
}
```

- [ ] **Step 2: Write the failing test `src/test/java/com/cre/earlywarning/ingest/CsvRowParserTest.java`**

```java
package com.cre.earlywarning.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvRowParserTest {

    private final CsvRowParser parser = new CsvRowParser();

    @Test
    void parsesHeaderedCsvIntoRows(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("report-2024-01.csv");
        Files.writeString(file, """
            loan_id,month,balance,noi,yearly_payments,payments_late
            L001,2024-01,25000000.00,2000000.00,1600000.00,0
            L002,2024-01,10000000.00,900000.00,700000.00,2
            """);

        List<CsvRow> rows = parser.parse(file);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).isEqualTo(new CsvRow("L001", YearMonth.of(2024, 1),
            new BigDecimal("25000000.00"), new BigDecimal("2000000.00"),
            new BigDecimal("1600000.00"), 0));
        assertThat(rows.get(1).paymentsLate()).isEqualTo(2);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails to compile**

Run: `mvn -q test -Dtest=CsvRowParserTest`
Expected: COMPILATION ERROR — `CsvRowParser` doesn't exist yet.

- [ ] **Step 4: Write `src/main/java/com/cre/earlywarning/ingest/CsvRowParser.java`**

```java
package com.cre.earlywarning.ingest;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;

@Component
public class CsvRowParser {

    public List<CsvRow> parse(Path csvFile) throws IOException {
        List<String> lines = Files.readAllLines(csvFile);
        return lines.stream()
            .skip(1) // header
            .filter(line -> !line.isBlank())
            .map(this::parseLine)
            .toList();
    }

    private CsvRow parseLine(String line) {
        String[] columns = line.split(",");
        return new CsvRow(
            columns[0].trim(),
            YearMonth.parse(columns[1].trim()),
            new BigDecimal(columns[2].trim()),
            new BigDecimal(columns[3].trim()),
            new BigDecimal(columns[4].trim()),
            Integer.parseInt(columns[5].trim())
        );
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -q test -Dtest=CsvRowParserTest`
Expected: PASS (1 test)

- [ ] **Step 6: Write the failing test `src/test/java/com/cre/earlywarning/ingest/IngestServiceTest.java`**

```java
package com.cre.earlywarning.ingest;

import com.cre.earlywarning.alerts.AlertRepository;
import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanMonthEventRepository;
import com.cre.earlywarning.domain.LoanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class IngestServiceTest {

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private LoanMonthEventRepository eventRepository;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private IngestService ingestService;

    @Test
    void ingestingANewRowWritesAnEventAndEvaluatesRules() {
        loanRepository.save(new Loan("L800", "APARTMENT", new BigDecimal("5000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2030, 1, 1), new BigDecimal("400000.00")));
        CsvRow row = new CsvRow("L800", YearMonth.of(2024, 1), new BigDecimal("5000000.00"),
            new BigDecimal("400000.00"), new BigDecimal("600000.00"), 2);

        boolean inserted = ingestService.ingest(row);

        assertThat(inserted).isTrue();
        assertThat(eventRepository.findByLoanIdOrderByMonthAsc("L800")).hasSize(1);
        // DSCR = 400000/600000 = 0.6667, which fires R2; paymentsLate=2 fires R1
        assertThat(alertRepository.findByLoanId("L800")).hasSize(2);
    }

    @Test
    void reingestingTheSameRowIsANoOpAndDoesNotChangeAlerts() {
        loanRepository.save(new Loan("L801", "APARTMENT", new BigDecimal("5000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2030, 1, 1), new BigDecimal("400000.00")));
        CsvRow row = new CsvRow("L801", YearMonth.of(2024, 1), new BigDecimal("5000000.00"),
            new BigDecimal("400000.00"), new BigDecimal("600000.00"), 2);

        ingestService.ingest(row);
        int alertCountAfterFirst = alertRepository.findByLoanId("L801").size();
        boolean secondInsert = ingestService.ingest(row);
        int alertCountAfterSecond = alertRepository.findByLoanId("L801").size();

        assertThat(secondInsert).isFalse();
        assertThat(alertCountAfterSecond).isEqualTo(alertCountAfterFirst);
    }
}
```

- [ ] **Step 7: Run the test to verify it fails to compile**

Run: `mvn -q test -Dtest=IngestServiceTest`
Expected: COMPILATION ERROR — `IngestService` doesn't exist yet.

- [ ] **Step 8: Write `src/main/java/com/cre/earlywarning/ingest/IngestService.java`**

```java
package com.cre.earlywarning.ingest;

import com.cre.earlywarning.alerts.AlertService;
import com.cre.earlywarning.domain.EventLog;
import com.cre.earlywarning.rules.RuleEngine;
import com.cre.earlywarning.rules.RuleEvaluation;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IngestService {

    private final EventLog eventLog;
    private final RuleEngine ruleEngine;
    private final AlertService alertService;

    public IngestService(EventLog eventLog, RuleEngine ruleEngine, AlertService alertService) {
        this.eventLog = eventLog;
        this.ruleEngine = ruleEngine;
        this.alertService = alertService;
    }

    /**
     * Returns true if this row was newly ingested, false if it was a duplicate (loanId+month
     * already in the event log) and therefore a no-op — rules are only re-evaluated on new events.
     */
    public boolean ingest(CsvRow row) {
        boolean inserted = eventLog.append(row.loanId(), row.month(), row.balance(), row.noi(),
            row.yearlyPayments(), row.paymentsLate());
        if (!inserted) {
            return false;
        }
        List<RuleEvaluation> evaluations = ruleEngine.evaluate(row.loanId(), row.month());
        alertService.applyRuleEvaluations(row.loanId(), row.month(), evaluations);
        return true;
    }
}
```

- [ ] **Step 9: Run the test to verify it passes**

Run: `mvn -q test -Dtest=IngestServiceTest`
Expected: PASS (2 tests)

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/cre/earlywarning/ingest src/test/java/com/cre/earlywarning/ingest
git commit -m "feat: add CSV parsing and idempotent IngestService wiring rules and alerts"
```

---

## Task 9: Folder Poller with ShedLock

**Files:**
- Create: `src/main/java/com/cre/earlywarning/config/ShedLockConfig.java`
- Create: `src/main/java/com/cre/earlywarning/ingest/FolderPollerJob.java`
- Test: `src/test/java/com/cre/earlywarning/ingest/FolderPollerJobTest.java`

**Interfaces:**
- Consumes: `CsvRowParser` and `IngestService` (Task 8).
- Produces: a `@Scheduled` job that moves CSV files through `inbox/ -> processing/ -> done/|failed/` and calls `ingestService.ingest(row)` per row. No other task depends on this one's internals — it is a terminal consumer of the pipeline built so far.

- [ ] **Step 1: Write `src/main/java/com/cre/earlywarning/config/ShedLockConfig.java`**

```java
package com.cre.earlywarning.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "5m")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
```

- [ ] **Step 2: Write the failing test `src/test/java/com/cre/earlywarning/ingest/FolderPollerJobTest.java`**

```java
package com.cre.earlywarning.ingest;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanMonthEventRepository;
import com.cre.earlywarning.domain.LoanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
    "app.intake.inbox-dir=build/test-data/poller-inbox",
    "app.intake.processing-dir=build/test-data/poller-processing",
    "app.intake.done-dir=build/test-data/poller-done",
    "app.intake.failed-dir=build/test-data/poller-failed"
})
class FolderPollerJobTest {

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private LoanMonthEventRepository eventRepository;

    @Autowired
    private FolderPollerJob pollerJob;

    @Test
    void pollPicksUpAFileAndMovesItToDone() throws IOException, InterruptedException {
        loanRepository.save(new Loan("L850", "APARTMENT", new BigDecimal("5000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2030, 1, 1), new BigDecimal("400000.00")));

        Path inbox = Path.of("build/test-data/poller-inbox");
        Files.createDirectories(inbox);
        Path file = inbox.resolve("report-2024-01.csv");
        Files.writeString(file, """
            loan_id,month,balance,noi,yearly_payments,payments_late
            L850,2024-01,5000000.00,400000.00,300000.00,0
            """);
        // backdate the file so the poller's "skip if modified in last 5s" guard doesn't skip it
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
            System.currentTimeMillis() - 10_000));

        pollerJob.poll();

        assertThat(Files.exists(file)).isFalse();
        assertThat(Files.exists(Path.of("build/test-data/poller-done/report-2024-01.csv"))).isTrue();
        assertThat(eventRepository.findByLoanIdOrderByMonthAsc("L850")).hasSize(1);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails to compile**

Run: `mvn -q test -Dtest=FolderPollerJobTest`
Expected: COMPILATION ERROR — `FolderPollerJob` doesn't exist yet.

- [ ] **Step 4: Write `src/main/java/com/cre/earlywarning/ingest/FolderPollerJob.java`**

```java
package com.cre.earlywarning.ingest;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;

@Component
public class FolderPollerJob {

    private static final Logger log = LoggerFactory.getLogger(FolderPollerJob.class);
    private static final long RECENTLY_MODIFIED_WINDOW_MS = 5_000;

    private final CsvRowParser csvRowParser;
    private final IngestService ingestService;
    private final Path inboxDir;
    private final Path processingDir;
    private final Path doneDir;
    private final Path failedDir;

    public FolderPollerJob(CsvRowParser csvRowParser, IngestService ingestService,
                            @Value("${app.intake.inbox-dir}") String inboxDir,
                            @Value("${app.intake.processing-dir}") String processingDir,
                            @Value("${app.intake.done-dir}") String doneDir,
                            @Value("${app.intake.failed-dir}") String failedDir) {
        this.csvRowParser = csvRowParser;
        this.ingestService = ingestService;
        this.inboxDir = Path.of(inboxDir);
        this.processingDir = Path.of(processingDir);
        this.doneDir = Path.of(doneDir);
        this.failedDir = Path.of(failedDir);
        createDirIfMissing(this.inboxDir);
        createDirIfMissing(this.processingDir);
        createDirIfMissing(this.doneDir);
        createDirIfMissing(this.failedDir);
    }

    @Scheduled(fixedDelay = 10_000)
    @SchedulerLock(name = "report-poller", lockAtMostFor = "5m", lockAtLeastFor = "5s")
    public void poll() {
        File[] files = inboxDir.toFile().listFiles((dir, name) -> name.endsWith(".csv"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (System.currentTimeMillis() - file.lastModified() < RECENTLY_MODIFIED_WINDOW_MS) {
                continue;
            }
            Path source = file.toPath();
            Path target = processingDir.resolve(file.getName());
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // another node won the race for this file; nothing to do
                continue;
            }
            processFile(target);
        }
    }

    private void processFile(Path file) {
        try {
            List<CsvRow> rows = csvRowParser.parse(file);
            for (CsvRow row : rows) {
                ingestService.ingest(row);
            }
            Files.move(file, doneDir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.error("Failed to process {}: {}", file, e.getMessage(), e);
            try {
                Files.move(file, failedDir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailure) {
                log.error("Failed to move {} to failed dir: {}", file, moveFailure.getMessage(), moveFailure);
            }
        }
    }

    private void createDirIfMissing(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create directory " + dir, e);
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -q test -Dtest=FolderPollerJobTest`
Expected: PASS (1 test)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/cre/earlywarning/config src/main/java/com/cre/earlywarning/ingest/FolderPollerJob.java src/test/java/com/cre/earlywarning/ingest/FolderPollerJobTest.java
git commit -m "feat: add ShedLock-guarded folder poller"
```

---

## Task 10: Seeded Data Generator with Planted Scenarios

**Files:**
- Create: `src/main/java/com/cre/earlywarning/generator/GeneratedLoan.java`
- Create: `src/main/java/com/cre/earlywarning/generator/LoanDataGenerator.java`
- Create: `src/main/java/com/cre/earlywarning/generator/GeneratorRunner.java`
- Test: `src/test/java/com/cre/earlywarning/generator/LoanDataGeneratorTest.java`

**Interfaces:**
- Consumes: `CsvRow` (Task 8, for the monthly rows it produces).
- Produces: `GeneratedLoan` record `(String loanId, String propertyType, BigDecimal originalBalance, BigDecimal rate, LocalDate maturityDate, BigDecimal underwritingNoi)`, `LoanDataGenerator.generate(long seed): GeneratedData` where `GeneratedData` is a record `(List<GeneratedLoan> loans, Map<YearMonth, List<CsvRow>> rowsByMonth)`. No later task consumes this directly — `GeneratorRunner` is the only caller, and it writes DB rows + CSV files as a side effect under the `generator` Spring profile.

- [ ] **Step 1: Write the failing test `src/test/java/com/cre/earlywarning/generator/LoanDataGeneratorTest.java`**

```java
package com.cre.earlywarning.generator;

import com.cre.earlywarning.ingest.CsvRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoanDataGeneratorTest {

    private final LoanDataGenerator generator = new LoanDataGenerator();

    @Test
    void generatesOneHundredLoansWithTwentyFourMonthsEach() {
        GeneratedData data = generator.generate(42L);

        assertThat(data.loans()).hasSize(100);
        assertThat(data.rowsByMonth()).hasSize(24);
        data.rowsByMonth().values().forEach(rows -> assertThat(rows).hasSize(100));
    }

    @Test
    void sameSeedProducesIdenticalOutput() {
        GeneratedData first = generator.generate(42L);
        GeneratedData second = generator.generate(42L);

        assertThat(first.loans()).containsExactlyElementsOf(second.loans());
        YearMonth anyMonth = first.rowsByMonth().keySet().iterator().next();
        assertThat(first.rowsByMonth().get(anyMonth)).containsExactlyElementsOf(second.rowsByMonth().get(anyMonth));
    }

    @Test
    void slowSlideScenarioLoanL001HasR4FiringAtLeastThreeMonthsBeforeR2() {
        GeneratedData data = generator.generate(42L);

        List<CsvRow> l001Rows = rowsForLoan(data, "L001");
        // DSCR must fall from ~1.45 to ~1.05 over the first 12 months so R4 (fall>=0.15 over 6mo)
        // fires well before R2 (dscr<1.10)
        BigDecimal yearlyPayments = l001Rows.get(0).yearlyPayments();
        BigDecimal firstMonthDscr = l001Rows.get(0).noi().divide(yearlyPayments, 4, java.math.RoundingMode.HALF_UP);
        BigDecimal twelfthMonthDscr = l001Rows.get(11).noi().divide(yearlyPayments, 4, java.math.RoundingMode.HALF_UP);

        assertThat(firstMonthDscr).isGreaterThan(new BigDecimal("1.40"));
        assertThat(twelfthMonthDscr).isLessThan(new BigDecimal("1.10"));
    }

    @Test
    void weakRefinanceScenarioLoanL002HasDebtYieldBelowEightPercentNearMaturity() {
        GeneratedData data = generator.generate(42L);
        List<CsvRow> l002Rows = rowsForLoan(data, "L002");
        CsvRow firstMonth = l002Rows.get(0);

        BigDecimal debtYield = firstMonth.noi().divide(firstMonth.balance(), 4, java.math.RoundingMode.HALF_UP);
        assertThat(debtYield).isLessThan(new BigDecimal("0.08"));

        GeneratedLoan loan = data.loans().stream().filter(l -> l.loanId().equals("L002")).findFirst().orElseThrow();
        long monthsToMaturity = java.time.temporal.ChronoUnit.MONTHS.between(
            firstMonth.month().atDay(1), loan.maturityDate().withDayOfMonth(1));
        assertThat(monthsToMaturity).isLessThanOrEqualTo(18);
    }

    @Test
    void missedPaymentsScenarioLoanL003GoesLateThenClears() {
        GeneratedData data = generator.generate(42L);
        List<CsvRow> l003Rows = rowsForLoan(data, "L003");

        assertThat(l003Rows.get(5).paymentsLate()).isGreaterThanOrEqualTo(2);
        assertThat(l003Rows.get(6).paymentsLate()).isGreaterThanOrEqualTo(2);
        assertThat(l003Rows.get(9).paymentsLate()).isEqualTo(0);
    }

    private List<CsvRow> rowsForLoan(GeneratedData data, String loanId) {
        return data.rowsByMonth().keySet().stream().sorted()
            .map(month -> data.rowsByMonth().get(month).stream()
                .filter(r -> r.loanId().equals(loanId)).findFirst().orElseThrow())
            .sorted(Comparator.comparing(CsvRow::month))
            .toList();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `mvn -q test -Dtest=LoanDataGeneratorTest`
Expected: COMPILATION ERROR — `LoanDataGenerator`, `GeneratedData`, `GeneratedLoan` don't exist yet.

- [ ] **Step 3: Write `src/main/java/com/cre/earlywarning/generator/GeneratedLoan.java`**

```java
package com.cre.earlywarning.generator;

import java.math.BigDecimal;
import java.time.LocalDate;

public record GeneratedLoan(
    String loanId,
    String propertyType,
    BigDecimal originalBalance,
    BigDecimal rate,
    LocalDate maturityDate,
    BigDecimal underwritingNoi
) {
}
```

- [ ] **Step 4: Write `src/main/java/com/cre/earlywarning/generator/GeneratedData.java`**

```java
package com.cre.earlywarning.generator;

import com.cre.earlywarning.ingest.CsvRow;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

public record GeneratedData(List<GeneratedLoan> loans, Map<YearMonth, List<CsvRow>> rowsByMonth) {
}
```

- [ ] **Step 5: Write `src/main/java/com/cre/earlywarning/generator/LoanDataGenerator.java`**

Design: 97 loans ("L004".."L100") follow a seeded random walk with deliberately conservative bounds (DSCR 1.2-1.6, debt yield 9-14%, no late payments, maturities 24+ months out) so they almost never fire any rule — this keeps the queue "quiet" per spec section 11 without needing to tune thresholds. 3 loans (`L001`, `L002`, `L003`) are fully scripted (not randomized) to exactly match the spec's 3 planted scenarios; being scripted rather than random, they are deterministic by construction regardless of seed.

```java
package com.cre.earlywarning.generator;

import com.cre.earlywarning.ingest.CsvRow;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Component
public class LoanDataGenerator {

    private static final int LOAN_COUNT = 100;
    private static final int MONTH_COUNT = 24;
    private static final YearMonth FIRST_MONTH = YearMonth.of(2024, 1);

    public GeneratedData generate(long seed) {
        Random random = new Random(seed);
        List<GeneratedLoan> loans = new ArrayList<>();
        Map<String, List<CsvRow>> rowsByLoan = new HashMap<>();

        loans.add(scriptedLoan("L001", "APARTMENT", 36));
        rowsByLoan.put("L001", slowSlideScenario());
        loans.add(scriptedLoan("L002", "OFFICE", 9));
        rowsByLoan.put("L002", weakRefinanceScenario());
        loans.add(scriptedLoan("L003", "RETAIL", 36));
        rowsByLoan.put("L003", missedPaymentsScenario());

        for (int i = 4; i <= LOAN_COUNT; i++) {
            String loanId = String.format("L%03d", i);
            String propertyType = propertyTypeFor(i);
            BigDecimal originalBalance = randomBalance(random);
            BigDecimal rate = randomRate(random);
            int maturityMonthsOut = 24 + random.nextInt(37); // 24..60 months out, stays quiet for R3/R5
            LocalDate maturityDate = FIRST_MONTH.plusMonths(maturityMonthsOut).atDay(1);
            BigDecimal yearlyPayments = amortizedAnnualPayment(originalBalance, rate);
            BigDecimal underwritingNoi = yearlyPayments.multiply(new BigDecimal("1.35"));

            loans.add(new GeneratedLoan(loanId, propertyType, originalBalance, rate, maturityDate, underwritingNoi));
            rowsByLoan.put(loanId, normalWalk(loanId, originalBalance, yearlyPayments, underwritingNoi, random));
        }

        Map<YearMonth, List<CsvRow>> rowsByMonth = new HashMap<>();
        for (int m = 0; m < MONTH_COUNT; m++) {
            YearMonth month = FIRST_MONTH.plusMonths(m);
            List<CsvRow> rowsForMonth = new ArrayList<>();
            for (GeneratedLoan loan : loans) {
                rowsForMonth.add(rowsByLoan.get(loan.loanId()).get(m));
            }
            rowsByMonth.put(month, rowsForMonth);
        }

        return new GeneratedData(loans, rowsByMonth);
    }

    private GeneratedLoan scriptedLoan(String loanId, String propertyType, int maturityMonthsOut) {
        BigDecimal originalBalance = new BigDecimal("20000000.00");
        BigDecimal rate = new BigDecimal("0.0550");
        LocalDate maturityDate = FIRST_MONTH.plusMonths(maturityMonthsOut).atDay(1);
        BigDecimal yearlyPayments = amortizedAnnualPayment(originalBalance, rate);
        BigDecimal underwritingNoi = yearlyPayments.multiply(new BigDecimal("1.45")).setScale(2, RoundingMode.HALF_UP);
        return new GeneratedLoan(loanId, propertyType, originalBalance, rate, maturityDate, underwritingNoi);
    }

    private List<CsvRow> slowSlideScenario() {
        BigDecimal balance = new BigDecimal("20000000.00");
        BigDecimal yearlyPayments = amortizedAnnualPayment(balance, new BigDecimal("0.0550"));
        List<CsvRow> rows = new ArrayList<>();
        for (int m = 0; m < MONTH_COUNT; m++) {
            YearMonth month = FIRST_MONTH.plusMonths(m);
            // dscr linearly interpolates 1.45 -> 1.05 over months 0..11, then holds at 1.05
            BigDecimal dscr = m <= 11
                ? new BigDecimal("1.45").subtract(new BigDecimal("0.40").multiply(BigDecimal.valueOf(m))
                    .divide(BigDecimal.valueOf(11), 4, RoundingMode.HALF_UP))
                : new BigDecimal("1.05");
            BigDecimal noi = dscr.multiply(yearlyPayments).setScale(2, RoundingMode.HALF_UP);
            rows.add(new CsvRow("L001", month, balance, noi, yearlyPayments, 0));
        }
        return rows;
    }

    private List<CsvRow> weakRefinanceScenario() {
        BigDecimal yearlyPayments = amortizedAnnualPayment(new BigDecimal("20000000.00"), new BigDecimal("0.0550"));
        BigDecimal dscr = new BigDecimal("1.30");
        BigDecimal noi = dscr.multiply(yearlyPayments).setScale(2, RoundingMode.HALF_UP);
        // debtYield = noi/balance must be 0.07 -> balance = noi/0.07
        BigDecimal balance = noi.divide(new BigDecimal("0.07"), 2, RoundingMode.HALF_UP);
        List<CsvRow> rows = new ArrayList<>();
        for (int m = 0; m < MONTH_COUNT; m++) {
            rows.add(new CsvRow("L002", FIRST_MONTH.plusMonths(m), balance, noi, yearlyPayments, 0));
        }
        return rows;
    }

    private List<CsvRow> missedPaymentsScenario() {
        BigDecimal balance = new BigDecimal("20000000.00");
        BigDecimal yearlyPayments = amortizedAnnualPayment(balance, new BigDecimal("0.0550"));
        BigDecimal noi = new BigDecimal("1.30").multiply(yearlyPayments).setScale(2, RoundingMode.HALF_UP);
        List<CsvRow> rows = new ArrayList<>();
        for (int m = 0; m < MONTH_COUNT; m++) {
            int paymentsLate = (m == 5 || m == 6) ? 2 : 0; // late at months 6-7 (0-indexed 5,6), clear after
            rows.add(new CsvRow("L003", FIRST_MONTH.plusMonths(m), balance, noi, yearlyPayments, paymentsLate));
        }
        return rows;
    }

    private List<CsvRow> normalWalk(String loanId, BigDecimal balance, BigDecimal yearlyPayments,
                                     BigDecimal startingNoi, Random random) {
        List<CsvRow> rows = new ArrayList<>();
        BigDecimal noi = startingNoi;
        for (int m = 0; m < MONTH_COUNT; m++) {
            double drift = (random.nextDouble() - 0.5) * 0.02; // +-1% monthly drift
            noi = noi.multiply(BigDecimal.valueOf(1 + drift)).setScale(2, RoundingMode.HALF_UP);
            rows.add(new CsvRow(loanId, FIRST_MONTH.plusMonths(m), balance, noi, yearlyPayments, 0));
        }
        return rows;
    }

    private String propertyTypeFor(int index) {
        int bucket = index % 10;
        if (bucket < 7) return "APARTMENT";
        if (bucket < 9) return "OFFICE";
        return "RETAIL";
    }

    private BigDecimal randomBalance(Random random) {
        double balance = 3_000_000 + random.nextDouble() * 27_000_000; // $3M-$30M
        return BigDecimal.valueOf(balance).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal randomRate(Random random) {
        double rate = 0.05 + random.nextDouble() * 0.02; // 5%-7%
        return BigDecimal.valueOf(rate).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal amortizedAnnualPayment(BigDecimal balance, BigDecimal annualRate) {
        double p = balance.doubleValue();
        double r = annualRate.doubleValue() / 12.0;
        int n = 30 * 12;
        double monthly = p * r / (1 - Math.pow(1 + r, -n));
        return BigDecimal.valueOf(monthly * 12).setScale(2, RoundingMode.HALF_UP);
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn -q test -Dtest=LoanDataGeneratorTest`
Expected: PASS (5 tests)

- [ ] **Step 7: Write `src/main/java/com/cre/earlywarning/generator/GeneratorRunner.java`**

```java
package com.cre.earlywarning.generator;

import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanRepository;
import com.cre.earlywarning.ingest.CsvRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

@Component
@Profile("generator")
public class GeneratorRunner implements CommandLineRunner {

    private final LoanDataGenerator generator;
    private final LoanRepository loanRepository;
    private final long seed;
    private final Path outputDir;

    public GeneratorRunner(LoanDataGenerator generator, LoanRepository loanRepository,
                            @Value("${app.generator.seed}") long seed,
                            @Value("${app.generator.output-dir}") String outputDir) {
        this.generator = generator;
        this.loanRepository = loanRepository;
        this.seed = seed;
        this.outputDir = Path.of(outputDir);
    }

    @Override
    public void run(String... args) throws IOException {
        GeneratedData data = generator.generate(seed);

        for (GeneratedLoan loan : data.loans()) {
            loanRepository.save(new Loan(loan.loanId(), loan.propertyType(), loan.originalBalance(),
                loan.rate(), loan.maturityDate(), loan.underwritingNoi()));
        }

        Files.createDirectories(outputDir);
        for (Map.Entry<YearMonth, List<CsvRow>> entry : data.rowsByMonth().entrySet()) {
            writeMonthFile(entry.getKey(), entry.getValue());
        }
    }

    private void writeMonthFile(YearMonth month, List<CsvRow> rows) throws IOException {
        StringBuilder sb = new StringBuilder("loan_id,month,balance,noi,yearly_payments,payments_late\n");
        for (CsvRow row : rows) {
            sb.append(row.loanId()).append(',')
                .append(row.month()).append(',')
                .append(row.balance()).append(',')
                .append(row.noi()).append(',')
                .append(row.yearlyPayments()).append(',')
                .append(row.paymentsLate()).append('\n');
        }
        Files.writeString(outputDir.resolve("report-" + month + ".csv"), sb.toString());
    }
}
```

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/cre/earlywarning/generator src/test/java/com/cre/earlywarning/generator
git commit -m "feat: add seeded data generator with 3 planted scenarios"
```

---

## Task 11: REST API

**Files:**
- Create: `src/main/java/com/cre/earlywarning/api/AlertDto.java`
- Create: `src/main/java/com/cre/earlywarning/api/LoanHistoryPointDto.java`
- Create: `src/main/java/com/cre/earlywarning/api/LoanDetailDto.java`
- Create: `src/main/java/com/cre/earlywarning/api/LoanQueryService.java`
- Create: `src/main/java/com/cre/earlywarning/api/AlertController.java`
- Create: `src/main/java/com/cre/earlywarning/api/LoanController.java`
- Create: `src/main/java/com/cre/earlywarning/api/ApiExceptionHandler.java`
- Test: `src/test/java/com/cre/earlywarning/api/AlertControllerTest.java`
- Test: `src/test/java/com/cre/earlywarning/api/LoanControllerTest.java`

**Interfaces:**
- Consumes: `AlertService` (Task 7), `Loan`/`LoanRepository`/`LoanMonthEventRepository` (Task 3), `MetricsCalculator` (Task 4).
- Produces: `GET /alerts`, `GET /loans/{id}`, `POST /alerts/{id}/acknowledge` per spec section 7. `LoanQueryService.getLoanDetail(String id): LoanDetailDto` is reused by Task 12/13's screen controllers so the JSON API and HTML screens show identical data.

- [ ] **Step 1: Write `src/main/java/com/cre/earlywarning/api/AlertDto.java`**

```java
package com.cre.earlywarning.api;

import com.cre.earlywarning.alerts.Alert;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public record AlertDto(
    Long id, String loanId, String ruleId, String state, int score,
    int scoreType, int scoreTime, int scoreSize, int ruleVersion,
    String firedMonth, String limitValue, Map<String, String> inputs
) {
    public static AlertDto from(Alert a, ObjectMapper mapper) {
        Map<String, String> inputs;
        try {
            inputs = mapper.readValue(a.getInputsJson(), new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            inputs = Map.of();
        }
        return new AlertDto(a.getId(), a.getLoanId(), a.getRuleId(), a.getState().name(), a.getScore(),
            a.getScoreType(), a.getScoreTime(), a.getScoreSize(), a.getRuleVersion(), a.getFiredMonth(),
            a.getLimitValue(), inputs);
    }
}
```

- [ ] **Step 2: Write `src/main/java/com/cre/earlywarning/api/LoanHistoryPointDto.java`**

```java
package com.cre.earlywarning.api;

import java.math.BigDecimal;

public record LoanHistoryPointDto(String month, BigDecimal dscr, BigDecimal debtYield) {
}
```

- [ ] **Step 3: Write `src/main/java/com/cre/earlywarning/api/LoanDetailDto.java`**

```java
package com.cre.earlywarning.api;

import java.math.BigDecimal;
import java.util.List;

public record LoanDetailDto(
    String loanId, String propertyType, BigDecimal originalBalance, BigDecimal rate,
    String maturityDate, List<LoanHistoryPointDto> history, List<AlertDto> alerts
) {
}
```

- [ ] **Step 4: Write the failing test `src/test/java/com/cre/earlywarning/api/LoanControllerTest.java`**

```java
package com.cre.earlywarning.api;

import com.cre.earlywarning.domain.EventLog;
import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanRepository;
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
    private EventLog eventLog;

    @Test
    void getLoanReturnsHistoryAndAlerts() throws Exception {
        loanRepository.save(new Loan("L600", "APARTMENT", new BigDecimal("5000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2030, 1, 1), new BigDecimal("400000.00")));
        eventLog.append("L600", YearMonth.of(2024, 1), new BigDecimal("5000000.00"),
            new BigDecimal("400000.00"), new BigDecimal("600000.00"), 2);

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
}
```

- [ ] **Step 5: Write the failing test `src/test/java/com/cre/earlywarning/api/AlertControllerTest.java`**

```java
package com.cre.earlywarning.api;

import com.cre.earlywarning.alerts.AlertRepository;
import com.cre.earlywarning.domain.EventLog;
import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanRepository;
import com.cre.earlywarning.alerts.Alert;
import com.cre.earlywarning.alerts.AlertState;
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
    private EventLog eventLog;

    @Autowired
    private AlertRepository alertRepository;

    @Test
    void getAlertsReturnsOpenQueue() throws Exception {
        loanRepository.save(new Loan("L601", "APARTMENT", new BigDecimal("5000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2030, 1, 1), new BigDecimal("400000.00")));
        eventLog.append("L601", YearMonth.of(2024, 1), new BigDecimal("5000000.00"),
            new BigDecimal("400000.00"), new BigDecimal("600000.00"), 2);

        mockMvc.perform(get("/alerts"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].loanId", is("L601")));
    }

    @Test
    void acknowledgeSetsStateToAcknowledged() throws Exception {
        loanRepository.save(new Loan("L602", "APARTMENT", new BigDecimal("5000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2030, 1, 1), new BigDecimal("400000.00")));
        eventLog.append("L602", YearMonth.of(2024, 1), new BigDecimal("5000000.00"),
            new BigDecimal("400000.00"), new BigDecimal("600000.00"), 2);
        Alert alert = alertRepository.findByLoanIdAndRuleId("L602", "R1").orElseThrow();

        mockMvc.perform(post("/alerts/" + alert.getId() + "/acknowledge"))
            .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(
            alertRepository.findById(alert.getId()).orElseThrow().getState()).isEqualTo(AlertState.ACKNOWLEDGED);
    }
}
```

- [ ] **Step 6: Run the tests to verify they fail to compile**

Run: `mvn -q test -Dtest=AlertControllerTest,LoanControllerTest`
Expected: COMPILATION ERROR — controllers and `LoanQueryService` don't exist yet.

- [ ] **Step 7: Write `src/main/java/com/cre/earlywarning/api/LoanQueryService.java`**

```java
package com.cre.earlywarning.api;

import com.cre.earlywarning.alerts.AlertRepository;
import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanMonthEventRepository;
import com.cre.earlywarning.domain.LoanRepository;
import com.cre.earlywarning.metrics.MetricsCalculator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;

@Service
public class LoanQueryService {

    private final LoanRepository loanRepository;
    private final LoanMonthEventRepository eventRepository;
    private final AlertRepository alertRepository;
    private final MetricsCalculator metricsCalculator;
    private final ObjectMapper objectMapper;

    public LoanQueryService(LoanRepository loanRepository, LoanMonthEventRepository eventRepository,
                             AlertRepository alertRepository, MetricsCalculator metricsCalculator,
                             ObjectMapper objectMapper) {
        this.loanRepository = loanRepository;
        this.eventRepository = eventRepository;
        this.alertRepository = alertRepository;
        this.metricsCalculator = metricsCalculator;
        this.objectMapper = objectMapper;
    }

    public LoanDetailDto getLoanDetail(String loanId) {
        Loan loan = loanRepository.findById(loanId)
            .orElseThrow(() -> new NoSuchElementException("Loan " + loanId + " not found"));

        var history = eventRepository.findByLoanIdOrderByMonthAsc(loanId).stream()
            .map(e -> metricsCalculator.calculate(loan, e))
            .map(m -> new LoanHistoryPointDto(m.month().toString(), m.dscr(), m.debtYield()))
            .toList();

        var alerts = alertRepository.findByLoanId(loanId).stream()
            .map(a -> AlertDto.from(a, objectMapper))
            .toList();

        return new LoanDetailDto(loan.getLoanId(), loan.getPropertyType(), loan.getOriginalBalance(),
            loan.getRate(), loan.getMaturityDate().toString(), history, alerts);
    }
}
```

- [ ] **Step 8: Write `src/main/java/com/cre/earlywarning/api/AlertController.java`**

```java
package com.cre.earlywarning.api;

import com.cre.earlywarning.alerts.AlertService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/alerts")
public class AlertController {

    private final AlertService alertService;
    private final ObjectMapper objectMapper;

    public AlertController(AlertService alertService, ObjectMapper objectMapper) {
        this.alertService = alertService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<AlertDto> list() {
        return alertService.openQueue().stream().map(a -> AlertDto.from(a, objectMapper)).toList();
    }

    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<Void> acknowledge(@PathVariable Long id) {
        alertService.acknowledge(id);
        return ResponseEntity.ok().build();
    }
}
```

- [ ] **Step 9: Write `src/main/java/com/cre/earlywarning/api/LoanController.java`**

```java
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

    @GetMapping("/{id}")
    public LoanDetailDto detail(@PathVariable String id) {
        return loanQueryService.getLoanDetail(id);
    }
}
```

- [ ] **Step 10: Write `src/main/java/com/cre/earlywarning/api/ApiExceptionHandler.java`**

```java
package com.cre.earlywarning.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<String> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }
}
```

- [ ] **Step 11: Run the tests to verify they pass**

Run: `mvn -q test -Dtest=AlertControllerTest,LoanControllerTest`
Expected: PASS (4 tests)

- [ ] **Step 12: Commit**

```bash
git add src/main/java/com/cre/earlywarning/api src/test/java/com/cre/earlywarning/api
git commit -m "feat: add REST API for alerts and loan detail"
```

---

## Task 12: Queue and Loan Detail Screens

**Files:**
- Create: `src/main/java/com/cre/earlywarning/web/QueueViewController.java`
- Create: `src/main/java/com/cre/earlywarning/web/LoanDetailViewController.java`
- Create: `src/main/resources/templates/queue.html`
- Create: `src/main/resources/templates/loan-detail.html`
- Test: `src/test/java/com/cre/earlywarning/web/QueueViewControllerTest.java`
- Test: `src/test/java/com/cre/earlywarning/web/LoanDetailViewControllerTest.java`

**Interfaces:**
- Consumes: `AlertService` (Task 7), `LoanQueryService`/`AlertDto` (Task 11).
- Produces: `GET /` (queue screen), `GET /loans/{id}/detail` (loan detail screen), `POST /ui/alerts/{id}/acknowledge` (form-driven acknowledge that redirects back to `/`, kept separate from the JSON `POST /alerts/{id}/acknowledge` API contract). No later task depends on this one.

- [ ] **Step 1: Write the failing test `src/test/java/com/cre/earlywarning/web/QueueViewControllerTest.java`**

```java
package com.cre.earlywarning.web;

import com.cre.earlywarning.domain.EventLog;
import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class QueueViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private EventLog eventLog;

    @Test
    void queuePageListsOpenAlerts() throws Exception {
        loanRepository.save(new Loan("L610", "APARTMENT", new BigDecimal("5000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2030, 1, 1), new BigDecimal("400000.00")));
        eventLog.append("L610", YearMonth.of(2024, 1), new BigDecimal("5000000.00"),
            new BigDecimal("400000.00"), new BigDecimal("600000.00"), 2);

        mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("L610")));
    }
}
```

- [ ] **Step 2: Write the failing test `src/test/java/com/cre/earlywarning/web/LoanDetailViewControllerTest.java`**

```java
package com.cre.earlywarning.web;

import com.cre.earlywarning.domain.EventLog;
import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LoanDetailViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private EventLog eventLog;

    @Test
    void loanDetailPageShowsChartDataAndAlerts() throws Exception {
        loanRepository.save(new Loan("L611", "OFFICE", new BigDecimal("8000000.00"),
            new BigDecimal("0.0600"), LocalDate.of(2030, 1, 1), new BigDecimal("600000.00")));
        eventLog.append("L611", YearMonth.of(2024, 1), new BigDecimal("8000000.00"),
            new BigDecimal("600000.00"), new BigDecimal("550000.00"), 0);

        mockMvc.perform(get("/loans/L611/detail"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("chart.js")))
            .andExpect(content().string(containsString("2024-01")));
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `mvn -q test -Dtest=QueueViewControllerTest,LoanDetailViewControllerTest`
Expected: FAIL — `/` and `/loans/{id}/detail` return 404 (no controllers/templates registered yet).

- [ ] **Step 4: Write `src/main/java/com/cre/earlywarning/web/QueueViewController.java`**

```java
package com.cre.earlywarning.web;

import com.cre.earlywarning.alerts.AlertService;
import com.cre.earlywarning.api.AlertDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class QueueViewController {

    private final AlertService alertService;
    private final ObjectMapper objectMapper;

    public QueueViewController(AlertService alertService, ObjectMapper objectMapper) {
        this.alertService = alertService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/")
    public String queue(Model model) {
        var alerts = alertService.openQueue().stream().map(a -> AlertDto.from(a, objectMapper)).toList();
        model.addAttribute("alerts", alerts);
        return "queue";
    }

    @PostMapping("/ui/alerts/{id}/acknowledge")
    public String acknowledge(@PathVariable Long id) {
        alertService.acknowledge(id);
        return "redirect:/";
    }
}
```

- [ ] **Step 5: Write `src/main/java/com/cre/earlywarning/web/LoanDetailViewController.java`**

```java
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
```

- [ ] **Step 6: Write `src/main/resources/templates/queue.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8"/>
    <title>Loan Early-Warning Queue</title>
</head>
<body>
<h1>Open Alerts</h1>
<table border="1" cellpadding="6">
    <thead>
        <tr><th>Loan</th><th>Rule</th><th>Score</th><th>Fired Month</th><th></th></tr>
    </thead>
    <tbody>
        <tr th:each="alert : ${alerts}">
            <td><a th:href="@{'/loans/' + ${alert.loanId} + '/detail'}" th:text="${alert.loanId}"></a></td>
            <td th:text="${alert.ruleId}"></td>
            <td th:text="${alert.score}"></td>
            <td th:text="${alert.firedMonth}"></td>
            <td>
                <form th:action="@{'/ui/alerts/' + ${alert.id} + '/acknowledge'}" method="post">
                    <button type="submit">Acknowledge</button>
                </form>
            </td>
        </tr>
    </tbody>
</table>
</body>
</html>
```

- [ ] **Step 7: Write `src/main/resources/templates/loan-detail.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8"/>
    <title>Loan Detail</title>
    <script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.4/dist/chart.umd.min.js"></script>
</head>
<body>
<h1 th:text="'Loan ' + ${loan.loanId}"></h1>
<p th:text="'Property type: ' + ${loan.propertyType}"></p>
<p th:text="'Original balance: ' + ${loan.originalBalance}"></p>
<p th:text="'Maturity: ' + ${loan.maturityDate}"></p>

<canvas id="metricsChart" width="800" height="400"></canvas>
<script th:inline="javascript">
    const chartData = JSON.parse(/*[[${chartDataJson}]]*/ '{}');
    new Chart(document.getElementById('metricsChart'), {
        type: 'line',
        data: {
            labels: chartData.labels,
            datasets: [
                { label: 'DSCR', data: chartData.dscr, borderColor: 'blue', fill: false },
                { label: 'Debt Yield', data: chartData.debtYield, borderColor: 'red', fill: false }
            ]
        }
    });
</script>

<h2>Alerts</h2>
<table border="1" cellpadding="6">
    <thead>
        <tr><th>Rule</th><th>State</th><th>Score</th><th>Fired Month</th><th>Limit</th><th>Inputs</th></tr>
    </thead>
    <tbody>
        <tr th:each="alert : ${loan.alerts}">
            <td th:text="${alert.ruleId}"></td>
            <td th:text="${alert.state}"></td>
            <td th:text="${alert.score}"></td>
            <td th:text="${alert.firedMonth}"></td>
            <td th:text="${alert.limitValue}"></td>
            <td th:text="${alert.inputs}"></td>
        </tr>
    </tbody>
</table>
</body>
</html>
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `mvn -q test -Dtest=QueueViewControllerTest,LoanDetailViewControllerTest`
Expected: PASS (2 tests)

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/cre/earlywarning/web src/main/resources/templates src/test/java/com/cre/earlywarning/web
git commit -m "feat: add queue and loan-detail Thymeleaf screens with Chart.js"
```

---

## Task 13: Planted Scenario Integration Tests

**Files:**
- Test: `src/test/java/com/cre/earlywarning/scenario/PlantedScenarioTest.java`

**Interfaces:**
- Consumes: `LoanDataGenerator` (Task 10), `IngestService` (Task 8), `AlertRepository` (Task 7). Pushes every generated row for a given loan through `IngestService.ingest` directly (bypassing the folder poller, which is already tested in isolation in Task 9) and asserts on the resulting `Alert` rows.
- Produces: no new production code — this task is the spec section 11 "Early" and "Gap in the standard list" acceptance checks, encoded as tests. No later task depends on it.

- [ ] **Step 1: Write `src/test/java/com/cre/earlywarning/scenario/PlantedScenarioTest.java`**

```java
package com.cre.earlywarning.scenario;

import com.cre.earlywarning.alerts.AlertRepository;
import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanRepository;
import com.cre.earlywarning.generator.GeneratedData;
import com.cre.earlywarning.generator.GeneratedLoan;
import com.cre.earlywarning.generator.LoanDataGenerator;
import com.cre.earlywarning.ingest.CsvRow;
import com.cre.earlywarning.ingest.IngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PlantedScenarioTest {

    private static final long SEED = 42L;

    @Autowired
    private LoanDataGenerator generator;

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private IngestService ingestService;

    @Autowired
    private AlertRepository alertRepository;

    private GeneratedData data;

    @BeforeEach
    void seedLoans() {
        data = generator.generate(SEED);
        for (GeneratedLoan loan : data.loans()) {
            loanRepository.save(new Loan(loan.loanId(), loan.propertyType(), loan.originalBalance(),
                loan.rate(), loan.maturityDate(), loan.underwritingNoi()));
        }
    }

    @Test
    void slowSlideScenario_R4FiresAtLeastThreeMonthsBeforeR2() {
        YearMonth r4FiredAt = null;
        YearMonth r2FiredAt = null;

        for (YearMonth month : sortedMonths()) {
            CsvRow row = rowFor("L001", month);
            ingestService.ingest(row);

            if (r4FiredAt == null && alertRepository.findByLoanIdAndRuleId("L001", "R4").isPresent()) {
                r4FiredAt = month;
            }
            if (r2FiredAt == null && alertRepository.findByLoanIdAndRuleId("L001", "R2").isPresent()) {
                r2FiredAt = month;
            }
        }

        assertThat(r4FiredAt).isNotNull();
        assertThat(r2FiredAt).isNotNull();
        long monthsEarly = java.time.temporal.ChronoUnit.MONTHS.between(r4FiredAt, r2FiredAt);
        assertThat(monthsEarly).isGreaterThanOrEqualTo(3);
    }

    @Test
    void weakRefinanceScenario_R5FiresAndR2StaysSilent() {
        for (YearMonth month : sortedMonths()) {
            ingestService.ingest(rowFor("L002", month));
        }

        assertThat(alertRepository.findByLoanIdAndRuleId("L002", "R5")).isPresent();
        assertThat(alertRepository.findByLoanIdAndRuleId("L002", "R2")).isEmpty();
    }

    @Test
    void missedPaymentsScenario_R1FiresThenResolves() {
        for (YearMonth month : sortedMonths()) {
            ingestService.ingest(rowFor("L003", month));
        }

        var alert = alertRepository.findByLoanIdAndRuleId("L003", "R1").orElseThrow();
        assertThat(alert.getState().name()).isEqualTo("RESOLVED");
    }

    private List<YearMonth> sortedMonths() {
        return data.rowsByMonth().keySet().stream().sorted().toList();
    }

    private CsvRow rowFor(String loanId, YearMonth month) {
        Optional<CsvRow> row = data.rowsByMonth().get(month).stream()
            .filter(r -> r.loanId().equals(loanId)).findFirst();
        return row.orElseThrow();
    }
}
```

- [ ] **Step 2: Run the test**

Run: `mvn -q test -Dtest=PlantedScenarioTest`
Expected: PASS (3 tests). If any fails, the most likely cause is the generator's scenario data in Task 10 not producing the exact DSCR/debt-yield/payments-late trajectory the rule thresholds in `rules-v1.yaml` expect — adjust the generator's scenario methods (not the rules or the config), since the scenarios are the thing designed to prove the rules, not vice versa.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/cre/earlywarning/scenario
git commit -m "test: verify the 3 planted scenarios against the real rule set end-to-end"
```

---

## Task 14: Concurrency & Idempotency Hardening (Testcontainers-MySQL)

**Files:**
- Test: `src/test/java/com/cre/earlywarning/concurrency/ConcurrencyAndIdempotencyIntegrationTest.java`

**Interfaces:**
- Consumes: `FolderPollerJob` (Task 9), `IngestService` (Task 8), `LoanRepository`/`LoanMonthEventRepository` (Task 3) — all retrieved from real, independently-booted `ConfigurableApplicationContext` instances pointed at one shared Testcontainers MySQL instance, which is the only way to honestly exercise ShedLock's and the unique constraint's real-MySQL behavior (design doc section 3).
- Produces: no new production code. This is the spec section 11 "One poller" and "Safe intake" acceptance checks, run against real MySQL instead of H2.

- [ ] **Step 1: Write `src/test/java/com/cre/earlywarning/concurrency/ConcurrencyAndIdempotencyIntegrationTest.java`**

```java
package com.cre.earlywarning.concurrency;

import com.cre.earlywarning.EarlyWarningApplication;
import com.cre.earlywarning.domain.Loan;
import com.cre.earlywarning.domain.LoanMonthEventRepository;
import com.cre.earlywarning.domain.LoanRepository;
import com.cre.earlywarning.ingest.CsvRow;
import com.cre.earlywarning.ingest.FolderPollerJob;
import com.cre.earlywarning.ingest.IngestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ConcurrencyAndIdempotencyIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("earlywarning")
        .withUsername("earlywarning")
        .withPassword("earlywarning");

    private ConfigurableApplicationContext nodeA;
    private ConfigurableApplicationContext nodeB;

    @AfterEach
    void tearDown() {
        if (nodeA != null) nodeA.close();
        if (nodeB != null) nodeB.close();
    }

    @Test
    void twoNodesPollingTheSameInbox_onlyOneProcessesEachFile(@TempDir Path sharedDirs) throws Exception {
        Path inbox = sharedDirs.resolve("inbox");
        Path processing = sharedDirs.resolve("processing");
        Path done = sharedDirs.resolve("done");
        Path failed = sharedDirs.resolve("failed");
        Files.createDirectories(inbox);

        nodeA = startNode(inbox, processing, done, failed);
        nodeB = startNode(inbox, processing, done, failed);
        seedLoan(nodeA, "L950");

        Path file = inbox.resolve("report-2024-01.csv");
        Files.writeString(file, """
            loan_id,month,balance,noi,yearly_payments,payments_late
            L950,2024-01,5000000.00,400000.00,300000.00,0
            """);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - 10_000));

        FolderPollerJob pollerA = nodeA.getBean(FolderPollerJob.class);
        FolderPollerJob pollerB = nodeB.getBean(FolderPollerJob.class);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        executor.submit(pollerA::poll);
        executor.submit(pollerB::poll);
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        assertThat(Files.exists(file)).isFalse();
        assertThat(Files.exists(done.resolve("report-2024-01.csv"))).isTrue();
        assertThat(Files.exists(processing.resolve("report-2024-01.csv"))).isFalse();

        LoanMonthEventRepository eventRepository = nodeA.getBean(LoanMonthEventRepository.class);
        assertThat(eventRepository.findByLoanIdOrderByMonthAsc("L950")).hasSize(1);
    }

    @Test
    void doubleIngestAgainstRealMySqlUniqueConstraintIsSafe(@TempDir Path dirs) {
        nodeA = startNode(dirs.resolve("inbox"), dirs.resolve("processing"), dirs.resolve("done"),
            dirs.resolve("failed"));
        seedLoan(nodeA, "L951");
        IngestService ingestService = nodeA.getBean(IngestService.class);
        CsvRow row = new CsvRow("L951", YearMonth.of(2024, 1), new BigDecimal("5000000.00"),
            new BigDecimal("400000.00"), new BigDecimal("300000.00"), 0);

        boolean first = ingestService.ingest(row);
        boolean second = ingestService.ingest(row);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        LoanMonthEventRepository eventRepository = nodeA.getBean(LoanMonthEventRepository.class);
        assertThat(eventRepository.findByLoanIdOrderByMonthAsc("L951")).hasSize(1);
    }

    private ConfigurableApplicationContext startNode(Path inbox, Path processing, Path done, Path failed) {
        Properties props = new Properties();
        props.setProperty("spring.datasource.url", mysql.getJdbcUrl());
        props.setProperty("spring.datasource.username", mysql.getUsername());
        props.setProperty("spring.datasource.password", mysql.getPassword());
        props.setProperty("spring.jpa.hibernate.ddl-auto", "validate");
        props.setProperty("spring.main.web-application-type", "none");
        props.setProperty("app.rules.active-version", "1");
        props.setProperty("app.generator.seed", "42");
        props.setProperty("app.intake.inbox-dir", inbox.toString());
        props.setProperty("app.intake.processing-dir", processing.toString());
        props.setProperty("app.intake.done-dir", done.toString());
        props.setProperty("app.intake.failed-dir", failed.toString());
        props.setProperty("app.generator.output-dir", inbox.toString());

        return new SpringApplicationBuilder(EarlyWarningApplication.class).properties(props).run();
    }

    private void seedLoan(ConfigurableApplicationContext context, String loanId) {
        LoanRepository loanRepository = context.getBean(LoanRepository.class);
        loanRepository.save(new Loan(loanId, "APARTMENT", new BigDecimal("5000000.00"),
            new BigDecimal("0.0550"), LocalDate.of(2030, 1, 1), new BigDecimal("400000.00")));
    }
}
```

- [ ] **Step 2: Run the test**

Run: `mvn -q test -Dtest=ConcurrencyAndIdempotencyIntegrationTest`
Expected: PASS (2 tests). This test spins up a real MySQL container and two full application contexts, so it is slow (tens of seconds) — that is expected and is why it's kept separate from the fast H2 suite per the design doc.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/cre/earlywarning/concurrency
git commit -m "test: verify ShedLock single-poller guarantee and double-ingest safety against real MySQL"
```

---

## Task 15: README and Manual End-to-End Verification

**Files:**
- Create: `README.md`

**Interfaces:**
- Consumes: nothing new — this task documents and manually exercises everything built in Tasks 1-14.
- Produces: the spec section 10 README deliverable, and a documented manual verification pass against every row of spec section 11's success table (the plan's Definition of Done).

- [ ] **Step 1: Write `README.md`**

```markdown
# Loan Surveillance Early-Warning Engine

A small Spring Boot service that flags commercial real estate loans trending toward trouble before
they cross the fixed thresholds on a standard monthly watchlist. No LLM, no ML — every alert traces
to a rule ID, a rule version, and the exact input values that fired it.

## The problem and who it serves

Servicers watch loans against fixed limits once a month; a loan can slide for months before any
alarm rings. $76.6B of CMBS loans reach maturity in 2026, and about 36% of them carry a debt yield
below 8% — the zone where refinancing often fails. The primary user is a servicer analyst who needs
a short, ranked, explainable list each month. See `cre-problem-research.md` for the full research.

## What was learned

Debt yield separates loans that refinance from loans that fail (13-14% vs. about 9%). The standard
watchlist checks absolute levels, not direction of change — that gap is what rules R4 and R5 target.
Full writeup in `cre-problem-research.md`.

## How scope was decided

The build spec (`option-a-research.md`) was narrowed from an earlier 12-rule, 3-screen, 400-loan
draft down to 5 rules, 2 screens, 100 loans — the smallest system that proves three claims: earlier
warning than the standard watchlist, coverage of a gap the watchlist misses, and full auditability of
every alert. See `docs/superpowers/specs/2026-10-03-loan-early-warning-design.md` for the
implementation-level decisions (stack, testing strategy, build sequencing) made on top of that spec.

## Assumptions and tradeoffs

See the "Global Constraints" and "Declared Assumptions" sections at the top of
`docs/superpowers/plans/2026-10-03-loan-early-warning.md` for the complete, numbered list (synthetic
data, rules over ML, folder-poller over Kafka, H2 for fast tests with Testcontainers-MySQL added for
the two concurrency-sensitive guarantees, flat amortization schedule, etc).

## Architecture

```
generator -> CSV file -> FolderPoller (ShedLock) -> IngestService -> EventLog -> MetricsCalculator -> RuleEngine -> AlertService -> REST API -> Thymeleaf screens
```

One Java 21 / Spring Boot 3 service. MySQL 8 via Docker Compose. Every monthly report becomes an
append-only event keyed by `loan_id + month`, so re-ingesting a file changes nothing and the whole
pipeline is replayable.

## What was built

- Seeded data generator: 100 loans, 24 months each, with 3 scripted planted scenarios (slow slide,
  weak refinance, missed payments).
- Idempotent event log and ShedLock-guarded folder poller.
- Rules R1-R5 as plain Java classes against versioned YAML config (`rules-v1.yaml`).
- Alert service: dedup by loan+rule, 3-state lifecycle (Open/Acknowledged/Resolved), 3-part score.
- REST API: `GET /alerts`, `GET /loans/{id}`, `POST /alerts/{id}/acknowledge`.
- Two Thymeleaf screens: ranked alert queue, loan detail with a Chart.js DSCR/debt-yield chart.
- Tests: one per rule, one per planted scenario end-to-end, plus Testcontainers-MySQL tests for the
  two-node ShedLock guarantee and double-ingest safety.

## Running it

```bash
docker compose up -d
mvn spring-boot:run -Dspring-boot.run.profiles=generator   # writes loans + 24 months of CSVs into inbox/
mvn spring-boot:run                                        # starts the app; poller picks up inbox/ every 10s
```

Then open `http://localhost:8080/` for the alert queue, or `http://localhost:8080/loans/L001/detail`
for a loan's chart and alert history.

## What would change or build next

1. Test rules R4/R5 on real SEC loan data (the synthetic data proves the mechanics, not the limits).
2. Add a rule for large tenants whose leases end soon.
3. Add a broker view for loans with a refinance gap.
4. Add Kafka as a second intake path calling the same `IngestService.ingest(row)`; later, move the
   event log itself onto a Kafka topic.
5. Add escalation for alerts that go unacknowledged.
```

- [ ] **Step 2: Run the full test suite one more time**

Run: `mvn -q test`
Expected: BUILD SUCCESS, all tests from Tasks 1-14 pass (the Testcontainers tests will take the longest; this is expected).

- [ ] **Step 3: Manual end-to-end verification — walk through every row of spec section 11's table**

Run:
```bash
docker compose up -d
mvn spring-boot:run -Dspring-boot.run.profiles=generator
mvn spring-boot:run
```

Then, in a separate terminal, with the app running:

```bash
curl -s http://localhost:8080/alerts | python3 -m json.tool
```

Confirm by hand:
- **Early**: `curl http://localhost:8080/loans/L001` shows an R4 alert with a `firedMonth` at least 3
  months before the R2 alert's `firedMonth` (or R2 absent if the window didn't reach it yet).
- **Gap in the standard list**: `curl http://localhost:8080/loans/L002` shows an R5 alert and no R2
  alert.
- **Clear**: every alert object in the `/alerts` response has a non-empty `limitValue` and `inputs`.
- **Quiet**: `curl -s http://localhost:8080/alerts | python3 -c "import json,sys; print(len(json.load(sys.stdin)))"`
  prints fewer than 15.
- **Repeatable**: stop the app, delete the MySQL volume (`docker compose down -v`), repeat the above —
  the same alerts appear.
- **Safe intake**: copy a `done/` CSV back into `inbox/` and wait 10+ seconds; confirm via
  `curl http://localhost:8080/alerts` that nothing changed.
- **One poller**: this is already covered by Task 14's automated Testcontainers test — note that in
  the README rather than re-running it manually.

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: add README covering problem, research, architecture, and next steps"
```
