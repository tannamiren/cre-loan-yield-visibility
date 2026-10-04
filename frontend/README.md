# Frontend

Next.js (App Router, TypeScript, Tailwind) UI for the Loan Early-Warning Engine. Reads and writes
through the existing Spring Boot REST API — see the root [README.md](../README.md#frontend-nextjs)
for the full "Frontend (Next.js)" section covering setup, environment configuration, and routes.

Quick start (requires the Spring Boot backend running first — see root README's "Running it"):

```bash
cp .env.local.example .env.local   # if you haven't already
npm install
npm run dev
```

Open http://localhost:3000.
