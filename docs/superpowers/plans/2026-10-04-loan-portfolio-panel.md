# Loan Portfolio Panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give brokers visibility into the full loan portfolio on the frontend home page, independent of whether the alert queue is empty, via a new `GET /loans` endpoint and an always-visible table with per-loan DSCR sparklines.

**Architecture:** One new additive Spring Boot endpoint (`GET /loans`) reusing the existing `LoanQueryService`/`MetricsCalculator` pattern. One new frontend API client function, two new components (a Server Component table, a Client Component sparkline wrapping `react-chartjs-2`), wired into the existing home page alongside the alert queue.

**Tech Stack:** Java 21/Spring Boot 3 (backend, unchanged dependencies), Next.js/TypeScript/Tailwind/react-chartjs-2 (frontend, unchanged dependencies — no new libraries either side).

## Global Constraints

- Purely additive — no changes to `GET /alerts`, `GET /loans/{id}`, `POST /alerts/{id}/acknowledge`, or any existing Thymeleaf/Next.js page. (design doc)
- No CORS configuration — the new frontend fetch is server-side, same as every existing call. (design doc section 2)
- No pagination — all ~100 loan rows and their sparkline chart instances render at once, by explicit choice. (design doc section 2)
- No automated frontend tests, consistent with this project's standing decision. One new backend test is expected. (design doc section 3)
- N+1 query pattern (one event-history query per loan in the new endpoint) is a deliberate simplicity choice at this data scale, not an oversight. (design doc section 1)

---

## Task 1: Backend — `GET /loans` Endpoint

**Files:**
- Create: `src/main/java/com/cre/earlywarning/api/LoanSummaryDto.java`
- Modify: `src/main/java/com/cre/earlywarning/api/LoanQueryService.java` (add `getAllLoanSummaries()`)
- Modify: `src/main/java/com/cre/earlywarning/api/LoanController.java` (add `GET /loans` handler)
- Modify: `src/test/java/com/cre/earlywarning/api/LoanControllerTest.java` (add one test)

**Interfaces:**
- Consumes: `LoanRepository.findAll()` (already exists via `JpaRepository`), `LoanMonthEventRepository.findByLoanIdOrderByMonthAsc`, `MetricsCalculator.calculate`, `LoanHistoryPointDto` — all pre-existing, used exactly as `getLoanDetail` already uses them.
- Produces: `GET /loans` → `List<LoanSummaryDto>`. `LoanSummaryDto` is consumed by Task 2's frontend `LoanSummaryDto` TypeScript type (field-for-field match required).

- [ ] **Step 1: Write the failing test — add to `src/test/java/com/cre/earlywarning/api/LoanControllerTest.java`**

Add this test method inside the existing `LoanControllerTest` class (alongside the two existing tests, same imports already present in the file apply):

```java
@Test
void listReturnsAllLoansWithLatestAndHistoricalMetrics() throws Exception {
    loanRepository.save(new Loan("L700", "OFFICE", new BigDecimal("8000000.00"),
        new BigDecimal("0.0600"), LocalDate.of(2030, 1, 1), new BigDecimal("600000.00")));
    ingestService.ingest(new CsvRow("L700", YearMonth.of(2024, 1), new BigDecimal("8000000.00"),
        new BigDecimal("550000.00"), new BigDecimal("500000.00"), 0));
    ingestService.ingest(new CsvRow("L700", YearMonth.of(2024, 2), new BigDecimal("8000000.00"),
        new BigDecimal("600000.00"), new BigDecimal("500000.00"), 0));

    mockMvc.perform(get("/loans"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()", is(1)))
        .andExpect(jsonPath("$[0].loanId", is("L700")))
        .andExpect(jsonPath("$[0].history.length()", is(2)))
        .andExpect(jsonPath("$[0].latestDscr").exists())
        .andExpect(jsonPath("$[0].latestDebtYield").exists());
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `mvn -q test -Dtest=LoanControllerTest`
Expected: COMPILATION ERROR or 404 — the `GET /loans` endpoint and `LoanSummaryDto` don't exist yet.

- [ ] **Step 3: Write `src/main/java/com/cre/earlywarning/api/LoanSummaryDto.java`**

```java
package com.cre.earlywarning.api;

import java.math.BigDecimal;
import java.util.List;

public record LoanSummaryDto(
    String loanId, String propertyType, BigDecimal originalBalance, BigDecimal rate,
    String maturityDate, BigDecimal latestDscr, BigDecimal latestDebtYield,
    List<LoanHistoryPointDto> history
) {
}
```

- [ ] **Step 4: Add `getAllLoanSummaries()` to `src/main/java/com/cre/earlywarning/api/LoanQueryService.java`**

Add this method to the existing `LoanQueryService` class (the class already has `loanRepository`,
`eventRepository`, `metricsCalculator` as fields from the existing `getLoanDetail` method — reuse
them, don't re-inject):

```java
public java.util.List<LoanSummaryDto> getAllLoanSummaries() {
    return loanRepository.findAll().stream()
        .map(this::toSummary)
        .toList();
}

private LoanSummaryDto toSummary(Loan loan) {
    var history = eventRepository.findByLoanIdOrderByMonthAsc(loan.getLoanId()).stream()
        .map(e -> metricsCalculator.calculate(loan, e))
        .map(m -> new LoanHistoryPointDto(m.month().toString(), m.dscr(), m.debtYield()))
        .toList();

    BigDecimal latestDscr = history.isEmpty() ? null : history.get(history.size() - 1).dscr();
    BigDecimal latestDebtYield = history.isEmpty() ? null : history.get(history.size() - 1).debtYield();

    return new LoanSummaryDto(loan.getLoanId(), loan.getPropertyType(), loan.getOriginalBalance(),
        loan.getRate(), loan.getMaturityDate().toString(), latestDscr, latestDebtYield, history);
}
```

Add the import `import java.math.BigDecimal;` at the top of the file if it's not already present
(check first — `getLoanDetail` may already import it via other usages).

- [ ] **Step 5: Add the `GET /loans` handler to `src/main/java/com/cre/earlywarning/api/LoanController.java`**

Add this method to the existing `LoanController` class, above or below the existing `detail` method:

```java
@GetMapping
public java.util.List<LoanSummaryDto> list() {
    return loanQueryService.getAllLoanSummaries();
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn -q test -Dtest=LoanControllerTest`
Expected: PASS (3 tests: the 2 existing plus the new one)

- [ ] **Step 7: Run the full backend suite to confirm nothing else broke**

Run: `mvn -q test`
Expected: all tests pass (59+1=60 tests), BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/cre/earlywarning/api/LoanSummaryDto.java src/main/java/com/cre/earlywarning/api/LoanQueryService.java src/main/java/com/cre/earlywarning/api/LoanController.java src/test/java/com/cre/earlywarning/api/LoanControllerTest.java
git commit -m "feat: add GET /loans endpoint listing all loans with latest and historical metrics"
```

---

## Task 2: Frontend API Client

**Files:**
- Modify: `frontend/lib/api.ts` (add `LoanSummaryDto` type and `getLoans()`)

**Interfaces:**
- Consumes: the `GET /loans` endpoint from Task 1 (field names must match `LoanSummaryDto` exactly).
- Produces: `LoanSummaryDto` TypeScript type and `getLoans(): Promise<LoanSummaryDto[]>` — Task 3's `LoanPortfolioTable` and Task 4's `page.tsx` import both.

- [ ] **Step 1: Add to `frontend/lib/api.ts`**

Add this type and function to the existing file (alongside the existing `AlertDto`, `LoanHistoryPointDto`, `LoanDetailDto` types and `getAlerts`/`getLoan`/`acknowledgeAlert` functions — same file, same conventions):

```typescript
export type LoanSummaryDto = {
  loanId: string;
  propertyType: string;
  originalBalance: number;
  rate: number;
  maturityDate: string;
  latestDscr: number | null;
  latestDebtYield: number | null;
  history: LoanHistoryPointDto[];
};

export async function getLoans(): Promise<LoanSummaryDto[]> {
  const res = await fetch(`${API_BASE_URL}/loans`, { cache: "no-store" });
  if (!res.ok) {
    throw new Error(`Failed to fetch loans: ${res.status}`);
  }
  return res.json();
}
```

- [ ] **Step 2: Type-check**

```bash
export PATH="/usr/local/opt/node@24/bin:$PATH"
cd frontend
npm run build
```

Expected: builds successfully (nothing imports `getLoans` yet, so this just confirms `lib/api.ts` itself has no type errors).

- [ ] **Step 3: Commit**

```bash
git add frontend/lib/api.ts
git commit -m "feat: add getLoans API client function"
```

---

## Task 3: Sparkline and LoanPortfolioTable Components

**Files:**
- Create: `frontend/components/Sparkline.tsx`
- Create: `frontend/components/LoanPortfolioTable.tsx`

**Interfaces:**
- Consumes: `LoanSummaryDto` from `frontend/lib/api.ts` (Task 2).
- Produces: `Sparkline` (props: `{ data: number[] }`) and `LoanPortfolioTable` (props: `{ loans: LoanSummaryDto[] }`) — Task 4's `page.tsx` renders `LoanPortfolioTable` directly.

- [ ] **Step 1: Write `frontend/components/Sparkline.tsx`**

```tsx
"use client";

import {
  Chart as ChartJS,
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
} from "chart.js";
import { Line } from "react-chartjs-2";

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement);

export function Sparkline({ data }: { data: number[] }) {
  const chartData = {
    labels: data.map((_, i) => i),
    datasets: [
      {
        data,
        borderColor: "rgb(37, 99, 235)",
        borderWidth: 1,
        pointRadius: 0,
        tension: 0.2,
      },
    ],
  };

  const options = {
    responsive: false,
    animation: false as const,
    plugins: { legend: { display: false }, tooltip: { enabled: false } },
    scales: {
      x: { display: false },
      y: { display: false },
    },
  };

  return <Line data={chartData} options={options} width={80} height={30} />;
}
```

- [ ] **Step 2: Write `frontend/components/LoanPortfolioTable.tsx`**

```tsx
import Link from "next/link";
import { LoanSummaryDto } from "@/lib/api";
import { Sparkline } from "./Sparkline";

export function LoanPortfolioTable({ loans }: { loans: LoanSummaryDto[] }) {
  if (loans.length === 0) {
    return <p className="text-gray-500">No loans.</p>;
  }

  return (
    <table className="w-full border-collapse text-left text-sm">
      <thead>
        <tr className="border-b border-gray-300">
          <th className="p-2">Loan</th>
          <th className="p-2">Property Type</th>
          <th className="p-2">Original Balance</th>
          <th className="p-2">Rate</th>
          <th className="p-2">Maturity</th>
          <th className="p-2">Latest DSCR</th>
          <th className="p-2">Latest Debt Yield</th>
          <th className="p-2">Trend</th>
        </tr>
      </thead>
      <tbody>
        {loans.map((loan) => (
          <tr key={loan.loanId} className="border-b border-gray-100">
            <td className="p-2">
              <Link className="text-blue-600 hover:underline" href={`/loans/${loan.loanId}`}>
                {loan.loanId}
              </Link>
            </td>
            <td className="p-2">{loan.propertyType}</td>
            <td className="p-2">
              {loan.originalBalance.toLocaleString(undefined, { style: "currency", currency: "USD" })}
            </td>
            <td className="p-2">{(loan.rate * 100).toFixed(2)}%</td>
            <td className="p-2">{loan.maturityDate}</td>
            <td className="p-2">{loan.latestDscr ?? "—"}</td>
            <td className="p-2">{loan.latestDebtYield ?? "—"}</td>
            <td className="p-2">
              <Sparkline data={loan.history.map((h) => h.dscr)} />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
```

- [ ] **Step 3: Build to confirm both components compile**

```bash
export PATH="/usr/local/opt/node@24/bin:$PATH"
cd frontend
npm run build
```

Expected: builds successfully. Neither component is rendered anywhere yet (that's Task 4), so this
only confirms they type-check in isolation.

- [ ] **Step 4: Commit**

```bash
git add frontend/components/Sparkline.tsx frontend/components/LoanPortfolioTable.tsx
git commit -m "feat: add Sparkline and LoanPortfolioTable components"
```

---

## Task 4: Wire Into Home Page and Verify

**Files:**
- Modify: `frontend/app/page.tsx`

**Interfaces:**
- Consumes: `getLoans` (Task 2), `LoanPortfolioTable` (Task 3).
- Produces: the completed home page. No later task depends on this.

- [ ] **Step 1: Modify `frontend/app/page.tsx`**

Replace the existing file's content with:

```tsx
import { getAlerts, getLoans } from "@/lib/api";
import { AlertTable } from "@/components/AlertTable";
import { LoanPortfolioTable } from "@/components/LoanPortfolioTable";

export const dynamic = "force-dynamic";

export default async function QueuePage() {
  const [alerts, loans] = await Promise.all([getAlerts(), getLoans()]);

  return (
    <main className="mx-auto max-w-5xl p-6">
      <h1 className="mb-4 text-2xl font-bold">Open Alerts</h1>
      <AlertTable alerts={alerts} />

      <h2 className="mb-4 mt-8 text-2xl font-bold">Loan Portfolio</h2>
      <LoanPortfolioTable loans={loans} />
    </main>
  );
}
```

- [ ] **Step 2: Build**

```bash
export PATH="/usr/local/opt/node@24/bin:$PATH"
cd frontend
npm run build
```

Expected: builds successfully.

- [ ] **Step 3: Manual verification against the real backend**

Start the backend (`./run.sh` from the repo root, or `mvn spring-boot:run` directly if port 3306 is
already held by a pre-existing container — check `docker ps` first; wait for readiness by polling
`curl http://localhost:8080/alerts` until 200, don't blind-sleep). Start the frontend
(`cd frontend && npm run dev`, wait for port 3000 similarly).

```bash
curl -s http://localhost:3000 | grep -o "Loan Portfolio"
curl -s http://localhost:3000 | grep -o "L0[0-9][0-9]" | sort -u | wc -l
curl -s http://localhost:8080/loans | grep -o '"loanId"' | wc -l
```

Expected: first command prints `Loan Portfolio` (the new section heading rendered); second prints a
number ≥ 3 (at least the 3 planted-scenario loans show up as distinct loan ID links on the page,
though if the backend has been fully seeded with all 100 loans this will be higher — either is
fine, just confirm it's not 0 or 1); third command's count should match the number of loans
currently seeded in the backend (compare against the count you'd expect given whatever seeding
state the backend is in).

Remember when counting occurrences in curl'd HTML: use `grep -o "pattern" | wc -l`, not `grep -c`
(Next.js SSR output can be a single unbroken line, and `grep -c` counts matching lines, not
occurrences — it will undercount).

Stop both processes when done (`docker compose down` if you want to fully tear down the backend).

- [ ] **Step 4: Commit**

```bash
git add frontend/app/page.tsx
git commit -m "feat: add always-visible loan portfolio panel to the home page"
```
