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
