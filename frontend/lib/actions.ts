"use server";

import { revalidatePath } from "next/cache";
import { acknowledgeAlert } from "@/lib/api";

export async function acknowledgeAlertAction(alertId: number): Promise<void> {
  await acknowledgeAlert(alertId);
  revalidatePath("/");
  revalidatePath("/loans/[id]", "page");
}
