import "server-only";

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
  const res = await fetch(`${API_BASE_URL}/alerts/${id}/acknowledge`, { method: "POST", cache: "no-store" });
  if (!res.ok) {
    throw new Error(`Failed to acknowledge alert ${id}: ${res.status}`);
  }
}
