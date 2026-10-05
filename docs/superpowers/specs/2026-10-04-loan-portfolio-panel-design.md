# Loan Portfolio Panel — Design

Date: 2026-10-04
Status: Approved

Adds broker visibility into the full loan portfolio on the frontend home page, independent of
whether any alerts are currently open. Problem: when the alert queue is empty, a broker currently
has zero visibility into the loan book at all — there's no way to see what loans exist without
guessing a loan ID for the `/loans/[id]` page. This adds a permanent, always-visible panel listing
every loan with its latest and historical DSCR/debt-yield trend.

## 1. Backend Change

One new additive endpoint — no changes to `GET /alerts`, `GET /loans/{id}`, or
`POST /alerts/{id}/acknowledge`.

- **`GET /loans`** → `List<LoanSummaryDto>`. Returns every loan in the portfolio with its static
  info plus latest and historical DSCR/debt yield. Coexists cleanly with the existing
  `GET /loans/{id}` mapping (distinct path shapes, no routing ambiguity) in the same
  `@RequestMapping("/loans")` controller.

- **New `LoanSummaryDto`** (`src/main/java/com/cre/earlywarning/api/LoanSummaryDto.java`):
  ```java
  public record LoanSummaryDto(
      String loanId, String propertyType, BigDecimal originalBalance, BigDecimal rate,
      String maturityDate, BigDecimal latestDscr, BigDecimal latestDebtYield,
      List<LoanHistoryPointDto> history
  ) {
  }
  ```
  Reuses the existing `LoanHistoryPointDto` record as-is.

- **`LoanQueryService` gets a new method**: `getAllLoanSummaries(): List<LoanSummaryDto>`. For each
  loan returned by `LoanRepository.findAll()`, computes its event history via the same
  `eventRepository.findByLoanIdOrderByMonthAsc` + `metricsCalculator.calculate` pattern
  `getLoanDetail` already uses, takes the last history point as "latest," and omits the alerts
  lookup entirely (this view doesn't need it). This is a per-loan query loop over ~100 loans — not
  a single optimized bulk query — consistent with this codebase's existing preference for
  simplicity over premature optimization at this data scale.

- **`LoanController`** gets one new handler:
  ```java
  @GetMapping
  public List<LoanSummaryDto> list() {
      return loanQueryService.getAllLoanSummaries();
  }
  ```

- **Testing**: one new `LoanControllerTest` case asserting `GET /loans` returns all seeded loans
  with non-null `latestDscr`/`latestDebtYield`/`history`. No changes to any existing test.

## 2. Frontend Change

- **`lib/api.ts`**: add `LoanSummaryDto` type (mirroring the backend record) and
  `getLoans(): Promise<LoanSummaryDto[]>` calling `GET /loans`, with the same `cache: "no-store"`
  convention as every other fetch in this file.

- **New `components/Sparkline.tsx`** — `"use client"`, wraps `react-chartjs-2`'s `Line` chart:
  animations disabled, no axes/legend/tooltip/grid, small fixed canvas size (e.g. 80×30px). Props:
  `{ data: number[] }` (a single series — DSCR values from `history`). Reuses the `chart.js`
  dependency already installed for the loan-detail page's chart; no new library.

- **New `components/LoanPortfolioTable.tsx`** — a Server Component (receives already-fetched
  `LoanSummaryDto[]` as a prop, does no fetching itself — same pattern as `AlertTable`). Renders a
  table: Loan ID (links to `/loans/{id}`), Property Type, Original Balance, Rate, Maturity Date,
  Latest DSCR, Latest Debt Yield, and a `Sparkline` column fed by each loan's `history.map(h =>
  h.dscr)`.

- **`app/page.tsx`**: fetches `getLoans()` alongside the existing `getAlerts()` call (both awaited
  in the same Server Component), renders `LoanPortfolioTable` below the existing `AlertTable` —
  always visible, not conditional on the alert queue being empty.

- **No pagination**: all ~100 rows (and their ~100 live `Sparkline` chart instances) render at once,
  per explicit choice. If this becomes a real perf problem later, pagination or a lighter
  sparkline renderer is the fallback — not addressed now.

## 3. Error Handling, Testing, Running

**Error handling**: `getLoans()` failing is caught by the existing root `error.tsx` boundary — no
new error-handling code needed. An empty portfolio renders a simple "No loans" message in
`LoanPortfolioTable`, mirroring `AlertTable`'s existing empty-state pattern.

**Testing**: one new backend test (`LoanControllerTest`) as described above. No automated frontend
tests, consistent with this project's standing decision to skip UI test automation.

**Running it**: unchanged — `./run.sh` for the backend, `cd frontend && npm run dev` for the
frontend. No new env vars, no new ports, no CORS configuration (same server-to-server fetch pattern
as every other call in this app).

## Out of scope

Pagination/virtualization of the loan table, a lighter-weight sparkline alternative, sorting/
filtering the portfolio table, bulk data endpoint optimization (N+1 query acceptance is deliberate
at this data scale).
