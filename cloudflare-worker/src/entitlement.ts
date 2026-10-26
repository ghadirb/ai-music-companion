import { ISSUER } from "./auth";
import { limits, skuTable, type Env } from "./config";
import { base64UrlEncode, base64UrlEncodeBytes } from "./util";

export interface EntitlementRecord { sku: string; tokenId: string; grantedAt: number; expiresAt: number | null }
export interface OwnershipRecord { sub: string; transfers: number; claimedAt: number; lastTransferAt: number | null }

export interface ActiveEntitlement { premium: boolean; skus: string[]; expiresAt: number | null; /** True when premium comes ONLY from the free trial. */ trial?: boolean }

/** Pseudo-SKU carried in entitlement tokens while the free trial is active. */
export const TRIAL_SKU = "trial";
export interface TrialRecord { startedAt: number; expiresAt: number }
export interface TrialStatus { eligible: boolean; active: boolean; startedAt: number | null; expiresAt: number | null }
export const trialKey = (sub: string) => `trial:${sub}`;

export async function readTrial(env: Env, sub: string): Promise<TrialRecord | null> {
  if (!env.PURCHASE_ENTITLEMENTS) return null;
  const raw = await env.PURCHASE_ENTITLEMENTS.get(trialKey(sub));
  if (!raw) return null;
  try {
    const r = JSON.parse(raw) as Partial<TrialRecord>;
    if (typeof r.startedAt === "number" && typeof r.expiresAt === "number") return { startedAt: r.startedAt, expiresAt: r.expiresAt };
  } catch { /* corrupt => treat as used, never re-grant */ }
  return { startedAt: 0, expiresAt: 0 };
}

/** The trial is one-time: eligible only if this identity never started it. */
export async function trialStatus(env: Env, sub: string, nowMs: number): Promise<TrialStatus> {
  if (!env.PURCHASE_ENTITLEMENTS) return { eligible: false, active: false, startedAt: null, expiresAt: null };
  const record = await readTrial(env, sub);
  if (!record) return { eligible: true, active: false, startedAt: null, expiresAt: null };
  return { eligible: false, active: nowMs < record.expiresAt, startedAt: record.startedAt, expiresAt: record.expiresAt };
}

export const entitlementKey = (sub: string, sku: string) => `entitlement:${sub}:${sku}`;
export const ownershipKey = (tokenId: string) => `myket-token:${tokenId}`;

/** Which SKUs are currently valid for `sub`. Premium is derived from server-side records, never from the client. */
export async function activeEntitlement(env: Env, sub: string, nowMs: number): Promise<ActiveEntitlement> {
  if (!env.PURCHASE_ENTITLEMENTS) return { premium: false, skus: [], expiresAt: null };
  const skus: string[] = [];
  let earliestExpiry: number | null = null;
  for (const sku of Object.keys(skuTable(env))) {
    const raw = await env.PURCHASE_ENTITLEMENTS.get(entitlementKey(sub, sku));
    if (!raw) continue;
    try {
      const record = JSON.parse(raw) as Partial<EntitlementRecord>;
      if (record.expiresAt != null && nowMs >= record.expiresAt) continue; // expired
      skus.push(sku);
      if (record.expiresAt != null) earliestExpiry = earliestExpiry === null ? record.expiresAt : Math.max(earliestExpiry, record.expiresAt);
    } catch { /* corrupt record => no entitlement */ }
  }
  if (skus.length) return { premium: true, skus, expiresAt: earliestExpiry };
  // No purchase: the one-time free trial (if running) grants premium until its end.
  const trial = await readTrial(env, sub);
  if (trial && nowMs < trial.expiresAt) return { premium: true, skus: [TRIAL_SKU], expiresAt: trial.expiresAt, trial: true };
  return { premium: false, skus: [], expiresAt: null };
}

export function newEntitlementRecord(env: Env, sku: string, tokenId: string, nowMs: number, grantedAt = nowMs): EntitlementRecord {
  const days = skuTable(env)[sku]?.durationDays ?? null;
  return { sku, tokenId, grantedAt, expiresAt: days === null ? null : grantedAt + days * 86_400_000 };
}

/**
 * Signs a short-lived entitlement token (ES256). The app embeds only the PUBLIC key and verifies it offline,
 * so being "premium" is decided by this server, not by a boolean in the APK.
 */
export async function signEntitlementToken(env: Env, sub: string, active: ActiveEntitlement, nowMs: number): Promise<string | null> {
  if (!env.ENTITLEMENT_SIGNING_JWK || !active.premium) return null;
  let key: CryptoKey;
  try {
    key = await crypto.subtle.importKey("jwk", JSON.parse(env.ENTITLEMENT_SIGNING_JWK), { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
  } catch {
    return null;
  }
  const iat = Math.floor(nowMs / 1000);
  let exp = iat + limits(env).entitlementTtlDays * 86_400;
  if (active.expiresAt !== null) exp = Math.min(exp, Math.floor(active.expiresAt / 1000));
  const header = base64UrlEncode(JSON.stringify({ alg: "ES256", typ: "JWT" }));
  const payload = base64UrlEncode(JSON.stringify({ iss: ISSUER, sub, plan: "premium", skus: active.skus, iat, exp }));
  const signature = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, new TextEncoder().encode(`${header}.${payload}`));
  return `${header}.${payload}.${base64UrlEncodeBytes(new Uint8Array(signature))}`;
}
