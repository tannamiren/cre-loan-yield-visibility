# Next.js Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Next.js app at `/frontend` that replaces the Thymeleaf screens as the UI you actually use, reading and acknowledging alerts against the existing, unmodified Spring Boot REST API.

**Architecture:** Next.js 15 App Router, TypeScript, Tailwind CSS. Server Components fetch directly from the Spring Boot API (server-to-server, no CORS). The Acknowledge button is a Server Action. One Client Component for the Chart.js chart (needs a browser canvas), one for the Acknowledge button (needs an onClick).

**Tech Stack:** Next.js 15, TypeScript, Tailwind CSS, npm, `react-chartjs-2` + `chart.js`.

## Global Constraints

- The Spring Boot app's REST API, Thymeleaf templates, and controllers are NOT modified by this plan — this is purely additive. (design doc section 1)
- No CORS configuration anywhere — all calls to the Spring Boot API happen server-side (Server Components, Server Actions), never from the browser. (design doc section 1)
- No automated tests for this frontend — explicitly out of scope by request. Verification is manual. (design doc section 3)
- No client-side state library, no data-fetching library (React Query etc.), no routing library beyond Next.js's own App Router. (design doc section 2)
- `run.sh` is not modified — it continues to start only the Spring Boot app. The frontend has its own separate `npm run dev`. (design doc section 3)

## API Contract (from the existing Spring Boot app — do not change, just match)

```
GET http://localhost:8080/alerts
  -> AlertDto[]

GET http://localhost:8080/loans/{id}
  -> LoanDetailDto
  -> 404 (plain text body) if the loan id doesn't exist

POST http://localhost:8080/alerts/{id}/acknowledge
  -> 200, empty body
```

```typescript
type AlertDto = {
  id: number;
  loanId: string;
  ruleId: string;
  state: "OPEN" | "ACKNOWLEDGED" | "RESOLVED";
  score: number;
  scoreType: number;
  scoreTime: number;
  scoreSize: number;
  ruleVersion: number;
  firedMonth: string;        // "YYYY-MM"
  limitValue: string;
  inputs: Record<string, string>;
};

type LoanHistoryPointDto = {
  month: string;             // "YYYY-MM"
  dscr: number;
  debtYield: number;
};

type LoanDetailDto = {
  loanId: string;
  propertyType: string;
  originalBalance: number;
  rate: number;
  maturityDate: string;      // "YYYY-MM-DD"
  history: LoanHistoryPointDto[];
  alerts: AlertDto[];
};
```

Note: `GET /alerts` only returns OPEN alerts (ranked by score, per the existing `AlertService.openQueue()`), so every alert on the queue page will have `state === "OPEN"`. `GET /loans/{id}` returns ALL of a loan's alerts regardless of state, so the detail page can show ACKNOWLEDGED/RESOLVED ones too.

---

## Task 1: Scaffold the Next.js Project

**Files:**
- Create: `frontend/` (entire project, via `create-next-app`)
- Create: `frontend/.env.local.example`
- Modify: root `.gitignore` (add `frontend/node_modules`, `frontend/.next`, `frontend/.env.local`)

**Interfaces:**
- Produces: a bootable Next.js dev server on port 3000, with TypeScript, Tailwind, and the App Router configured. No later task depends on internals beyond "the project exists and `npm run dev` works."

- [ ] **Step 1: Scaffold with create-next-app**

From the repo root:

```bash
npx create-next-app@latest frontend \
  --typescript --eslint --tailwind --app \
  --src-dir=false --import-alias "@/*" --use-npm --yes
```

If your installed `create-next-app` version prompts interactively instead of honoring these flags, answer: TypeScript=Yes, ESLint=Yes, Tailwind=Yes, App Router=Yes, `src/` directory=No, import alias=Yes (`@/*`), Turbopack=whatever the tool defaults to (not load-bearing for this project).

- [ ] **Step 2: Install the chart dependency**

```bash
cd frontend
npm install chart.js react-chartjs-2
```

- [ ] **Step 3: Write `frontend/.env.local.example`**

```
API_BASE_URL=http://localhost:8080
```

- [ ] **Step 4: Create your own `frontend/.env.local` (gitignored) with the same content**

```bash
cp frontend/.env.local.example frontend/.env.local
```

- [ ] **Step 5: Update the root `.gitignore`**

Add these lines (append, don't remove anything existing):

```
frontend/node_modules/
frontend/.next/
frontend/.env.local
```

- [ ] **Step 6: Verify the scaffold boots**

```bash
cd frontend
npm run dev &
sleep 5
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:3000
kill %1
```

Expected: prints `200` (the default Next.js starter page). This is just proving the scaffold works before anything else is built — the default page content will be replaced entirely in Task 3.

- [ ] **Step 7: Commit**

```bash
cd /path/to/repo/root
git add frontend .gitignore
git commit -m "chore: scaffold Next.js frontend project"
```

---

## Task 2: API Client

**Files:**
- Create: `frontend/lib/api.ts`

**Interfaces:**
- Produces: `AlertDto`, `LoanHistoryPointDto`, `LoanDetailDto` TypeScript types (exact shapes in the plan header above), and three functions: `getAlerts(): Promise<AlertDto[]>`, `getLoan(id: string): Promise<LoanDetailDto | null>` (returns `null` on a 404, throws on any other non-OK status), `acknowledgeAlert(id: number): Promise<void>`. Every later task that touches the API imports from this file — no task fetches directly.

- [ ] **Step 1: Write `frontend/lib/api.ts`**

```typescript
export type AlertDto = {
  id: number;
  loanId: string;
  ruleId: string;
  state: "OPEN" | "ACKNOWLEDGED" | "RESOLVED";
  score: number;
  scoreType: number;
  scoreTime: number;
  scoreSize: number;
  ruleVersion: number;
  firedMonth: string;
  limitValue: string;
  inputs: Record<string, string>;
};

export type LoanHistoryPointDto = {
  month: string;
  dscr: number;
  debtYield: number;
};

export type LoanDetailDto = {
  loanId: string;
  propertyType: string;
  originalBalance: number;
  rate: number;
  maturityDate: string;
  history: LoanHistoryPointDto[];
  alerts: AlertDto[];
};

const API_BASE_URL = process.env.API_BASE_URL ?? "http://localhost:8080";

export async function getAlerts(): Promise<AlertDto[]> {
  const res = await fetch(`${API_BASE_URL}/alerts`, { cache: "no-store" });
  if (!res.ok) {
    throw new Error(`Failed to fetch alerts: ${res.status}`);
  }
  return res.json();
}

export async function getLoan(id: string): Promise<LoanDetailDto | null> {
  const res = await fetch(`${API_BASE_URL}/loans/${id}`, { cache: "no-store" });
  if (res.status === 404) {
    return null;
  }
  if (!res.ok) {
    throw new Error(`Failed to fetch loan ${id}: ${res.status}`);
  }
  return res.json();
}

export async function acknowledgeAlert(id: number): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/alerts/${id}/acknowledge`, { method: "POST" });
  if (!res.ok) {
    throw new Error(`Failed to acknowledge alert ${id}: ${res.status}`);
  }
}
```

`cache: "no-store"` is important here: alert/loan data changes as the backend's poller ingests new
files, and this app has no need for Next.js's default request caching — every page load should show
current data.

- [ ] **Step 2: Type-check**

```bash
cd frontend
npm run build
```

Expected: builds successfully (the default starter page doesn't use `api.ts` yet, so this just
proves `api.ts` itself has no type errors). Then delete the `.next` build output isn't necessary —
`next build` is safe to run repeatedly.

- [ ] **Step 3: Commit**

```bash
git add frontend/lib/api.ts
git commit -m "feat: add typed API client for the Spring Boot backend"
```

---

## Task 3: Queue Page

**Files:**
- Create: `frontend/components/ScoreBreakdown.tsx`
- Create: `frontend/components/AlertTable.tsx`
- Modify: `frontend/app/page.tsx` (replace the create-next-app starter content entirely)

**Interfaces:**
- Consumes: `getAlerts`, `AlertDto` from `frontend/lib/api.ts` (Task 2).
- Produces: `ScoreBreakdown` (props: `{ scoreType: number; scoreTime: number; scoreSize: number }`, renders the three components as small labeled badges) and `AlertTable` (props: `{ alerts: AlertDto[] }`, renders the shared table markup) — Task 5's loan detail page reuses both of these as-is.

- [ ] **Step 1: Write `frontend/components/ScoreBreakdown.tsx`**

```tsx
type ScoreBreakdownProps = {
  scoreType: number;
  scoreTime: number;
  scoreSize: number;
};

export function ScoreBreakdown({ scoreType, scoreTime, scoreSize }: ScoreBreakdownProps) {
  return (
    <div className="flex gap-1 text-xs text-gray-600">
      <span className="rounded bg-gray-100 px-1.5 py-0.5" title="Type score">T{scoreType}</span>
      <span className="rounded bg-gray-100 px-1.5 py-0.5" title="Time-to-maturity score">M{scoreTime}</span>
      <span className="rounded bg-gray-100 px-1.5 py-0.5" title="Loan-size score">S{scoreSize}</span>
    </div>
  );
}
```

- [ ] **Step 2: Write `frontend/components/AlertTable.tsx`**

```tsx
import Link from "next/link";
import { AlertDto } from "@/lib/api";
import { ScoreBreakdown } from "./ScoreBreakdown";
import { AcknowledgeButton } from "./AcknowledgeButton";

type AlertTableProps = {
  alerts: AlertDto[];
  showLoanLink?: boolean;
  showAcknowledge?: boolean;
  showAuditDetail?: boolean;
};

export function AlertTable({
  alerts,
  showLoanLink = true,
  showAcknowledge = true,
  showAuditDetail = false,
}: AlertTableProps) {
  if (alerts.length === 0) {
    return <p className="text-gray-500">No alerts.</p>;
  }

  return (
    <table className="w-full border-collapse text-left text-sm">
      <thead>
        <tr className="border-b border-gray-300">
          {showLoanLink && <th className="p-2">Loan</th>}
          <th className="p-2">Rule</th>
          <th className="p-2">State</th>
          <th className="p-2">Score</th>
          <th className="p-2">Breakdown</th>
          <th className="p-2">Fired Month</th>
          <th className="p-2">Rule Version</th>
          {showAuditDetail && <th className="p-2">Limit</th>}
          {showAuditDetail && <th className="p-2">Inputs</th>}
          {showAcknowledge && <th className="p-2"></th>}
        </tr>
      </thead>
      <tbody>
        {alerts.map((alert) => (
          <tr key={alert.id} className="border-b border-gray-100">
            {showLoanLink && (
              <td className="p-2">
                <Link className="text-blue-600 hover:underline" href={`/loans/${alert.loanId}`}>
                  {alert.loanId}
                </Link>
              </td>
            )}
            <td className="p-2">{alert.ruleId}</td>
            <td className="p-2">{alert.state}</td>
            <td className="p-2 font-semibold">{alert.score}</td>
            <td className="p-2">
              <ScoreBreakdown
                scoreType={alert.scoreType}
                scoreTime={alert.scoreTime}
                scoreSize={alert.scoreSize}
              />
            </td>
            <td className="p-2">{alert.firedMonth}</td>
            <td className="p-2">{alert.ruleVersion}</td>
            {showAuditDetail && <td className="p-2">{alert.limitValue}</td>}
            {showAuditDetail && (
              <td className="p-2 font-mono text-xs">{JSON.stringify(alert.inputs)}</td>
            )}
            {showAcknowledge && alert.state === "OPEN" && (
              <td className="p-2">
                <AcknowledgeButton alertId={alert.id} />
              </td>
            )}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
```

This imports `AcknowledgeButton`, which doesn't exist yet — that's Task 4. This task's build
verification step will fail until Task 4 is done; that's expected and intentional (the two tasks are
tightly coupled, this is the one place in the plan where a later task's file is referenced first).
If you're executing tasks in order, write a temporary no-op placeholder for `AcknowledgeButton`
isn't necessary — just proceed directly to Task 4 next; don't run `npm run build` at the end of this
task's steps, run it at the end of Task 4 instead.

- [ ] **Step 3: Replace `frontend/app/page.tsx` with the queue page**

```tsx
import { getAlerts } from "@/lib/api";
import { AlertTable } from "@/components/AlertTable";

export const dynamic = "force-dynamic";

export default async function QueuePage() {
  const alerts = await getAlerts();

  return (
    <main className="mx-auto max-w-5xl p-6">
      <h1 className="mb-4 text-2xl font-bold">Open Alerts</h1>
      <AlertTable alerts={alerts} />
    </main>
  );
}
```

`export const dynamic = "force-dynamic"` ensures this page is never statically cached by Next.js's
build-time rendering — it must hit the live API on every request.

- [ ] **Step 4: Commit (after Task 4 is also done, since this task doesn't build cleanly alone)**

Skip this commit step here — Task 4 commits both together. Proceed directly to Task 4.

---

## Task 4: Acknowledge Action

**Files:**
- Create: `frontend/lib/actions.ts`
- Create: `frontend/components/AcknowledgeButton.tsx`

**Interfaces:**
- Consumes: `acknowledgeAlert` from `frontend/lib/api.ts` (Task 2); rendered by `AlertTable` (Task 3), which already references `AcknowledgeButton` by name and prop shape `{ alertId: number }`.
- Produces: `acknowledgeAlertAction(alertId: number): Promise<void>` Server Action and the `AcknowledgeButton` Client Component. No later task depends on these beyond the existing `AlertTable` usage.

- [ ] **Step 1: Write `frontend/lib/actions.ts`**

```typescript
"use server";

import { revalidatePath } from "next/cache";
import { acknowledgeAlert } from "@/lib/api";

export async function acknowledgeAlertAction(alertId: number): Promise<void> {
  await acknowledgeAlert(alertId);
  revalidatePath("/");
  revalidatePath("/loans/[id]", "page");
}
```

- [ ] **Step 2: Write `frontend/components/AcknowledgeButton.tsx`**

```tsx
"use client";

import { useState } from "react";
import { acknowledgeAlertAction } from "@/lib/actions";

export function AcknowledgeButton({ alertId }: { alertId: number }) {
  const [pending, setPending] = useState(false);

  return (
    <button
      type="button"
      disabled={pending}
      onClick={async () => {
        setPending(true);
        await acknowledgeAlertAction(alertId);
        setPending(false);
      }}
      className="rounded bg-gray-800 px-2 py-1 text-xs text-white hover:bg-gray-700 disabled:opacity-50"
    >
      {pending ? "..." : "Acknowledge"}
    </button>
  );
}
```

- [ ] **Step 3: Build to confirm Tasks 3 and 4 compile together**

```bash
cd frontend
npm run build
```

Expected: builds successfully. If `getAlerts()` fails at build time because the Spring Boot API
isn't running, that's expected for `next build`'s static-analysis pass on a `force-dynamic` page —
Next.js should not attempt to call the API at build time for a force-dynamic route. If the build
does try to hit the network and fails, note this in your report; it would mean revisiting whether
`force-dynamic` is sufficient or whether the fetch needs to be wrapped differently, but don't change
the approach without checking with me first.

- [ ] **Step 4: Manually verify end-to-end against the real backend**

```bash
# From the repo root, in one terminal:
./run.sh
# Wait for it to report the app has started, then in another terminal:
cd frontend && npm run dev &
sleep 5
curl -s http://localhost:3000 | grep -o "L00[0-9]" | sort -u
curl -s http://localhost:3000 | grep -c "Acknowledge"
```

Expected: the first `curl`/`grep` prints at least `L001`, `L002`, `L003` (the three planted
scenario loans, assuming the backend has been seeded already — if `done/report-2025-12.csv`
doesn't exist yet, `run.sh` will seed it automatically on this run). The second command prints a
number greater than 0 (at least one Acknowledge button rendered), confirming `AlertTable` and
`AcknowledgeButton` are wired and rendering real data. Stop both processes when done
(`kill %1` for the frontend dev server; `docker compose down` if you want to fully tear down, though
leaving MySQL running is fine for the next task).

- [ ] **Step 5: Commit**

```bash
git add frontend/app/page.tsx frontend/components/ScoreBreakdown.tsx frontend/components/AlertTable.tsx frontend/lib/actions.ts frontend/components/AcknowledgeButton.tsx
git commit -m "feat: add alert queue page with acknowledge action"
```

---

## Task 5: Loan Detail Page

**Files:**
- Create: `frontend/components/DscrChart.tsx`
- Create: `frontend/app/loans/[id]/page.tsx`
- Create: `frontend/app/loans/[id]/not-found.tsx`

**Interfaces:**
- Consumes: `getLoan`, `LoanDetailDto` from `frontend/lib/api.ts` (Task 2); `AlertTable` from `frontend/components/AlertTable.tsx` (Task 3).
- Produces: the `/loans/[id]` route. No later task depends on this.

- [ ] **Step 1: Write `frontend/components/DscrChart.tsx`**

```tsx
"use client";

import {
  Chart as ChartJS,
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  Tooltip,
  Legend,
} from "chart.js";
import { Line } from "react-chartjs-2";
import { LoanHistoryPointDto } from "@/lib/api";

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, Tooltip, Legend);

export function DscrChart({ history }: { history: LoanHistoryPointDto[] }) {
  const data = {
    labels: history.map((h) => h.month),
    datasets: [
      {
        label: "DSCR",
        data: history.map((h) => h.dscr),
        borderColor: "rgb(37, 99, 235)",
        backgroundColor: "rgba(37, 99, 235, 0.2)",
      },
      {
        label: "Debt Yield",
        data: history.map((h) => h.debtYield),
        borderColor: "rgb(220, 38, 38)",
        backgroundColor: "rgba(220, 38, 38, 0.2)",
      },
    ],
  };

  return <Line data={data} />;
}
```

- [ ] **Step 2: Write `frontend/app/loans/[id]/not-found.tsx`**

```tsx
export default function LoanNotFound() {
  return (
    <main className="mx-auto max-w-5xl p-6">
      <h1 className="text-2xl font-bold">Loan not found</h1>
      <p className="mt-2 text-gray-600">No loan exists with that ID.</p>
    </main>
  );
}
```

- [ ] **Step 3: Write `frontend/app/loans/[id]/page.tsx`**

```tsx
import { notFound } from "next/navigation";
import { getLoan } from "@/lib/api";
import { AlertTable } from "@/components/AlertTable";
import { DscrChart } from "@/components/DscrChart";

export const dynamic = "force-dynamic";

export default async function LoanDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const loan = await getLoan(id);

  if (!loan) {
    notFound();
  }

  return (
    <main className="mx-auto max-w-5xl p-6">
      <h1 className="text-2xl font-bold">Loan {loan.loanId}</h1>
      <dl className="mt-2 grid grid-cols-2 gap-1 text-sm text-gray-700 sm:grid-cols-4">
        <dt className="font-semibold">Property type</dt>
        <dd>{loan.propertyType}</dd>
        <dt className="font-semibold">Original balance</dt>
        <dd>{loan.originalBalance.toLocaleString(undefined, { style: "currency", currency: "USD" })}</dd>
        <dt className="font-semibold">Rate</dt>
        <dd>{(loan.rate * 100).toFixed(2)}%</dd>
        <dt className="font-semibold">Maturity</dt>
        <dd>{loan.maturityDate}</dd>
      </dl>

      <h2 className="mb-2 mt-6 text-lg font-semibold">DSCR &amp; Debt Yield</h2>
      <DscrChart history={loan.history} />

      <h2 className="mb-2 mt-6 text-lg font-semibold">Alerts</h2>
      <AlertTable alerts={loan.alerts} showLoanLink={false} showAuditDetail={true} />
    </main>
  );
}
```

`params` is a `Promise` here because this targets Next.js 15, where route params are async — if
`create-next-app@latest` in Task 1 scaffolded an earlier Next.js major version where `params` is a
plain object (not a Promise), adjust this one signature accordingly (`{ params }: { params: { id:
string } }` with no `await`) and note the version discrepancy in your report.

- [ ] **Step 4: Build and verify**

```bash
cd frontend
npm run build
```

Then, with the backend still running from Task 4's verification (or restart it with `./run.sh` from
the repo root if you stopped it):

```bash
npm run dev &
sleep 5
curl -s http://localhost:3000/loans/L001 | grep -o "Loan L001"
curl -s http://localhost:3000/loans/L001 | grep -c "canvas"
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:3000/loans/DOES-NOT-EXIST
kill %1
```

Expected: first command prints `Loan L001`; second prints a number ≥ 1 (the chart's canvas
element rendered server-side as part of the initial HTML, before client-side hydration takes over —
this confirms the component tree rendered without throwing); third prints `200` (Next.js's
`notFound()` renders the not-found page with a 200 status in dev mode by default — if you get a
different code, note it, that's a Next.js version-behavior detail worth flagging rather than a bug
to silently work around).

- [ ] **Step 5: Commit**

```bash
git add frontend/components/DscrChart.tsx frontend/app/loans
git commit -m "feat: add loan detail page with DSCR/debt-yield chart"
```

---

## Task 6: Error Boundary, README, Final Verification

**Files:**
- Create: `frontend/app/error.tsx`
- Modify: root `README.md` (add a "Frontend (Next.js)" section)

**Interfaces:**
- Consumes: nothing new.
- Produces: a root error boundary catching unhandled errors (e.g. the Spring Boot API being down) with a readable message instead of Next.js's default error overlay in production mode.

- [ ] **Step 1: Write `frontend/app/error.tsx`**

```tsx
"use client";

export default function Error({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <main className="mx-auto max-w-5xl p-6">
      <h1 className="text-2xl font-bold text-red-700">Something went wrong</h1>
      <p className="mt-2 text-gray-600">
        Couldn&apos;t reach the API. Confirm the Spring Boot app is running
        (<code>./run.sh</code> from the repo root), then try again.
      </p>
      <button
        type="button"
        onClick={reset}
        className="mt-4 rounded bg-gray-800 px-3 py-1.5 text-sm text-white hover:bg-gray-700"
      >
        Retry
      </button>
      <pre className="mt-4 text-xs text-gray-400">{error.message}</pre>
    </main>
  );
}
```

- [ ] **Step 2: Verify the error boundary triggers when the API is down**

```bash
# Stop the backend if it's running: docker compose down (from repo root)
cd frontend
npm run dev &
sleep 3
curl -s http://localhost:3000 | grep -o "Something went wrong"
kill %1
```

Expected: prints `Something went wrong`. Then restart the backend (`./run.sh` from the repo root)
for the next step.

- [ ] **Step 3: Add a "Frontend (Next.js)" section to the root `README.md`**

Insert this new section right after the existing "## Running it" section (before "## Adding more
synthetic data"):

```markdown
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
```

(Note: the nested triple-backtick above is literal markdown-inside-markdown for this plan step —
when you actually insert this into `README.md`, insert the inner content as a normal top-level
section with a single `bash` code fence, not nested fences. Read the existing `README.md`
structure and match its formatting conventions exactly.)

- [ ] **Step 4: Final full-stack manual verification**

```bash
# Terminal 1, from repo root:
./run.sh

# Terminal 2, once terminal 1 shows the app has started:
cd frontend && npm install && npm run dev
```

Open (or curl, if you have no browser access) both:
- `http://localhost:3000/` — confirm it lists alerts including `L001`, `L002`, `L003` with Score,
  Type/Time/Size breakdown, Fired Month, Rule Version columns, and Acknowledge buttons on OPEN
  alerts.
- `http://localhost:3000/loans/L001` — confirm loan info, a rendered chart, and an alerts table with
  Limit/Inputs columns visible.

If you have real browser access in this environment, actually click Acknowledge on one alert and
confirm it disappears from `/`. If you don't have browser access, at minimum re-run the Task 4-style
curl checks here against the fully-assembled app (both the queue and detail pages together) and
note in your report that the click-through itself wasn't verified interactively.

Stop both processes and run `docker compose down` from the repo root when finished.

- [ ] **Step 5: Commit**

```bash
git add frontend/app/error.tsx README.md
git commit -m "feat: add error boundary; document the Next.js frontend in README"
```