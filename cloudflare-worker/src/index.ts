import { signSession, verifySession, type Claims } from "./auth";
import { limits, MAX_OWNERSHIP_TRANSFERS, skuTable, TRANSFER_COOLDOWN_MS, type Env } from "./config";
import { requestIntent } from "./dj";
import {
  activeEntitlement, entitlementKey, newEntitlementRecord, ownershipKey, signEntitlementToken,
  type EntitlementRecord, type OwnershipRecord,
} from "./entitlement";
import { verifyWithMyket } from "./myket";
import { counterFor, type Counter } from "./quota";
import { base64UrlDecode, base64UrlEncode, hmacBase64Url, json, readJson, sha256Hex, timingSafeEqual } from "./util";

export { QuotaCounter } from "./quota";

export interface Deps {
  counter: Counter | null;
  fetchImpl: typeof fetch;
  now: () => number;
}

const ALLOWED_EMBEDDING_MODELS = new Set(["text-embedding-3-small"]);
const INSTALLATION_ID = /^[a-zA-Z0-9_-]{24,128}$/;
const TOKEN_ID = /^[A-Za-z0-9._:=+-]{1,256}$/;
const DAY_SECONDS = 86_400;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    return handle(request, env, { counter: counterFor(env), fetchImpl: fetch, now: Date.now });
  },
};

/** Exported for tests: dependency-injected request handler. */
export async function handle(request: Request, env: Env, deps: Deps): Promise<Response> {
  const requestId = crypto.randomUUID().slice(0, 8);
  const path = new URL(request.url).pathname;
  try {
    if (request.method === "GET" && path === "/healthz") return json({ ok: true });
    if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405, { allow: "POST" });
    if (!env.JWT_SIGNING_SECRET || !deps.counter) {
      log(requestId, "misconfigured", { path });
      return json({ error: "service_unavailable" }, 503);
    }

    if (path === "/v1/session/anonymous") return await issueSession(request, env, deps, requestId);

    const claims = await verifySession(request, env.JWT_SIGNING_SECRET, deps.now());
    if (!claims) return json({ error: "unauthorized" }, 401);

    const minute = Math.floor(deps.now() / 60_000);
    const rate = await deps.counter.incrementIfBelow(`rl:${claims.sub}:${minute}`, limits(env).requestsPerMinute, 120);
    if (!rate.allowed) return json({ error: "rate_limited" }, 429, { "retry-after": "30" });

    switch (path) {
      case "/v1/music-embedding": return await createEmbedding(request, env, deps, claims, requestId);
      case "/v1/dj/intent": return await createDjIntent(request, env, deps, claims, requestId);
      case "/v1/myket/purchase-nonce": return await issuePurchaseNonce(request, env, deps, claims);
      case "/v1/myket/verify": return await verifyPurchase(request, env, deps, claims, requestId);
      case "/v1/myket/restore": return await restorePurchase(request, env, deps, claims, requestId);
      case "/v1/entitlements/me": return await getEntitlement(env, deps, claims);
      default: return json({ error: "not_found" }, 404);
    }
  } catch (error) {
    log(requestId, "internal_error", { path, name: error instanceof Error ? error.name : "unknown" });
    return json({ error: "internal_error" }, 500);
  }
}

// ---------- sessions ----------

async function issueSession(request: Request, env: Env, deps: Deps, requestId: string): Promise<Response> {
  const body = await readJson<{ installationId?: unknown }>(request, 1024);
  if (!body.ok) return bodyError(body.error);
  const id = body.value.installationId;
  if (typeof id !== "string" || !INSTALLATION_ID.test(id)) return json({ error: "invalid_installation_id" }, 400);

  // Anonymous IDs are free to mint, so cap minting per network address to stop quota farming.
  const ipHash = await hashedIp(request, env);
  const day = dayStamp(deps.now());
  const minted = await deps.counter!.incrementIfBelow(`sess:${day}:${ipHash}`, limits(env).sessionPerIpDay, DAY_SECONDS + 3600);
  if (!minted.allowed) {
    log(requestId, "session_rate_limited", { ip: ipHash.slice(0, 8) });
    return json({ error: "too_many_sessions" }, 429, { "retry-after": "3600" });
  }
  const { token, expiresInSeconds } = await signSession(env.JWT_SIGNING_SECRET, id, deps.now());
  return json({ accessToken: token, expiresInSeconds });
}

// ---------- AI (embedding + DJ) ----------

type Reservation =
  | { ok: true; limit: number; remaining: number; premium: boolean; refund: () => Promise<void> }
  | { ok: false; response: Response };

async function reserveAi(request: Request, env: Env, deps: Deps, claims: Claims): Promise<Reservation> {
  const now = deps.now();
  const entitlement = await activeEntitlement(env, claims.sub, now);
  const cfg = limits(env);
  const limit = entitlement.premium ? cfg.premiumDaily : cfg.freeDaily;
  const day = dayStamp(now);
  const subKey = `ai:${day}:${claims.sub}`;
  const ttl = DAY_SECONDS + 3600;

  const sub = await deps.counter!.incrementIfBelow(subKey, limit, ttl);
  if (!sub.allowed) {
    return { ok: false, response: json({ error: "daily_quota_exhausted", limit, resetsAt: nextUtcMidnight(now) }, 429) };
  }
  let ipKey: string | null = null;
  if (!entitlement.premium) {
    ipKey = `aiip:${day}:${await hashedIp(request, env)}`;
    const ip = await deps.counter!.incrementIfBelow(ipKey, cfg.freeDaily * cfg.freeIpFactor, ttl);
    if (!ip.allowed) {
      await deps.counter!.decrement(subKey);
      return { ok: false, response: json({ error: "daily_quota_exhausted", limit, resetsAt: nextUtcMidnight(now) }, 429) };
    }
  }
  return {
    ok: true, limit, remaining: Math.max(0, limit - sub.count), premium: entitlement.premium,
    refund: async () => {
      await deps.counter!.decrement(subKey);
      if (ipKey) await deps.counter!.decrement(ipKey);
    },
  };
}

async function createEmbedding(request: Request, env: Env, deps: Deps, claims: Claims, requestId: string): Promise<Response> {
  const body = await readJson<{ model?: unknown; input?: unknown }>(request, 64 * 1024);
  if (!body.ok) return bodyError(body.error);
  const raw = body.value.input;
  const inputs = typeof raw === "string" ? [raw] : Array.isArray(raw) ? raw : null;
  if (!inputs || !inputs.length || inputs.length > 12 || inputs.some((v) => typeof v !== "string" || !v || v.length > 6_000)) {
    return json({ error: "invalid_input" }, 400);
  }
  const model = typeof body.value.model === "string" ? body.value.model : "text-embedding-3-small";
  if (!ALLOWED_EMBEDDING_MODELS.has(model)) return json({ error: "unsupported_model" }, 400);

  const reservation = await reserveAi(request, env, deps, claims);
  if (!reservation.ok) return reservation.response;

  let upstream: Response;
  try {
    upstream = await deps.fetchImpl("https://api.gapgpt.app/v1/embeddings", {
      method: "POST",
      headers: { authorization: `Bearer ${env.GAPGPT_API_KEY}`, "content-type": "application/json" },
      body: JSON.stringify({ model, input: inputs }),
      signal: AbortSignal.timeout(15_000),
    });
  } catch {
    await reservation.refund();
    return json({ error: "upstream_unavailable" }, 502);
  }
  const data = upstream.ok ? ((await upstream.json().catch(() => null)) as { data?: unknown; model?: unknown; usage?: unknown } | null) : null;
  if (!data?.data) {
    await reservation.refund();
    log(requestId, "embedding_upstream_error", { status: upstream.status });
    return json({ error: "upstream_error" }, 502);
  }
  // Only whitelisted fields are forwarded; upstream error bodies/headers never reach the client.
  return json({ data: data.data, model: data.model, usage: data.usage }, 200, aiHeaders(reservation));
}

async function createDjIntent(request: Request, env: Env, deps: Deps, claims: Claims, requestId: string): Promise<Response> {
  const entitlement = await activeEntitlement(env, claims.sub, deps.now());
  if (!entitlement.premium) return json({ error: "premium_required" }, 403);

  const body = await readJson<{ prompt?: unknown }>(request, 4 * 1024);
  if (!body.ok) return bodyError(body.error);
  const prompt = typeof body.value.prompt === "string" ? body.value.prompt.trim() : "";
  if (!prompt || prompt.length > 300) return json({ error: "invalid_prompt" }, 400);

  const reservation = await reserveAi(request, env, deps, claims);
  if (!reservation.ok) return reservation.response;

  const intent = await requestIntent(env, prompt, deps.fetchImpl);
  if (!intent) {
    await reservation.refund();
    log(requestId, "dj_upstream_error", {});
    return json({ error: "ai_unavailable" }, 502);
  }
  return json({ intent }, 200, aiHeaders(reservation));
}

function aiHeaders(r: { limit: number; remaining: number; premium: boolean }): Record<string, string> {
  return {
    "x-ai-daily-limit": String(r.limit),
    "x-ai-daily-remaining": String(r.remaining),
    "x-ai-tier": r.premium ? "premium" : "free",
  };
}

// ---------- entitlement ----------

async function getEntitlement(env: Env, deps: Deps, claims: Claims): Promise<Response> {
  const now = deps.now();
  const active = await activeEntitlement(env, claims.sub, now);
  const cfg = limits(env);
  const limit = active.premium ? cfg.premiumDaily : cfg.freeDaily;
  const used = await deps.counter!.get(`ai:${dayStamp(now)}:${claims.sub}`);
  return json({
    premium: active.premium,
    skus: active.skus,
    expiresAt: active.expiresAt,
    aiDailyLimit: limit,
    aiDailyRemaining: Math.max(0, limit - used),
    resetsAt: nextUtcMidnight(now),
    entitlementToken: await signEntitlementToken(env, claims.sub, active, now),
  });
}

// ---------- Myket purchases ----------

function paymentConfigured(env: Env): boolean {
  return !!(env.MYKET_ACCESS_TOKEN && env.MYKET_PACKAGE_NAME && env.PURCHASE_ENTITLEMENTS);
}

async function issuePurchaseNonce(request: Request, env: Env, deps: Deps, claims: Claims): Promise<Response> {
  if (!paymentConfigured(env)) return json({ error: "payment_not_configured" }, 503);
  const body = await readJson<{ sku?: unknown }>(request, 1024);
  if (!body.ok) return bodyError(body.error);
  const sku = body.value.sku;
  if (typeof sku !== "string" || !skuTable(env)[sku]) return json({ error: "invalid_sku" }, 400);

  const payload = base64UrlEncode(JSON.stringify({
    sub: claims.sub, sku, nonce: crypto.randomUUID(), exp: Math.floor(deps.now() / 1000) + 10 * 60,
  }));
  const signature = await hmacBase64Url(payload, env.JWT_SIGNING_SECRET);
  return json({ developerPayload: `v1.${payload}.${signature}` });
}

async function verifyPurchase(request: Request, env: Env, deps: Deps, claims: Claims, requestId: string): Promise<Response> {
  if (!paymentConfigured(env)) return json({ error: "payment_not_configured" }, 503);
  const body = await readJson<{ sku?: unknown; tokenId?: unknown; developerPayload?: unknown }>(request, 4 * 1024);
  if (!body.ok) return bodyError(body.error);
  const { sku, tokenId, developerPayload } = body.value;
  if (typeof sku !== "string" || !skuTable(env)[sku]) return json({ error: "invalid_sku" }, 400);
  if (typeof tokenId !== "string" || !TOKEN_ID.test(tokenId) || typeof developerPayload !== "string") {
    return json({ error: "invalid_purchase" }, 400);
  }

  const nonce = await validPurchasePayload(developerPayload, sku, claims.sub, env.JWT_SIGNING_SECRET, deps.now());
  if (!nonce) return json({ error: "invalid_developer_payload" }, 400);

  const owner = await readOwnership(env, tokenId);
  if (owner && owner.sub !== claims.sub) return json({ error: "purchase_already_claimed" }, 409);

  // Verify with Myket first so a transient Myket outage never burns the (single-use) nonce.
  const verified = await verifyWithMyket(env, sku, tokenId, deps.fetchImpl);
  if (!verified.ok) return verified.reason === "unavailable" ? json({ error: "verification_unavailable" }, 503) : json({ error: "purchase_not_valid" }, 400);
  if (verified.developerPayload !== developerPayload) return json({ error: "purchase_not_valid" }, 400);

  // Replay protection: each server-issued nonce can be redeemed exactly once (atomic).
  const redeemed = await deps.counter!.incrementIfBelow(`nonce:${nonce}`, 1, 3600);
  if (!redeemed.allowed) {
    // Same owner retrying after a lost response is idempotent; anyone else replaying is rejected.
    if (owner && owner.sub === claims.sub) return await entitlementResponse(env, deps, claims);
    return json({ error: "replay_detected" }, 409);
  }

  await grant(env, deps.now(), claims.sub, sku, tokenId, owner ?? null);
  log(requestId, "purchase_verified", { sku });
  return await entitlementResponse(env, deps, claims);
}

/**
 * Restore after reinstall/new device: the anonymous identity changed, so the original purchase payload can't match.
 * The Myket purchase token (only visible to the buyer's Myket account) is re-verified with Myket and the entitlement
 * is moved to the new identity. Transfers are limited (count + cooldown) to discourage sharing a token.
 */
async function restorePurchase(request: Request, env: Env, deps: Deps, claims: Claims, requestId: string): Promise<Response> {
  if (!paymentConfigured(env)) return json({ error: "payment_not_configured" }, 503);
  const body = await readJson<{ sku?: unknown; tokenId?: unknown }>(request, 2 * 1024);
  if (!body.ok) return bodyError(body.error);
  const { sku, tokenId } = body.value;
  if (typeof sku !== "string" || !skuTable(env)[sku]) return json({ error: "invalid_sku" }, 400);
  if (typeof tokenId !== "string" || !TOKEN_ID.test(tokenId)) return json({ error: "invalid_purchase" }, 400);

  const now = deps.now();
  const owner = await readOwnership(env, tokenId);
  if (owner && owner.sub !== claims.sub) {
    if (owner.transfers >= MAX_OWNERSHIP_TRANSFERS) return json({ error: "transfer_limit_reached" }, 409);
    if (owner.lastTransferAt !== null && now - owner.lastTransferAt < TRANSFER_COOLDOWN_MS) return json({ error: "transfer_cooldown" }, 429);
  }

  const verified = await verifyWithMyket(env, sku, tokenId, deps.fetchImpl);
  if (!verified.ok) return verified.reason === "unavailable" ? json({ error: "verification_unavailable" }, 503) : json({ error: "purchase_not_valid" }, 400);

  await grant(env, now, claims.sub, sku, tokenId, owner ?? null);
  log(requestId, "purchase_restored", { sku, transferred: !!owner && owner.sub !== claims.sub });
  return await entitlementResponse(env, deps, claims);
}

async function grant(env: Env, nowMs: number, sub: string, sku: string, tokenId: string, owner: OwnershipRecord | null): Promise<void> {
  const kv = env.PURCHASE_ENTITLEMENTS!;
  let grantedAt = nowMs;
  if (owner && owner.sub !== sub) {
    // Move (don't duplicate) the entitlement, keeping the original purchase time so time-limited SKUs are not extended.
    const previous = await kv.get(entitlementKey(owner.sub, sku));
    if (previous) {
      try { grantedAt = (JSON.parse(previous) as Partial<EntitlementRecord>).grantedAt ?? nowMs; } catch { /* ignore */ }
    }
    await kv.delete(entitlementKey(owner.sub, sku));
  } else if (owner) {
    const existing = await kv.get(entitlementKey(sub, sku));
    if (existing) {
      try { grantedAt = (JSON.parse(existing) as Partial<EntitlementRecord>).grantedAt ?? nowMs; } catch { /* ignore */ }
    }
  }
  const ownership: OwnershipRecord = owner && owner.sub !== sub
    ? { sub, transfers: owner.transfers + 1, claimedAt: owner.claimedAt, lastTransferAt: nowMs }
    : owner ?? { sub, transfers: 0, claimedAt: nowMs, lastTransferAt: null };
  await kv.put(ownershipKey(tokenId), JSON.stringify(ownership));
  await kv.put(entitlementKey(sub, sku), JSON.stringify(newEntitlementRecord(env, sku, tokenId, nowMs, grantedAt)));
}

async function readOwnership(env: Env, tokenId: string): Promise<OwnershipRecord | null> {
  const raw = await env.PURCHASE_ENTITLEMENTS!.get(ownershipKey(tokenId));
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as Partial<OwnershipRecord>;
    if (typeof parsed.sub === "string") {
      return { sub: parsed.sub, transfers: parsed.transfers ?? 0, claimedAt: parsed.claimedAt ?? 0, lastTransferAt: parsed.lastTransferAt ?? null };
    }
  } catch { /* legacy plain-string owner from the first version */ }
  return { sub: raw, transfers: 0, claimedAt: 0, lastTransferAt: null };
}

async function entitlementResponse(env: Env, deps: Deps, claims: Claims): Promise<Response> {
  const now = deps.now();
  const active = await activeEntitlement(env, claims.sub, now);
  return json({
    premium: active.premium, skus: active.skus, expiresAt: active.expiresAt,
    entitlementToken: await signEntitlementToken(env, claims.sub, active, now),
  });
}

/** Returns the nonce if the payload is authentic, unexpired and bound to this subject + SKU. */
async function validPurchasePayload(payload: string, sku: string, sub: string, secret: string, nowMs: number): Promise<string | null> {
  const [version, encoded, signature] = payload.split(".");
  if (version !== "v1" || !encoded || !signature) return null;
  try {
    const expected = await hmacBase64Url(encoded, secret);
    if (!timingSafeEqual(signature, expected)) return null;
    const value = JSON.parse(base64UrlDecode(encoded)) as { sub?: string; sku?: string; nonce?: string; exp?: number };
    if (value.sub !== sub || value.sku !== sku || !value.nonce || !value.exp || nowMs >= value.exp * 1000) return null;
    return value.nonce;
  } catch {
    return null;
  }
}

// ---------- helpers ----------

function bodyError(error: "unsupported_media_type" | "payload_too_large" | "invalid_json"): Response {
  return json({ error }, error === "unsupported_media_type" ? 415 : error === "payload_too_large" ? 413 : 400);
}

const dayStamp = (nowMs: number) => new Date(nowMs).toISOString().slice(0, 10);
function nextUtcMidnight(nowMs: number): string {
  const d = new Date(nowMs);
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate() + 1)).toISOString();
}

async function hashedIp(request: Request, env: Env): Promise<string> {
  const ip = request.headers.get("cf-connecting-ip") ?? "unknown";
  return (await sha256Hex(`${ip}|${env.JWT_SIGNING_SECRET}`)).slice(0, 32);
}

/** Structured logs: request id + event only. Never tokens, purchase tokens, prompts, IPs or secrets. */
function log(requestId: string, event: string, fields: Record<string, unknown>): void {
  console.log(JSON.stringify({ rid: requestId, event, ...fields }));
}
