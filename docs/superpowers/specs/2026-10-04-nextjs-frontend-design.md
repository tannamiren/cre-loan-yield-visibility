# Next.js Frontend — Design

Date: 2026-10-04
Status: Approved

Replaces the Thymeleaf screens' role as the primary UI with a Next.js app, without touching the
existing Spring Boot app's REST API, Thymeleaf screens, or any reviewed backend code. Thymeleaf
stays in place as a fallback; this document only covers the new frontend.

## 1. Structure & Stack

- **Location**: `/frontend` at the repo root, alongside `/src`, `docker-compose.yml`, etc. — a
  separate Node project, not a Maven module, not built or run by Maven.
- **Stack**: Next.js 15 (App Router), TypeScript, Tailwind CSS, npm.
- **Pages**:
  - `/` — alert queue (replaces `queue.html`'s role as the UI you actually use)
  - `/loans/[id]` — loan detail with chart + alert history (replaces `loan-detail.html`'s role)
- **Data fetching**: Server Components call the Spring Boot API directly via a server-side base URL
  (`API_BASE_URL` env var in `frontend/.env.local`, defaulting to `http://localhost:8080`). This is a
  server-to-server call from the Next.js Node process to the Spring Boot process — no CORS
  configuration is needed anywhere, since the browser never talks to port 8080 directly.
- **Mutations**: the "Acknowledge" action is a Next.js Server Action (`'use server'`) that POSTs to
  `/alerts/{id}/acknowledge` on the Spring Boot API, then calls `revalidatePath('/')` so the queue
  re-renders without the acknowledged alert. Same "no CORS, no client fetch" property as reads.
- **Chart**: `react-chartjs-2` (the React wrapper around Chart.js) instead of the CDN-`<script>`
  approach the Thymeleaf page used — proper component lifecycle/cleanup, same underlying charting
  library so the visual result matches.
- **Thymeleaf is untouched**: no changes to `queue.html`, `loan-detail.html`,
  `QueueViewController`, `LoanDetailViewController`, or any REST controller. The Spring Boot app
  keeps running on port 8080 exactly as before; Next.js is an additive dev server on port 3000.

## 2. Pages & Components

**Queue page (`/`)** — Server Component. Fetches `GET /alerts`. Renders a table: Loan (links to
`/loans/{id}`), Rule, Score with Type/Time/Size breakdown columns (matching the README's
explanation of what those mean), Fired Month, Rule Version, and an Acknowledge button per row — the
button is a small Client Component wrapping the Server Action, since it needs an `onClick`.

**Loan detail page (`/loans/[id]`)** — Server Component. Fetches `GET /loans/{id}`. Renders:
- Loan info (property type, original balance, rate, maturity date)
- A `react-chartjs-2` line chart of DSCR and debt yield across the 24 months — a Client Component
  (Chart.js needs a browser canvas) that receives the already-fetched history data as props from its
  server parent; it does no fetching of its own.
- An alerts table (same columns as the queue, plus Limit and Inputs, for full audit detail)

**Shared code**:
- `frontend/lib/api.ts` — typed fetch helpers (`getAlerts()`, `getLoan(id)`, `acknowledgeAlert(id)`)
  with TypeScript types matching the existing `AlertDto`/`LoanDetailDto`/`LoanHistoryPointDto` JSON
  shapes from the Spring Boot API.
- `frontend/components/AlertTable.tsx` and `ScoreBreakdown.tsx` — shared presentational components
  used by both pages, so the alert table isn't duplicated.

No routing library, no client-side state management, no client-side data cache (React Query, etc.)
— Server Components plus `revalidatePath` cover this app's needs.

## 3. Error Handling, Testing, Running

**Error handling**: an unknown loan ID triggers Next.js's `notFound()` (renders the built-in 404
page) rather than letting a failed fetch throw an unhandled error. A root `error.tsx` boundary
catches anything else (e.g. the Spring Boot API being unreachable) and shows a simple "couldn't
reach the API" message instead of a raw stack trace.

**Testing**: no automated test suite for this frontend (explicitly out of scope per this round —
skipped by request). Verification is manual: once built, load `/`, load a loan detail page, click
Acknowledge, and confirm the alert disappears from the queue — mirroring how the Thymeleaf screens
were manually verified in the original plan's Task 15.

**Running it**: `run.sh` is unchanged — it continues to start only the Spring Boot app (Docker +
backend), which remains the system of record. The frontend is a separate, optional dev server:

```bash
cd frontend
npm install
npm run dev   # http://localhost:3000
```

Documented as a new "Frontend (Next.js)" section in the root `README.md`.

## Out of scope

Automated UI tests, removing/modifying the Thymeleaf screens, CORS configuration (not needed given
the server-to-server fetch approach), authentication, any change to the Spring Boot REST API
contracts or Thymeleaf templates.
