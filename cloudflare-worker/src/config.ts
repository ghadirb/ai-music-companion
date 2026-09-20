export interface Env {
  // ---- secrets (Cloudflare secrets only; never in the repo or the APK) ----
  GAPGPT_API_KEY: string;
  JWT_SIGNING_SECRET: string;
  MYKET_ACCESS_TOKEN?: string;
  /** Private ES256 key (JWK JSON) used to sign entitlement tokens the app verifies offline. */
  ENTITLEMENT_SIGNING_JWK?: string;

  // ---- plain configuration (wrangler.toml [vars]) ----
  MYKET_PACKAGE_NAME?: string;
  FREE_DAILY_EMBEDDING_LIMIT?: string;
  PREMIUM_DAILY_EMBEDDING_LIMIT?: string;
  /** Free users behind one IP may use at most FREE_DAILY_LIMIT * this factor per day. */
  FREE_IP_DAILY_FACTOR?: string;
  SESSION_LIMIT_PER_IP_DAY?: string;
  REQUEST_LIMIT_PER_MINUTE?: string;
  ENTITLEMENT_TTL_DAYS?: string;
  /** JSON map: sku -> { "durationDays": number | null }. Defaults below. */
  PREMIUM_SKUS?: string;
  DJ_MODEL?: string;

  // ---- bindings ----
  PURCHASE_ENTITLEMENTS?: KVNamespace;
  QUOTA_COUNTER?: DurableObjectNamespace;
}

export interface SkuConfig { durationDays: number | null }

const DEFAULT_SKUS: Record<string, SkuConfig> = {
  premium_lifetime: { durationDays: null },
  premium_monthly: { durationDays: 31 },
  premium_yearly: { durationDays: 366 },
};

export function skuTable(env: Env): Record<string, SkuConfig> {
  if (!env.PREMIUM_SKUS) return DEFAULT_SKUS;
  try {
    const parsed = JSON.parse(env.PREMIUM_SKUS) as Record<string, { durationDays?: number | null }>;
    const table: Record<string, SkuConfig> = {};
    for (const [sku, value] of Object.entries(parsed)) {
      if (!/^[a-z0-9_]{1,64}$/.test(sku)) continue;
      const days = value?.durationDays;
      table[sku] = { durationDays: typeof days === "number" && days > 0 && days <= 3660 ? Math.floor(days) : null };
    }
    return Object.keys(table).length ? table : DEFAULT_SKUS;
  } catch {
    return DEFAULT_SKUS;
  }
}

function positive(raw: string | undefined, fallback: number, max = 100_000): number {
  const value = Number.parseInt(raw ?? "", 10);
  return Number.isFinite(value) && value > 0 && value <= max ? value : fallback;
}

export interface Limits {
  freeDaily: number;
  premiumDaily: number;
  freeIpFactor: number;
  sessionPerIpDay: number;
  requestsPerMinute: number;
  entitlementTtlDays: number;
}

/** All numbers are configuration, not code: change wrangler.toml [vars], no redeploy of logic needed. */
export function limits(env: Env): Limits {
  return {
    freeDaily: positive(env.FREE_DAILY_EMBEDDING_LIMIT, 8, 10_000),
    premiumDaily: positive(env.PREMIUM_DAILY_EMBEDDING_LIMIT, 120, 10_000),
    freeIpFactor: positive(env.FREE_IP_DAILY_FACTOR, 5, 100),
    sessionPerIpDay: positive(env.SESSION_LIMIT_PER_IP_DAY, 30, 10_000),
    requestsPerMinute: positive(env.REQUEST_LIMIT_PER_MINUTE, 40, 10_000),
    entitlementTtlDays: positive(env.ENTITLEMENT_TTL_DAYS, 7, 60),
  };
}

export const MAX_OWNERSHIP_TRANSFERS = 3;
export const TRANSFER_COOLDOWN_MS = 24 * 60 * 60 * 1000;
