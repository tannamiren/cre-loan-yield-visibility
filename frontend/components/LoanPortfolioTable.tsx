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
            <td className="p-2">{loan.latestDscr !== null ? `${loan.latestDscr.toFixed(2)}x` : "—"}</td>
            <td className="p-2">
              {loan.latestDebtYield !== null ? `${(loan.latestDebtYield * 100).toFixed(2)}%` : "—"}
            </td>
            <td className="p-2">
              <Sparkline data={loan.history.map((h) => h.dscr)} />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
