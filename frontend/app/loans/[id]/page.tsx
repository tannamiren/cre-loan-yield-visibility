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
