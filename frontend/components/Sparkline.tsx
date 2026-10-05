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
