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

## The 5 rules

Each rule is a plain Java class reading its limits from `rules-v1.yaml`. R1-R3 mirror the standard
CREFC watchlist; R4-R5 are this project's early-warning additions.

| ID | Name | Fires when | Type | Why it exists |
| --- | --- | --- | --- | --- |
| R1 | Late payments | 2 or more payments are late in the current month | Credit | Standard watchlist signal |
| R2 | Low DSCR | DSCR is below 1.10 | Credit | Standard watchlist signal |
| R3 | Maturity soon | Maturity is within 3 months | Credit | Standard watchlist signal (spec's "90 days" approximated as 3 months, since data is monthly) |
| R4 | DSCR falling | DSCR fell 0.15 or more over the trailing 6 months | Early warning | Catches a loan trending toward trouble before it crosses R2's fixed line |
| R5 | Low debt yield near maturity | Debt yield is below 8% **and** maturity is within 18 months | Early warning | Debt yield separates loans that refinance from loans that fail; this is the gap the standard watchlist misses |

R1-R3 fire on an absolute threshold crossed *this month*. R4-R5 look at trend/context (a 6-month
delta, or debt yield combined with how soon the loan matures) — that's the actual "early warning"
part of the product.

## Reading the alert table

Each alert shown in the queue or on a loan's detail page carries:

- **Fired Month** — the most recent month (`YYYY-MM`) in which the rule fired. If the rule stops
  firing for 2 consecutive months after that, the alert auto-resolves.
- **Score** — the sum of three parts, shown as separate columns:
  - **Type** — `40` if the firing rule is Credit (R1/R2/R3), `25` if it's Early Warning (R4/R5).
    Credit rules score higher because they represent an already-crossed line, not just a trend.
  - **Time** — scaled by months to maturity at the time of firing: `30` if ≤ 6 months, `20` if
    ≤ 12 months, `10` if ≤ 18 months, `0` otherwise. A loan closer to maturity is more urgent.
  - **Size** — `0` to `20`, scaled by the loan's original balance against the largest loan in the
    portfolio. A bigger loan going bad matters more.
  - The displayed **Score** is Type + Time + Size, and is what the queue is ranked by (highest
    first).
- **Rule Version** — which version of `rules-*.yaml` fired this alert, so a limit change later
  doesn't retroactively change what an old alert says it was judged against.
- **Limit** / **Inputs** — the exact threshold and the exact input values (e.g. the DSCR value, the
  months-to-maturity) that were compared, for full auditability.

## Running it

```bash
docker compose up -d
mvn spring-boot:run -Dspring-boot.run.profiles=generator   # writes loans + 24 months of CSVs into inbox/
mvn spring-boot:run                                        # starts the app; poller picks up inbox/ every 10s
```

Then open `http://localhost:8080/` for the alert queue, or `http://localhost:8080/loans/L001/detail`
for a loan's chart and alert history.

## Frontend (Next.js)

An alternative UI to the Thymeleaf screens, built in Next.js (App Router, TypeScript, Tailwind) at
`/frontend`. It reads and writes through the same REST API as Thymeleaf — no separate backend, no
CORS, no new API contract. Server Components fetch from the Spring Boot app server-side; the
Acknowledge button is a Server Action.

```bash
cd frontend
npm install
npm run dev
```

Then open `http://localhost:3000/` for the alert queue, or `http://localhost:3000/loans/L001` for a
loan's chart and alert history. The Spring Boot app (`./run.sh` from the repo root) must be running
first — this frontend has no data of its own.

Configure the API base URL via `frontend/.env.local` (copy `.env.local.example`); defaults to
`http://localhost:8080`.

## Adding more synthetic data

All synthetic data comes from `LoanDataGenerator` (`src/main/java/com/cre/earlywarning/generator/`),
run at startup by `GeneratorRunner` when the app is started with `-Dspring-boot.run.profiles=generator`.
It seeds the `loan` table and writes one CSV per month into `inbox/` (`app.generator.output-dir` in
`application.yml`), which the running app's own poller then ingests within ~10 seconds.

- **Get a different "normal" dataset with the same mechanics** — change `app.generator.seed` in
  `src/main/resources/application.yml` (default `42`), or pass `--reseed` to `run.sh`. The 3 planted
  scenarios (`L001`/`L002`/`L003`) are scripted and don't change; only the 97 random-walk loans do.
- **Add more loans** — raise `LOAN_COUNT` in `LoanDataGenerator.java` (currently `100`). New loans
  get IDs `L004`+ through the random-walk path; no other change needed.
- **Add more months** — raise `MONTH_COUNT` (currently `24`). The 3 scripted scenarios only define
  behavior through month 23 (slow slide flattens at month 11, missed payments clears by month 9,
  weak refinance is constant throughout) — extending `MONTH_COUNT` reuses their last-defined value
  for any months beyond what each scenario method explicitly sets, so check `slowSlideScenario()` /
  `weakRefinanceScenario()` / `missedPaymentsScenario()` if you want the extra months to do something
  new rather than hold flat.
- **Add a new planted scenario** — follow the pattern of `slowSlideScenario()`: write a method
  returning `List<CsvRow>` for a fixed loan ID with a scripted (non-random) trajectory, register the
  loan via `scriptedLoan(...)`, and add both to the two `loans.add(...)` / `rowsByLoan.put(...)` calls
  near the top of `generate()`. Keep it deterministic (no `Random` calls) so it's reproducible
  regardless of seed.
- **Rerun without changing the generator** — after any code change, `./run.sh --reseed` wipes the
  previous run's state (drop the `inbox`/`processing`/`done`/`failed` folders and the MySQL volume
  first if you want a fully clean slate: `docker compose down -v`).
- **Hand-craft a one-off CSV instead** — drop a file into `inbox/` matching the existing format
  (header `loan_id,month,balance,noi,yearly_payments,payments_late`, one row per loan per month,
  `month` as `YYYY-MM`) for a loan ID that already exists in the `loan` table; the poller will pick it
  up on its next 10-second cycle like any generated file.

## What would change or build next

1. Test rules R4/R5 on real SEC loan data (the synthetic data proves the mechanics, not the limits).
2. Add a rule for large tenants whose leases end soon.
3. Add a broker view for loans with a refinance gap.
4. Add Kafka as a second intake path calling the same `IngestService.ingest(row)`; later, move the
   event log itself onto a Kafka topic.
5. Add escalation for alerts that go unacknowledged.
