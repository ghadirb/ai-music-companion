import type { TrackVersion } from "./catalog";

export type ProbeFailure = "not_found" | "forbidden" | "not_audio" | "timeout" | "network" | "server_error" | "bad_status";
export type ProbeResult = { ok: true; contentType: string | null; contentLength: number | null } | { ok: false; reason: ProbeFailure; status?: number };

/** Link-specific failures say nothing about the site; availability failures do. */
export const SITE_FAILURES: ReadonlySet<ProbeFailure> = new Set<ProbeFailure>(["timeout", "network", "server_error"]);

/** Recognises real audio by magic bytes: a 200 response with an HTML error page must never count as playable. */
export function looksLikeAudio(head: Uint8Array): boolean {
  if (head.length < 4) return false;
  const ascii = (from: number, text: string) => text.split("").every((c, i) => head[from + i] === c.charCodeAt(0));
  if (ascii(0, "ID3")) return true;                                  // MP3 with ID3v2
  if (head[0] === 0xff && (head[1] & 0xe0) === 0xe0) return true;   // MPEG audio frame sync
  if (ascii(0, "fLaC") || ascii(0, "OggS")) return true;            // FLAC, Ogg/Opus
  if (ascii(0, "RIFF") && head.length >= 12 && ascii(8, "WAVE")) return true;
  if (head.length >= 8 && ascii(4, "ftyp")) return true;            // MP4/M4A
  return false;
}

/**
 * Checks that a URL really serves audio. Uses a 1 KB ranged GET (some servers reject HEAD) and never downloads the file.
 * `fetchImpl` is injected so tests (and the Worker) control the network.
 */
export async function probeAudio(url: string, fetchImpl: typeof fetch, timeoutMs = 8000): Promise<ProbeResult> {
  let res: Response;
  try {
    res = await fetchImpl(url, { method: "GET", headers: { range: "bytes=0-1023", "user-agent": "NovasaMusicBot/1.0" }, redirect: "manual", signal: AbortSignal.timeout(timeoutMs) });
  } catch (e) {
    return { ok: false, reason: e instanceof Error && (e.name === "TimeoutError" || e.name === "AbortError") ? "timeout" : "network" };
  }
  if (res.status === 404 || res.status === 410) return { ok: false, reason: "not_found", status: res.status };
  if (res.status === 401 || res.status === 403) return { ok: false, reason: "forbidden", status: res.status };
  if (res.status >= 500) return { ok: false, reason: "server_error", status: res.status };
  if (res.status !== 200 && res.status !== 206) return { ok: false, reason: "bad_status", status: res.status }; // includes redirects: do not follow blindly
  const contentType = res.headers.get("content-type");
  if (contentType && /^text\/html/i.test(contentType)) return { ok: false, reason: "not_audio", status: res.status };
  let head: Uint8Array;
  try { head = new Uint8Array(await res.arrayBuffer()).subarray(0, 1024); } catch { return { ok: false, reason: "network" }; }
  if (!looksLikeAudio(head)) return { ok: false, reason: "not_audio", status: res.status };
  const range = res.headers.get("content-range")?.match(/\/(\d+)$/);
  const length = range ? Number(range[1]) : Number(res.headers.get("content-length")) || null;
  return { ok: true, contentType, contentLength: length };
}

export interface HealthPolicy {
  /** Availability failures within the window that open the breaker. */
  siteFailureThreshold: number;
  /** Distinct broken links (with zero successes) within the window that also open it: the whole site is probably gone/blocked. */
  linkFailureThreshold: number;
  windowMs: number;
  cooldownMs: number;
}

export const DEFAULT_HEALTH_POLICY: HealthPolicy = { siteFailureThreshold: 3, linkFailureThreshold: 8, windowMs: 5 * 60_000, cooldownMs: 10 * 60_000 };

interface SourceState { siteFailures: number; badLinks: Set<string>; successes: number; windowStart: number; openUntil: number }

/** Per-source circuit breaker. In-memory (per Worker isolate); the interface lets a durable store replace it. */
export class SourceHealth {
  private readonly states = new Map<string, SourceState>();
  constructor(private readonly policy: HealthPolicy = DEFAULT_HEALTH_POLICY) {}

  private state(id: string, now: number): SourceState {
    let s = this.states.get(id);
    if (!s || now - s.windowStart > this.policy.windowMs) {
      s = { siteFailures: 0, badLinks: new Set(), successes: 0, windowStart: now, openUntil: s?.openUntil ?? 0 };
      this.states.set(id, s);
    }
    return s;
  }

  isOpen(id: string, now: number): boolean { return (this.states.get(id)?.openUntil ?? 0) > now; }

  recordSuccess(id: string, now: number): void {
    const s = this.state(id, now);
    s.successes += 1;
    s.siteFailures = 0;
    s.openUntil = 0;
  }

  recordFailure(id: string, url: string, reason: ProbeFailure, now: number): void {
    const s = this.state(id, now);
    if (SITE_FAILURES.has(reason)) s.siteFailures += 1; else s.badLinks.add(url);
    if (s.siteFailures >= this.policy.siteFailureThreshold || (s.successes === 0 && s.badLinks.size >= this.policy.linkFailureThreshold)) {
      s.openUntil = now + this.policy.cooldownMs;
    }
  }
}

export interface Attempt { sourceId: string; url: string; result: "ok" | ProbeFailure | "skipped_unhealthy" }
export type Resolution = { ok: true; version: TrackVersion; attempts: Attempt[] } | { ok: false; attempts: Attempt[] };

/**
 * Walks the versions of ONE song in priority order and returns the first one that really serves audio.
 * Versions must already be matched to the same song (catalog does that); similar-sounding songs are never substituted here.
 * Unhealthy sources are skipped without a network call, which prevents hammering a failing site.
 */
export async function resolvePlayable(versions: TrackVersion[], deps: { fetchImpl: typeof fetch; health: SourceHealth; now: () => number; maxAttempts?: number }): Promise<Resolution> {
  const attempts: Attempt[] = [];
  const limit = deps.maxAttempts ?? 6;
  let probed = 0;
  for (const version of versions) {
    if (deps.health.isOpen(version.sourceId, deps.now())) { attempts.push({ sourceId: version.sourceId, url: version.url, result: "skipped_unhealthy" }); continue; }
    if (probed >= limit) break;
    probed += 1;
    const result = await probeAudio(version.url, deps.fetchImpl);
    if (result.ok) {
      deps.health.recordSuccess(version.sourceId, deps.now());
      attempts.push({ sourceId: version.sourceId, url: version.url, result: "ok" });
      return { ok: true, version, attempts };
    }
    deps.health.recordFailure(version.sourceId, version.url, result.reason, deps.now());
    attempts.push({ sourceId: version.sourceId, url: version.url, result: result.reason });
  }
  return { ok: false, attempts };
}
