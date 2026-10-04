"use client";

import { useState } from "react";
import { acknowledgeAlertAction } from "@/lib/actions";

export function AcknowledgeButton({ alertId }: { alertId: number }) {
  const [pending, setPending] = useState(false);
  const [failed, setFailed] = useState(false);

  return (
    <button
      type="button"
      disabled={pending}
      onClick={async () => {
        setPending(true);
        setFailed(false);
        try {
          await acknowledgeAlertAction(alertId);
        } catch {
          setFailed(true);
        } finally {
          setPending(false);
        }
      }}
      className="rounded bg-gray-800 px-2 py-1 text-xs text-white hover:bg-gray-700 disabled:opacity-50"
    >
      {pending ? "..." : failed ? "Retry" : "Acknowledge"}
    </button>
  );
}
