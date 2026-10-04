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
