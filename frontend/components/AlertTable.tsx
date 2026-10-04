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
