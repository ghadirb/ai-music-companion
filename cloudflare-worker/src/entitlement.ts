import { ISSUER } from "./auth";
import { limits, skuTable, type Env } from "./config";
import { base64UrlEncode, base64UrlEncodeBytes } from "./util";

export interface EntitlementRecord { sku: string; tokenId: string; grantedAt: number; expiresAt: number | null }
export interface OwnershipRecord { sub: string; transfers: number; claimedAt: number; lastTransferAt: number | null }

export interface ActiveEntitlement { premium: boolean; skus: string[]; expiresAt: number | null }

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
  return { premium: skus.length > 0, skus, expiresAt: skus.length && earliestExpiry !== null ? earliestExpiry : null };
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
