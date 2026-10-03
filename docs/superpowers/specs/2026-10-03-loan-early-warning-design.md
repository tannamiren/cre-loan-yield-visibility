# Loan Surveillance Early-Warning Engine — Design

Date: 2026-10-03
Status: Approved
Source spec: `option-a-research.md` (authoritative for scope, rules, API, scoring, assumptions/tradeoffs)
Related: `goal.md`, `cre-problem-research.md`, `CLAUDE.md`

This document captures the implementation decisions needed to turn `option-a-research.md` into working
code. It does not restate the spec's domain rationale, rule definitions, scoring table, or
assumptions/tradeoffs — those are already final in `option-a-research.md` sections 3–9 and are treated as
binding here. This doc only covers what the spec left open: stack choices, test strategy for
concurrency-sensitive guarantees, and build sequencing.

## 1. Stack

- **Language/framework**: Java 21, Spring Boot 3 (per `CLAUDE.md`).
- **Build tool**: Maven.
- **Storage**: MySQL 8 via Docker Compose for local dev/run. H2 in MySQL-compatibility mode for fast
  unit/rule/scenario tests. Testcontainers-MySQL for a dedicated concurrency/idempotency integration suite
  (see section 3 — this is additive to the spec's H2-for-tests choice, not a replacement, because two of the
  spec's section 11 success criteria are concurrency-sensitive in ways H2 cannot honestly verify).
- **Chart rendering**: Chart.js via CDN `<script>` tag in the loan-detail Thymeleaf template. No bundler, no
  npm step — stays within the spec's "one deployable unit, no separate frontend build" constraint while
  giving a real line chart with alert markers instead of a bare data table.

## 2. Architecture

Pipeline (per spec section 8), implemented as distinct packages with narrow interfaces so each stage can be
understood and tested independently:

```
generator → CSV file → FolderPoller (ShedLock) → IngestService → EventLog → MetricsCalculator → RuleEngine → AlertService → REST API → Thymeleaf screens
```

- **`generator`**: standalone runnable that writes seeded CSVs for 100 loans × 24 months, with the 3
  planted scenarios (spec section 4) injected at known loan IDs. Same seed → same output.
- **`ingest`**: `FolderPoller` is a `@Scheduled` job (10s interval) guarded by ShedLock (`report-poller`
  lock, JDBC provider, MySQL `shedlock` table, `lockAtMostFor` 5m, `lockAtLeastFor` 5s). It moves files
  `inbox/` → `processing/` → `done/`/`failed/` atomically, parses rows, and calls
  `IngestService.ingest(row)` per row.
- **`EventLog`**: append-only table, unique constraint on `(loan_id, month)`. The DB constraint is the
  idempotency guarantee — not application-level deduping alone — so a repeat delivery is a no-op by
  construction.
- **`MetricsCalculator`**: pure functions computing DSCR, debt yield, and months-to-maturity from event log
  rows. `BigDecimal` throughout, rounded to 4 decimal places.
- **`RuleEngine`**: 5 plain `Rule` classes (R1–R5), limits read from a versioned YAML config. Each rule
  returns fire/no-fire plus the exact input values and limit that mattered, for audit.
- **`AlertService`**: dedupes by `(loan_id, rule_id)` — a later firing of the same rule updates the existing
  alert rather than creating a duplicate. Implements the 3-state lifecycle (Open → Acknowledged →
  Resolved, auto-resolving after 2 clear months) and the 3-part score.
- **API**: `GET /alerts` (ranked queue), `GET /loans/{id}` (history + alerts), `POST /alerts/{id}/acknowledge`
  (spec section 7) — no changes to these contracts.
- **Screens**: server-rendered Thymeleaf — queue page and loan-detail page (24-month DSCR/debt-yield chart
  with alert markers, alert list with inputs/limit/rule version).

**Data model**: `loan`, `loan_month` as specified in spec section 4, plus `event_log` and `alert`. Rule
limits live in versioned YAML files on disk (e.g. `rules-v1.yaml`, `rules-v2.yaml`), each carrying its own
`version` field; no separate database table for rule config. The `alert` table stores the version number
that fired it, read straight from the YAML.

## 3. Testing Strategy

**Unit/rule tests (H2, fast, run on every build)**:
- One test per rule (R1–R5), each asserting a specific input combination against the rule's actual
  business condition — not a trivial return-value check (per `CLAUDE.md` Rule 8).
- `MetricsCalculator` tests for DSCR/debt-yield/months-to-maturity, including rounding edge cases.
- Alert dedup/state-machine tests (open → acknowledged → resolved after 2 clear months; no duplicate
  alerts on repeated rule fires for the same loan+rule).
- Scoring tests for each of the 3 score components (type, time-to-maturity, loan size).

**Scenario tests (H2)** — the 3 planted scenarios (spec section 4) are first-class fixtures, asserting the
exact outcomes the spec commits to:
- Slow slide: R4 fires at least 3 months before R2.
- Weak refinance: R5 fires; R2 stays silent.
- Missed payments: R1 fires, later auto-resolves.

**Integration tests (Testcontainers-MySQL)** — for the two concurrency-sensitive guarantees H2's MySQL-mode
emulation cannot honestly verify:
- Two `FolderPoller` instances (two app contexts) pointed at one `inbox/` with one real MySQL +
  `shedlock` table → assert exactly one instance processes a given file (spec section 11, "One poller").
- Drop the same CSV twice through real ingest → assert no duplicate events and no duplicate/changed alerts,
  relying on the real unique constraint (spec section 11, "Safe intake").

**Manual end-to-end verification before declaring done** (per `CLAUDE.md` Rule 10 — fail loud, verify don't
assume): `docker compose up`, run the generator, start the app, drop a scenario CSV into `inbox/`, confirm
via `GET /alerts` and the queue screen that the expected alert appears with the correct score and a full
audit trail (rule id, version, inputs, limit).

## 4. Build Sequence

Walking skeleton first, then breadth — de-risks integration problems early and keeps something demoable
throughout, given the Monday Oct 5 11:59pm ET deadline.

1. **Skeleton**: Spring Boot app boots; Docker Compose MySQL up; `loan`/`loan_month`/`event_log` tables via
   migration; generator produces one seeded CSV; `FolderPoller` (with ShedLock) picks it up;
   `IngestService` writes events. No rules yet — prove the plumbing works end-to-end.
2. **First vertical slice**: wire R1 only → `AlertService` → `GET /alerts` → bare queue screen. Something
   demoable exists almost immediately.
3. **Fill in breadth**: add R2–R5, the YAML versioned config, scoring, the 3 alert states, the loan-detail
   screen with the Chart.js chart, `POST /alerts/{id}/acknowledge`.
4. **Scenario correctness**: inject the 3 planted scenarios into the generator; add the scenario tests;
   confirm they pass against the real rule set (not just isolated rule unit tests).
5. **Concurrency/idempotency hardening**: Testcontainers tests for two-node polling and double-delivery
   safety.
6. **Polish for interview**: README (problem, research, assumptions, tradeoffs, architecture, next steps —
   spec section 10 deliverable); confirm the "do not build" list (spec section 10) stayed untouched; confirm
   every row of the spec section 11 success table passes; keep commit history clean and legible (per
   `goal.md`'s explicit instruction to preserve prompts/commit history for the harness conversation).

## 5. Definition of Done

Spec section 11's table, literally — each row verified by a passing test or an explicit documented manual
check, never assumed:

- Early: scenario 1 — R4 fires at least 3 months before R2.
- Gap in the standard list: scenario 2 — R5 fires, R2 stays silent.
- Clear: every alert shows the rule, the inputs, and the limit.
- Quiet: fewer than 15 open alerts per 100 loans in a normal month.
- Repeatable: the same seed gives the same alerts.
- Safe intake: dropping the same file twice does not change the alerts.
- One poller: running two nodes on one inbox results in exactly one node processing each file.

## Out of scope (unchanged from spec)

Broker view, tenant-lease rules, refinance gap, loan-to-value, alert escalation, analyst comment form, real
SEC data import, Kafka, login. See spec section 10, "Do not build."
