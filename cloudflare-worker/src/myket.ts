import type { Env } from "./config";

export type MyketResult =
  | { ok: true; developerPayload: string | null }
  | { ok: false; reason: "unavailable" | "invalid" };

/** Server-side purchase verification against Myket. The Myket access token never leaves this Worker. */
export async function verifyWithMyket(env: Env, sku: string, tokenId: string, fetchImpl: typeof fetch): Promise<MyketResult> {
  if (!env.MYKET_ACCESS_TOKEN || !env.MYKET_PACKAGE_NAME) return { ok: false, reason: "unavailable" };
  const endpoint = `https://developer.myket.ir/api/partners/applications/${encodeURIComponent(env.MYKET_PACKAGE_NAME)}/purchases/products/${encodeURIComponent(sku)}/verify`;
  try {
    const upstream = await fetchImpl(endpoint, {
      method: "POST",
      headers: { "X-Access-Token": env.MYKET_ACCESS_TOKEN, "content-type": "application/json" },
      body: JSON.stringify({ tokenId }),
      signal: AbortSignal.timeout(10_000),
    });
    if (upstream.status >= 500) return { ok: false, reason: "unavailable" };
    const body = (await upstream.json().catch(() => null)) as Record<string, unknown> | null;
    if (!upstream.ok || !body) return { ok: false, reason: "invalid" };
    if (body.purchaseState !== 0 || body.consumptionState !== 0) return { ok: false, reason: "invalid" };
    return { ok: true, developerPayload: typeof body.developerPayload === "string" ? body.developerPayload : null };
  } catch {
    return { ok: false, reason: "unavailable" };
  }
}
