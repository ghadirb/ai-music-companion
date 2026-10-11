import { jalaliKey, jalaliToIso, recentJalaliDays, type JalaliDate } from "./jalali";
import { dayKey, mirrorFetch, validateSource, DEFAULT_SOURCES, type MusicSource } from "./sources";
import { scanDay } from "./scanner";
import { resolvePlayable, SourceHealth, type Attempt } from "./resolve";
import { MusicStore } from "./store";
import { readZipListing, type ZipFailure } from "./zip";
import type { TrackVersion } from "./catalog";

/** Everything an operator may change WITHOUT shipping a new Android build. Stored in D1 (music_settings), not in the APK. */
export interface MusicConfig {
  sources: MusicSource[];
  /** Shown by default (3 months) and the maximum the app may request (6 months). */
  defaultWindowDays: number;
  maxWindowDays: number;
  scanEnabled: boolean;
  /** Minimum minutes between scheduled runs (the Cron Trigger itself fires every 30 minutes). */
  scanEveryMinutes: number;
  /** Outbound requests per run (Workers allow 50 subrequests on the free plan). */
  scanBudget: number;
  /** Days always re-scanned each run to catch late uploads, and how far back late uploads are still looked for. */
  recentDays: number;
  lateRescanDays: number;
  retentionDays: number;
  archiveValidationPerRun: number;
}

export const DEFAULT_CONFIG: MusicConfig = {
  sources: DEFAULT_SOURCES,
  defaultWindowDays: 90, maxWindowDays: 183,
  scanEnabled: true, scanEveryMinutes: 30, scanBudget: 40,
  recentDays: 3, lateRescanDays: 14, retentionDays: 200, archiveValidationPerRun: 3,
};

const CONFIG_KEY = "config";
const LAST_RUN_KEY = "last_run";

const int = (v: unknown, min: number, max: number): number | null => (typeof v === "number" && Number.isInteger(v) && v >= min && v <= max ? v : null);

export type ConfigPatchResult = { ok: true; config: MusicConfig } | { ok: false; errors: string[] };

/** Validates a partial update against the current config. Unknown keys and out-of-range values are rejected, never silently clamped. */
export function applyConfigPatch(current: MusicConfig, patch: unknown): ConfigPatchResult {
  if (!patch || typeof patch !== "object" || Array.isArray(patch)) return { ok: false, errors: ["config must be an object"] };
  const next: MusicConfig = { ...current };
  const errors: string[] = [];
  const numeric: [keyof MusicConfig, number, number][] = [
    ["defaultWindowDays", 7, 366], ["maxWindowDays", 7, 366], ["scanEveryMinutes", 10, 1440], ["scanBudget", 5, 45],
    ["recentDays", 1, 7], ["lateRescanDays", 1, 30], ["retentionDays", 30, 400], ["archiveValidationPerRun", 0, 10],
  ];
  const allowed = new Set<string>(["sources", "scanEnabled", ...numeric.map((n) => n[0])]);
  for (const [key, value] of Object.entries(patch as Record<string, unknown>)) {
    if (!allowed.has(key)) { errors.push(`unknown key: ${key}`); continue; }
    if (key === "scanEnabled") { if (typeof value === "boolean") next.scanEnabled = value; else errors.push("scanEnabled must be boolean"); continue; }
    if (key === "sources") {
      if (!Array.isArray(value) || value.length === 0 || value.length > 12) { errors.push("sources must be a non-empty array (max 12)"); continue; }
      const parsed = value.map(validateSource);
      const bad = parsed.map((s, i) => (s ? -1 : i)).filter((i) => i >= 0);
      if (bad.length) { errors.push(`invalid sources at index: ${bad.join(",")}`); continue; }
      const list = parsed as MusicSource[];
      if (new Set(list.map((s) => s.id)).size !== list.length) { errors.push("duplicate source id"); continue; }
      next.sources = list;
      continue;
    }
    const spec = numeric.find((n) => n[0] === key)!;
    const n = int(value, spec[1], spec[2]);
    if (n === null) errors.push(`${key} must be an integer in ${spec[1]}..${spec[2]}`); else (next as unknown as Record<string, number>)[key] = n;
  }
  if (next.defaultWindowDays > next.maxWindowDays) errors.push("defaultWindowDays must not exceed maxWindowDays");
  if (next.lateRescanDays < next.recentDays) errors.push("lateRescanDays must be >= recentDays");
  return errors.length ? { ok: false, errors } : { ok: true, config: next };
}

export async function loadConfig(store: MusicStore): Promise<MusicConfig> {
  const raw = await store.getSetting(CONFIG_KEY);
  if (!raw) return DEFAULT_CONFIG;
  try {
    const result = applyConfigPatch(DEFAULT_CONFIG, JSON.parse(raw));
    return result.ok ? result.config : DEFAULT_CONFIG; // a corrupted stored config must never take the feature down
  } catch { return DEFAULT_CONFIG; }
}

export async function saveConfig(store: MusicStore, config: MusicConfig, now: number): Promise<void> {
  await store.setSetting(CONFIG_KEY, JSON.stringify(config), now);
}

export const enabledSources = (config: MusicConfig): MusicSource[] =>
  config.sources.filter((s) => s.enabled).sort((a, b) => a.priority - b.priority);

// ------------------------------------------------------------------ scan planning

export interface PlannedDay { source: MusicSource; day: JalaliDate; key: string; reason: "recent" | "first" | "retry" | "late" }

const MIN = 60_000, HOUR = 60 * MIN;

/**
 * Decides WHICH (source, day) folders to fetch now. Months of folders are never re-scanned: a day is fetched again only
 * while it can still receive uploads (recent), after a failure (retry), or once in a while for late uploads.
 */
export function planScan(now: number, config: MusicConfig, known: Map<string, { status: string; scanned_at: number }>, windowDays = config.maxWindowDays): PlannedDay[] {
  const days = recentJalaliDays(now, windowDays);
  const out: PlannedDay[] = [];
  for (const source of enabledSources(config)) {
    days.forEach((day, age) => {
      const key = dayKey(source, day);
      const row = known.get(key);
      const since = row ? now - row.scanned_at : Number.POSITIVE_INFINITY;
      let reason: PlannedDay["reason"] | null = null;
      if (!row) reason = age < config.recentDays ? "recent" : "first";
      else if (row.status === "error" && since >= 30 * MIN) reason = "retry";
      else if (age < config.recentDays && since >= 25 * MIN) reason = "recent";
      else if (age < config.lateRescanDays && since >= 6 * HOUR) reason = "late";
      if (reason) out.push({ source, day, key, reason });
    });
  }
  const rank = { recent: 0, retry: 1, first: 2, late: 3 } as const;
  // Newest days first inside each class; sources keep their priority order.
  return out.sort((a, b) => rank[a.reason] - rank[b.reason] || jalaliKey(b.day).localeCompare(jalaliKey(a.day)) || a.source.priority - b.source.priority);
}

export interface ScanSummary {
  scannedDays: number; requests: number; tracksSeen: number; archivesSeen: number; errors: number;
  skippedSources: string[]; validatedArchives: number; budgetExhausted: boolean;
}

export interface ServiceDeps { fetchImpl: typeof fetch; now: () => number }

/** One scheduled (or admin-triggered) pass: scan what is due, validate some archives, prune old rows. */
export async function runScan(store: MusicStore, rawDeps: ServiceDeps, opts: { force?: boolean; windowDays?: number } = {}): Promise<ScanSummary> {
  const now = rawDeps.now();
  const config = await loadConfig(store);
  const deps: ServiceDeps = { ...rawDeps, fetchImpl: mirrorFetch(config.sources, rawDeps.fetchImpl) };
  const summary: ScanSummary = { scannedDays: 0, requests: 0, tracksSeen: 0, archivesSeen: 0, errors: 0, skippedSources: [], validatedArchives: 0, budgetExhausted: false };
  if (!config.scanEnabled && !opts.force) return summary;
  const lastRun = Number(await store.getSetting(LAST_RUN_KEY)) || 0;
  if (!opts.force && now - lastRun < config.scanEveryMinutes * MIN * 0.9) return summary;
  await store.setSetting(LAST_RUN_KEY, String(now), now);

  const window = Math.min(opts.windowDays ?? config.maxWindowDays, config.maxWindowDays);
  const days = recentJalaliDays(now, window);
  const known = await store.scanDays(enabledSources(config).flatMap((s) => days.map((d) => dayKey(s, d))));
  const plan = planScan(now, config, known, window);

  let budget = config.scanBudget - config.archiveValidationPerRun; // keep room for archive validation requests
  const errorsBySource = new Map<string, number>();
  const CONCURRENCY = 4;
  let cursor = 0;
  const worker = async () => {
    while (budget > 0) {
      const item = plan[cursor++];
      if (!item) return;
      if ((errorsBySource.get(item.source.id) ?? 0) >= 3) { if (!summary.skippedSources.includes(item.source.id)) summary.skippedSources.push(item.source.id); continue; }
      budget -= 1; // reserve; real request count (sub-folders) is reconciled below
      const scan = await scanDay(item.source, item.day, { fetchImpl: deps.fetchImpl, now: deps.now, requestBudget: Math.max(1, Math.min(3, budget + 1)) });
      budget -= Math.max(0, scan.requests - 1);
      summary.requests += scan.requests; summary.scannedDays += 1;
      if (scan.status === "error") { summary.errors += 1; errorsBySource.set(item.source.id, (errorsBySource.get(item.source.id) ?? 0) + 1); }
      else { summary.tracksSeen += scan.sightings.length; summary.archivesSeen += scan.archives.length; }
      // A site-level failure is recorded as an error row so it is retried later, but it never erases earlier results.
      await store.saveDayScan(item.key, item.source.id, jalaliKey(item.day), scan, deps.now());
    }
  };
  await Promise.all(Array.from({ length: CONCURRENCY }, worker));
  summary.budgetExhausted = cursor < plan.length;

  summary.validatedArchives = await validatePendingArchives(store, deps, config.archiveValidationPerRun);

  const cutoff = recentJalaliDays(now, config.retentionDays).at(-1)!;
  await store.prune(jalaliToIso(cutoff), jalaliKey(cutoff));
  return summary;
}

// ------------------------------------------------------------------ ZIP validation

const PERMANENT: ReadonlySet<ZipFailure> = new Set<ZipFailure>([
  "too_small", "no_eocd", "zip64_unsupported", "multi_disk", "corrupt_directory", "truncated", "encrypted",
  "too_many_entries", "too_large", "zip_bomb", "unsafe_path", "not_found",
]);

/** Reads the contents of archives never validated before (plus temporary failures after 12 h). Never downloads an archive. */
export async function validatePendingArchives(store: MusicStore, deps: ServiceDeps, limit: number): Promise<number> {
  if (limit <= 0) return 0;
  const pending = await store.unvalidatedArchives(limit, deps.now() - 12 * HOUR);
  let done = 0;
  for (const archive of pending) {
    await validateArchive(store, deps, archive.url, archive.size);
    done += 1;
  }
  return done;
}

export async function validateArchive(store: MusicStore, rawDeps: ServiceDeps, url: string, size: number | null): Promise<ZipFailure | "ok"> {
  const deps: ServiceDeps = { ...rawDeps, fetchImpl: mirrorFetch((await loadConfig(store)).sources, rawDeps.fetchImpl) };
  if (size === null) { await store.markArchive(url, "rejected", "unknown_size", deps.now()); return "too_small"; }
  const listing = await readZipListing(url, size, deps.fetchImpl);
  if (listing.ok) {
    if (listing.audio.length === 0) { await store.markArchive(url, "rejected", "no_audio", deps.now()); return "ok"; }
    await store.saveArchiveListing(url, listing.audio, deps.now());
    return "ok";
  }
  await store.markArchive(url, PERMANENT.has(listing.reason) ? "rejected" : "bad", listing.reason, deps.now());
  return listing.reason;
}

// ------------------------------------------------------------------ resolving a playable link

/** In-memory per isolate; good enough to stop hammering a failing site inside one burst of requests. */
export const sharedHealth = new SourceHealth();

export interface ResolveOutcome { ok: boolean; version: TrackVersion | null; attempts: Attempt[] }

/** Walks the song's versions in priority order, returns the first that really serves audio, and remembers link health. */
export async function resolveTrack(store: MusicStore, rawDeps: ServiceDeps, trackId: string, exclude: string[], health: SourceHealth = sharedHealth): Promise<ResolveOutcome | null> {
  const deps: ServiceDeps = { ...rawDeps, fetchImpl: mirrorFetch((await loadConfig(store)).sources, rawDeps.fetchImpl) };
  const rows = await store.trackVersions(trackId);
  if (!rows.length) return null;
  const skip = new Set(exclude);
  const versions: TrackVersion[] = rows
    .filter((r) => !skip.has(r.url))
    .map((r) => ({ sourceId: r.source_id, sourcePriority: r.source_priority, url: r.url, fileName: r.file_name, quality: r.quality, size: r.size, folderDate: r.folder_date, modified: r.modified }));
  const result = await resolvePlayable(versions, { fetchImpl: deps.fetchImpl, health, now: deps.now });
  const LINK_FAILURES = new Set(["not_found", "forbidden", "not_audio", "bad_status"]);
  for (const attempt of result.attempts) {
    if (attempt.result === "ok") await store.markVersion(attempt.url, "ok", deps.now());
    else if (LINK_FAILURES.has(attempt.result)) await store.markVersion(attempt.url, "bad", deps.now()); // site-level failures are NOT blamed on the link
  }
  return { ok: result.ok, version: result.ok ? result.version : null, attempts: result.attempts };
}
