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
