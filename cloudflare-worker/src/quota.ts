import type { Env } from "./config";

export interface CounterResult { allowed: boolean; count: number }

/** Atomic counters (quota, rate limits, single-use nonces). */
export interface Counter {
  /** Increments only if the current value is below `limit`; expires after `ttlSeconds`. */
  incrementIfBelow(key: string, limit: number, ttlSeconds: number): Promise<CounterResult>;
  decrement(key: string): Promise<void>;
  get(key: string): Promise<number>;
}

interface Stored { count: number; expiresAt: number }

/**
 * Durable Object: one instance per counter key, so increments are strictly serialized
 * (KV read-modify-write is NOT atomic and can be raced to exceed a quota).
 */
export class QuotaCounter {
  constructor(private readonly state: DurableObjectState) {}

  async fetch(request: Request): Promise<Response> {
    const body = (await request.json().catch(() => null)) as { op?: string; limit?: number; ttl?: number } | null;
    const now = Date.now();
    let stored = await this.state.storage.get<Stored>("c");
    if (stored && stored.expiresAt <= now) stored = undefined;

    switch (body?.op) {
      case "incr": {
        const limit = Math.max(0, Math.floor(body.limit ?? 0));
        const count = stored?.count ?? 0;
        if (count >= limit) return Response.json({ allowed: false, count });
        const next: Stored = { count: count + 1, expiresAt: stored?.expiresAt ?? now + Math.max(1, body.ttl ?? 60) * 1000 };
        await this.state.storage.put("c", next);
        await this.state.storage.setAlarm(next.expiresAt);
        return Response.json({ allowed: true, count: next.count });
      }
      case "decr": {
        if (stored && stored.count > 0) await this.state.storage.put("c", { ...stored, count: stored.count - 1 });
        return Response.json({ count: Math.max(0, (stored?.count ?? 0) - 1) });
      }
      case "get":
        return Response.json({ count: stored?.count ?? 0 });
      default:
        return Response.json({ error: "bad_op" }, { status: 400 });
    }
  }

  async alarm(): Promise<void> {
    await this.state.storage.deleteAll();
  }
}

export class DurableObjectCounter implements Counter {
  constructor(private readonly namespace: DurableObjectNamespace) {}

  private async call<T>(key: string, payload: object): Promise<T> {
    const stub = this.namespace.get(this.namespace.idFromName(key));
    const response = await stub.fetch("https://counter/op", { method: "POST", body: JSON.stringify(payload) });
    return (await response.json()) as T;
  }
  incrementIfBelow(key: string, limit: number, ttlSeconds: number) {
    return this.call<CounterResult>(key, { op: "incr", limit, ttl: ttlSeconds });
  }
  async decrement(key: string) { await this.call(key, { op: "decr" }); }
  async get(key: string) { return (await this.call<{ count: number }>(key, { op: "get" })).count; }
}

/**
 * Development fallback only (no Durable Object binding): best-effort KV counter, NOT race-safe.
 * Production must bind QUOTA_COUNTER (see wrangler.toml).
 */
export class KvCounter implements Counter {
  constructor(private readonly kv: KVNamespace) {}
  async incrementIfBelow(key: string, limit: number, ttlSeconds: number): Promise<CounterResult> {
    const count = Number.parseInt((await this.kv.get(`ctr:${key}`)) ?? "0", 10) || 0;
    if (count >= limit) return { allowed: false, count };
    await this.kv.put(`ctr:${key}`, String(count + 1), { expirationTtl: Math.max(60, ttlSeconds) });
    return { allowed: true, count: count + 1 };
  }
  async decrement(key: string) {
    const count = Number.parseInt((await this.kv.get(`ctr:${key}`)) ?? "0", 10) || 0;
    if (count > 0) await this.kv.put(`ctr:${key}`, String(count - 1), { expirationTtl: 86_400 });
  }
  async get(key: string) { return Number.parseInt((await this.kv.get(`ctr:${key}`)) ?? "0", 10) || 0; }
}

export function counterFor(env: Env): Counter | null {
  if (env.QUOTA_COUNTER) return new DurableObjectCounter(env.QUOTA_COUNTER);
  if (env.PURCHASE_ENTITLEMENTS) return new KvCounter(env.PURCHASE_ENTITLEMENTS);
  return null;
}
