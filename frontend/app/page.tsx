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
