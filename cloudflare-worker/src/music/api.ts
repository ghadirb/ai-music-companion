import type { Claims } from "../auth";
import type { Env } from "../config";
import type { Counter } from "../quota";
import { json, readJson, sha256Hex, timingSafeEqual } from "../util";
import { jalaliKey, jalaliToIso, recentJalaliDays } from "./jalali";
import { applyConfigPatch, enabledSources, loadConfig, resolveTrack, runScan, saveConfig, validateArchive, type MusicConfig } from "./service";
import { MusicStore, type ArchiveRow, type D1Like, type TrackRow, type VersionRow } from "./store";

export interface MusicDeps { counter: Counter | null; fetchImpl: typeof fetch; now: () => number }

const DAY_SECONDS = 86_400;
const TRACK_ID = /^[0-9a-f]{16}$/;
const ARTIST_KEY = /^[^\u0000-\u001f]{1,120}$/;

export function storeFor(env: Env): MusicStore | null {
  return env.MUSIC_DB ? new MusicStore(env.MUSIC_DB as unknown as D1Like) : null;
}

const dayStamp = (now: number) => new Date(now).toISOString().slice(0, 10);

function sourceNames(config: MusicConfig): Map<string, string> {
  return new Map(config.sources.map((s) => [s.id, s.name]));
}

function trackJson(t: TrackRow & { versions: VersionRow[] }, names: Map<string, string>) {
  return {
    id: t.id, artist: t.artist, title: t.title,
    // The folder date is when the file was FILED on the source. It is exposed under its real name; the app labels it accordingly.
    folderDate: t.first_folder_date, folderIso: t.first_folder_iso, dateKind: "folder",
    versions: t.versions.map((v) => ({
      url: v.url, sourceId: v.source_id, source: names.get(v.source_id) ?? v.source_id, quality: v.quality, size: v.size, status: v.status,
    })),
  };
}

function archiveJson(a: ArchiveRow, names: Map<string, string>) {
  return {
    url: a.url, sourceId: a.source_id, source: names.get(a.source_id) ?? a.source_id, fileName: a.file_name, collection: a.collection,
    artist: a.artist, artistKey: a.artist_key, size: a.size, folderDate: a.folder_date, folderIso: a.folder_iso,
    status: a.status, entryCount: a.entry_count,
  };
}

// ------------------------------------------------------------------ user-facing API (anonymous session + generic rate limit already applied)

export async function handleMusic(path: string, request: Request, env: Env, deps: MusicDeps, claims: Claims): Promise<Response> {
  const store = storeFor(env);
  if (!store) return json({ error: "music_not_configured" }, 503);
  const config = await loadConfig(store);
  const names = sourceNames(config);

  switch (path) {
    case "/v1/music/config":
      return json({
        defaultWindowDays: config.defaultWindowDays, maxWindowDays: config.maxWindowDays, collectionWindowDays: config.collectionWindowDays,
        windowOptions: [...new Set([Math.min(90, config.maxWindowDays), Math.min(180, config.maxWindowDays)])],
        sources: enabledSources(config).map((s) => ({ id: s.id, name: s.name, priority: s.priority, zips: s.scanZips })),
        lastScanAt: await store.lastScanAt(),
      });

    case "/v1/music/tracks": {
      const body = await readJson<{ windowDays?: unknown; q?: unknown; offset?: unknown; limit?: unknown }>(request, 2 * 1024);
      if (!body.ok) return bodyError(body.error);
      const windowDays = typeof body.value.windowDays === "number" && Number.isInteger(body.value.windowDays) && body.value.windowDays >= 1
        ? Math.min(body.value.windowDays, config.maxWindowDays) : config.defaultWindowDays;
      const q = typeof body.value.q === "string" ? body.value.q.slice(0, 80) : "";
      const offset = clampInt(body.value.offset, 0, 5000, 0);
      const limit = clampInt(body.value.limit, 1, 50, 30);
      const since = jalaliToIso(recentJalaliDays(deps.now(), windowDays).at(-1)!);
      const page = await store.listTracks({ sinceIso: since, query: q, limit, offset, badRetryBefore: deps.now() - DAY_SECONDS * 1000 });
      return json({
        tracks: page.tracks.map((t) => trackJson(t, names)), total: page.total, windowDays,
        nextOffset: offset + page.tracks.length < page.total ? offset + page.tracks.length : null,
        lastScanAt: await store.lastScanAt(),
      });
    }

    case "/v1/music/artists":
      return json({ artists: (await store.listArtists()).map((a) => ({ artistKey: a.artist_key, artist: a.artist, collections: a.collections, latestIso: a.latest_iso })) });

    case "/v1/music/collections": {
      const body = await readJson<{ artistKey?: unknown }>(request, 1024);
      if (!body.ok) return bodyError(body.error);
      if (typeof body.value.artistKey !== "string" || !ARTIST_KEY.test(body.value.artistKey)) return json({ error: "invalid_artist" }, 400);
      return json({ collections: (await store.listCollections(body.value.artistKey)).map((a) => archiveJson(a, names)) });
    }

    case "/v1/music/archive": {
      const body = await readJson<{ url?: unknown }>(request, 2 * 1024);
      if (!body.ok) return bodyError(body.error);
      if (typeof body.value.url !== "string" || body.value.url.length > 2048) return json({ error: "invalid_url" }, 400);
      // Only URLs the scanner itself discovered are ever fetched: the API is not an open proxy / SSRF vector.
      let archive = await store.getArchive(body.value.url);
      if (!archive || archive.status === "rejected") return json({ error: "not_found" }, 404);
      if (archive.checked_at === null) {
        const cap = await deps.counter!.incrementIfBelow(`music:av:${dayStamp(deps.now())}:${claims.sub}`, 40, DAY_SECONDS + 3600);
        if (!cap.allowed) return json({ error: "daily_limit_reached" }, 429, { "retry-after": "3600" });
        await validateArchive(store, { fetchImpl: deps.fetchImpl, now: deps.now }, archive.url, archive.size);
        archive = await store.getArchive(archive.url);
        if (!archive || archive.status === "rejected") return json({ error: "archive_unavailable", detail: archive?.detail ?? null }, 422);
      }
      const entries = await store.archiveEntries(archive.url);
      return json({ archive: archiveJson(archive, names), entries });
    }

    case "/v1/music/resolve": {
      const body = await readJson<{ trackId?: unknown; exclude?: unknown }>(request, 4 * 1024);
      if (!body.ok) return bodyError(body.error);
      if (typeof body.value.trackId !== "string" || !TRACK_ID.test(body.value.trackId)) return json({ error: "invalid_track" }, 400);
      const exclude = Array.isArray(body.value.exclude) ? body.value.exclude.filter((u): u is string => typeof u === "string" && u.length <= 2048).slice(0, 12) : [];
      // Each resolve may probe several remote servers, so it has its own daily cap on top of the per-minute limit.
      const cap = await deps.counter!.incrementIfBelow(`music:rs:${dayStamp(deps.now())}:${claims.sub}`, 300, DAY_SECONDS + 3600);
      if (!cap.allowed) return json({ error: "daily_limit_reached" }, 429, { "retry-after": "3600" });
      const outcome = await resolveTrack(store, { fetchImpl: deps.fetchImpl, now: deps.now }, body.value.trackId, exclude);
      if (!outcome) return json({ error: "not_found" }, 404);
      const attempts = outcome.attempts.map((a) => ({ sourceId: a.sourceId, result: a.result }));
      if (!outcome.ok || !outcome.version) return json({ ok: false, attempts }, 200);
      const v = outcome.version;
      return json({ ok: true, version: { url: v.url, sourceId: v.sourceId, source: names.get(v.sourceId) ?? v.sourceId, quality: v.quality, size: v.size }, attempts });
    }

    default:
      return json({ error: "not_found" }, 404);
  }
}

// ------------------------------------------------------------------ admin API (separate secret, never shipped in the APK)

async function adminAllowed(request: Request, env: Env, deps: MusicDeps): Promise<Response | null> {
  if (!env.MUSIC_ADMIN_TOKEN || env.MUSIC_ADMIN_TOKEN.length < 24) return json({ error: "admin_disabled" }, 503);
  const ip = (await sha256Hex(`${request.headers.get("cf-connecting-ip") ?? "unknown"}|${env.JWT_SIGNING_SECRET}`)).slice(0, 32);
  const hour = Math.floor(deps.now() / 3_600_000);
  const attempts = await deps.counter!.get(`music:adminfail:${hour}:${ip}`);
  if (attempts >= 10) return json({ error: "too_many_attempts" }, 429, { "retry-after": "3600" });
  const given = request.headers.get("authorization")?.replace(/^Bearer\s+/i, "") ?? "";
  // Compare hashes: fixed length, so neither the token length nor its prefix leaks through timing.
  const ok = timingSafeEqual(await sha256Hex(given), await sha256Hex(env.MUSIC_ADMIN_TOKEN));
  if (!ok) {
    await deps.counter!.incrementIfBelow(`music:adminfail:${hour}:${ip}`, 1000, 7200);
    return json({ error: "unauthorized" }, 401);
  }
  return null;
}

export async function handleMusicAdmin(path: string, request: Request, env: Env, deps: MusicDeps): Promise<Response> {
  const denied = await adminAllowed(request, env, deps);
  if (denied) return denied;
  const store = storeFor(env);
  if (!store) return json({ error: "music_not_configured" }, 503);
  const body = await readJson<Record<string, unknown>>(request, 32 * 1024);
  if (!body.ok) return bodyError(body.error);

  switch (path) {
    case "/v1/music/admin/config": {
      const current = await loadConfig(store);
      if (body.value.config === undefined) return json({ config: current });
      const next = applyConfigPatch(current, body.value.config);
      if (!next.ok) return json({ error: "invalid_config", details: next.errors }, 400);
      await saveConfig(store, next.config, deps.now());
      return json({ config: next.config });
    }
    case "/v1/music/admin/scan": {
      const windowDays = typeof body.value.windowDays === "number" ? body.value.windowDays : undefined;
      const summary = await runScan(store, { fetchImpl: deps.fetchImpl, now: deps.now }, { force: true, windowDays });
      return json({ summary });
    }
    case "/v1/music/admin/status": {
      const config = await loadConfig(store);
      const today = recentJalaliDays(deps.now(), 1)[0];
      return json({ today: jalaliKey(today), lastScanAt: await store.lastScanAt(), counts: await store.counts(), sources: config.sources.map((s) => ({ id: s.id, enabled: s.enabled, priority: s.priority })) });
    }
    default:
      return json({ error: "not_found" }, 404);
  }
}

// ------------------------------------------------------------------ helpers

function clampInt(value: unknown, min: number, max: number, fallback: number): number {
  return typeof value === "number" && Number.isInteger(value) && value >= min && value <= max ? value : fallback;
}

function bodyError(error: "unsupported_media_type" | "payload_too_large" | "invalid_json"): Response {
  return json({ error }, error === "unsupported_media_type" ? 415 : error === "payload_too_large" ? 413 : 400);
}
