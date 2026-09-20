import { handle, type Deps } from "../src/index";
import type { Env } from "../src/config";
import type { Counter } from "../src/quota";
import { signSession } from "../src/auth";

export class MemoryKV {
  store = new Map<string, string>();
  async get(key: string) { return this.store.get(key) ?? null; }
  async put(key: string, value: string) { this.store.set(key, value); }
  async delete(key: string) { this.store.delete(key); }
}

export class MemoryCounter implements Counter {
  values = new Map<string, number>();
  async incrementIfBelow(key: string, limit: number) {
    const count = this.values.get(key) ?? 0;
    if (count >= limit) return { allowed: false, count };
    this.values.set(key, count + 1);
    return { allowed: true, count: count + 1 };
  }
  async decrement(key: string) { const c = this.values.get(key) ?? 0; if (c > 0) this.values.set(key, c - 1); }
  async get(key: string) { return this.values.get(key) ?? 0; }
}

export const SECRET = "test-secret-".padEnd(40, "x");
export const NOW = Date.UTC(2026, 8, 20, 12, 0, 0);
export const installId = (n = 1) => `install-${n}`.padEnd(32, "a");

export interface Harness {
  env: Env;
  kv: MemoryKV;
  counter: MemoryCounter;
  fetchCalls: { url: string; body: any }[];
  fetchImpl: (input: any, init?: any) => Promise<Response>;
  setUpstream: (fn: (url: string, body: any) => Response | Promise<Response>) => void;
  clock: { now: number };
  call: (path: string, opts?: { token?: string | null; body?: unknown; raw?: string; ip?: string; method?: string; contentType?: string }) => Promise<{ status: number; body: any; headers: Headers }>;
  session: (n?: number, ip?: string) => Promise<string>;
}

export function makeHarness(envOverrides: Partial<Env> = {}): Harness {
  const kv = new MemoryKV();
  const counter = new MemoryCounter();
  const clock = { now: NOW };
  const fetchCalls: { url: string; body: any }[] = [];
  let upstream: (url: string, body: any) => Response | Promise<Response> = () => new Response("{}", { status: 500 });
  const fetchImpl = async (input: any, init?: any) => {
    const url = String(input);
    const body = init?.body ? JSON.parse(init.body) : null;
    fetchCalls.push({ url, body });
    return upstream(url, body);
  };
  const env = {
    GAPGPT_API_KEY: "gap-key",
    JWT_SIGNING_SECRET: SECRET,
    MYKET_ACCESS_TOKEN: "myket-token",
    MYKET_PACKAGE_NAME: "com.ghadirb.aimusic",
    PURCHASE_ENTITLEMENTS: kv as unknown as KVNamespace,
    ...envOverrides,
  } as Env;
  const deps: Deps = { counter, fetchImpl: fetchImpl as typeof fetch, now: () => clock.now };

  const call: Harness["call"] = async (path, opts = {}) => {
    const headers: Record<string, string> = { "cf-connecting-ip": opts.ip ?? "203.0.113.5" };
    if (opts.token) headers.authorization = `Bearer ${opts.token}`;
    const method = opts.method ?? "POST";
    if (method === "POST") headers["content-type"] = opts.contentType ?? "application/json";
    const body = method === "POST" ? (opts.raw ?? JSON.stringify(opts.body ?? {})) : undefined;
    const response = await handle(new Request(`https://gw.test${path}`, { method, headers, body }), env, deps);
    const text = await response.text();
    let parsed: any = null;
    try { parsed = JSON.parse(text); } catch { parsed = text; }
    return { status: response.status, body: parsed, headers: response.headers };
  };

  const session: Harness["session"] = async (n = 1, ip) => {
    const res = await call("/v1/session/anonymous", { body: { installationId: installId(n) }, ip });
    if (res.status !== 200) throw new Error(`session failed ${res.status}`);
    return res.body.accessToken as string;
  };

  return { env, kv, counter, fetchCalls, fetchImpl, setUpstream: (fn) => { upstream = fn; }, clock, call, session };
}

export const okEmbedding = () => new Response(JSON.stringify({ data: [{ embedding: [0.1, 0.2] }], model: "text-embedding-3-small", usage: { total_tokens: 3 }, secret_debug: "x" }), { status: 200 });
export { signSession };
