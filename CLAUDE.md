# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What is being built

A **Loan Surveillance Early-Warning Engine** for commercial real estate (CRE) loans: a tool that flags
loans trending toward trouble before they cross the fixed thresholds on a standard monthly watchlist.
Full spec: `option-a-research.md`. Key constraints from that spec:

- **Narrow scope on purpose**: 5 rules (R1–R5), 2 screens (ranked alert queue, loan detail), 100 synthetic
  loans × 24 months. Section 10 of the spec has an explicit "do not build" list (broker view, tenant-lease
  rules, refinance gap, LTV, escalation, comments, real data import, Kafka, login) — do not add these
  without being asked.
- **Synthetic data only**, generated with a seeded random walk so runs are repeatable. An optional SEC
  ABS-EE importer is called out as explicitly out of scope for now.

### Planned architecture (from spec section 8)

One Java 21 / Spring Boot 3 service, structured as an append-only event pipeline so every step is
replayable and auditable:

```
data generator → monthly report CSV → folder poller → ingest → event log → metrics → rules → alerts → API → screens
```

- **Intake**: a scheduled folder poller (not Kafka — called out as a deliberate tradeoff to avoid running a
  broker) watches `inbox/`, moves files through `processing/` → `done/`/`failed/` atomically, and calls
  `IngestService.ingest(row)` per CSV row. A Kafka consumer could later call the same `ingest` method with
  no other changes.
- **Idempotency**: the event key is `loan_id + month`. Re-ingesting the same file/row is a no-op — this is a
  hard correctness requirement, not an optimization (see spec section 11, "Safe intake").
- **Concurrency**: ShedLock (JDBC provider, MySQL `shedlock` table) ensures only one node runs the poller at
  a time, so the service can scale horizontally without double-processing a file.
- **Storage**: MySQL 8 via Docker Compose; H2 in MySQL-compatibility mode for tests.
- **Numbers**: use `BigDecimal` throughout for money/ratio math, never floating point. DSCR and debt yield
  are rounded to 4 decimal places.
- **Rules**: plain Java classes (no rules engine — 5 rules don't need one), with limits defined in a
  versioned YAML config. Every alert records which rule version fired it.
- **Screens**: server-rendered Thymeleaf (one deployable unit, no separate frontend build).

### Domain vocabulary (spec section 3)

- **DSCR** = NOI / yearly loan payments. ~1.25 is healthy; below 1.10 triggers R2.
- **Debt yield** = NOI / loan balance. Below 8% near maturity triggers R5.
- **Maturity** = loan end date; most CRE loans require a refinance or payoff at maturity rather than
  amortizing to zero.
- R1–R3 mirror the standard CREFC watchlist (late payments, low DSCR, maturity soon). R4 (DSCR falling) and
  R5 (low debt yield near maturity) are the project's own early-warning additions — the thing being tested
  is whether R4/R5 fire meaningfully earlier than R1–R3 on the three planted scenarios (spec section 4.1).

## Working on this project

- Treat `option-a-research.md` section 10's checklist as the backlog and section 11 as the acceptance
  criteria — a change is "done" when it satisfies one of the "How to judge success" rows (early firing,
  watchlist-gap coverage, auditability, alert quietness, repeatability, safe re-ingest, single-poller
  correctness under two nodes).
- The three planted scenarios (slow slide, weak refinance, missed payments — spec section 4.1) are the
  primary test fixtures; each has a known correct outcome that tests should assert against directly.
- Keep assumptions/tradeoffs changes consistent with spec section 9 — if a future decision contradicts a
  listed tradeoff, call that out explicitly since the research
  docs frame these as deliberate, interview-relevant choices.

### Tech Stack
- Java
- Springboot

- Ensure all relevant tech stack defined in the plans are available, if not, proceed with installation first. 

Implementation should be test driven. Before claiming done, run the application and confirm end to end is working. When in doubt, ask questions

# Project Rules
## Rule 1 - Think Before Coding.
No silent assumptions. State what you're assuming. Surface tradeoffs.
Ask before guessing. Push back when a simpler approach exists.
## Rule 2 - Simplicity First.
Minimum code that solves the problem.
No speculative features. No abstractions for single-use code. If a senior engineer would call it overcomplicated - simplify.
## Rule 3 - Surgical Changes.
Touch only what you must. Don't
"improve" adjacent code, comments, or formatting. Don't refactor what isn't broken. Match existing style.
## Rule 4 - Goal-Driven Execution.
Define success criteria. Loop until verified. Don't tell Claude what steps to follow, tell it what success looks like and let it iterate.
## Rule 5 - Use the model only for judgment calls
Use Claude for: classification, drafting, summarization, extraction from unstructured text.
Do NOT use Claude for: routing, retries, status-code handling, deterministic transforms.
If a status code already answers the question, plain code answers the question.
## Rule 6 - Surface conflicts, don't average them
If two existing patterns in the codebase contradict, don't blend them.
Pick one (the more recent / more tested), explain why, and flag the other for cleanup.
"Average" code that satisfies both rules is the worst code.
## Rule 7 - Read before you write Before adding code in a file, read the file's exports, the immediate caller, and any obvious shared utilities.
If you don't understand why existing code is structured the way it is, ask before adding to it.
"Looks orthogonal to me" is the most dangerous phrase in this codebase.
## Rule 8 - Tests verify intent, not just behavior
Every test must encode WHY the behavior matters, not just WHAT it does.
A test like
'expect (getUserName()). toBe ('John')'
is worthless if the function takes a hardcoded ID.
If you can't write a test that would fail when business logic changes, the function is wrong.
## Rule 9 - Checkpoint after every significant step
After completing each step in a multi-step task:
summarize what was
done, what's verified, what's left.
Don't continue from a state you can't describe back to me.
If you lose track, stop and restate.
## Rule 10 - Fail loud
If you can't be sure something worked, say so explicitly.
"Migration completed" is wrong if 30 records were skipped silently.
"Tests pass" is wrong if you skipped any-
"Feature works" is wrong if you didn't verify the edge case I asked about.
Default to surfacing uncertainty, not hiding it.