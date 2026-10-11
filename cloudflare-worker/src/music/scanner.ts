import type { Sighting } from "./catalog";
import { isAudioName, isZipName, parseAutoindex, type ListingEntry } from "./listing";
import { jalaliKey, jalaliToIso, type JalaliDate } from "./jalali";
import { dayUrl, type MusicSource } from "./sources";
import { artistKey, parseTrackName, parseZipName } from "./track";

/** A ZIP found while scanning. Contents are read separately (zip.ts) and only on demand / at validation time. */
export interface ArchiveSighting {
  sourceId: string;
  url: string;
  fileName: string;
  collection: string;
  artist: string | null;
  artistKey: string;
  size: number | null;
  folderDate: string;
  folderIso: string;
  modified: string | null;
  discoveredAt: number;
}

export type DayStatus = "ok" | "empty" | "error";

export interface DayScan {
  status: DayStatus;
  /** Machine-readable reason: "forbidden", "timeout", "html_page", "server_error", ... */
  detail: string | null;
  sightings: Sighting[];
  archives: ArchiveSighting[];
  entries: number;
  requests: number;
}

type ListingFetch =
  | { ok: true; entries: ListingEntry[] }
  | { ok: false; status: "empty" | "error"; detail: string };

const MAX_LISTING_BYTES = 2 * 1024 * 1024;
const MAX_SUBFOLDERS = 8;

/** Percent-encodes one path segment exactly once (names contain spaces, Persian letters, brackets). */
export const encodeSegment = (name: string) => encodeURIComponent(name).replace(/[!'()*]/g, (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`);

async function fetchListing(url: string, fetchImpl: typeof fetch, timeoutMs: number): Promise<ListingFetch> {
  let res: Response;
  try {
    res = await fetchImpl(url, { method: "GET", headers: { accept: "text/html", "user-agent": "NovasaMusicBot/1.0" }, redirect: "manual", signal: AbortSignal.timeout(timeoutMs) });
  } catch (e) {
    return { ok: false, status: "error", detail: e instanceof Error && (e.name === "TimeoutError" || e.name === "AbortError") ? "timeout" : "network" };
  }
  // A missing day folder is normal (no uploads that day); it says nothing about the site's health.
  if (res.status === 404 || res.status === 410) { await res.body?.cancel().catch(() => undefined); return { ok: false, status: "empty", detail: "not_found" }; }
  if (res.status === 401 || res.status === 403) { await res.body?.cancel().catch(() => undefined); return { ok: false, status: "error", detail: "forbidden" }; }
  if (res.status >= 500) { await res.body?.cancel().catch(() => undefined); return { ok: false, status: "error", detail: "server_error" }; }
  if (res.status !== 200) { await res.body?.cancel().catch(() => undefined); return { ok: false, status: "error", detail: `status_${res.status}` }; }
  const declared = Number(res.headers.get("content-length") ?? 0);
  if (declared > MAX_LISTING_BYTES) { await res.body?.cancel().catch(() => undefined); return { ok: false, status: "error", detail: "listing_too_large" }; }
  let html: string;
  try { html = (await res.text()).slice(0, MAX_LISTING_BYTES); } catch { return { ok: false, status: "error", detail: "network" }; }
  const parsed = parseAutoindex(html);
  if (parsed.kind === "not_a_listing") return { ok: false, status: "error", detail: parsed.reason };
  return { ok: true, entries: parsed.entries };
}

/**
 * Scans ONE day folder of ONE source (plus first-level sub-folders when the source needs it).
 * The folder date is recorded as-is: it is when the file was filed, not a proven release date.
 */
export async function scanDay(
  source: MusicSource, day: JalaliDate,
  deps: { fetchImpl: typeof fetch; now: () => number; timeoutMs?: number; requestBudget?: number },
): Promise<DayScan> {
  const timeoutMs = deps.timeoutMs ?? 10_000;
  let budget = deps.requestBudget ?? 1 + MAX_SUBFOLDERS;
  const base = dayUrl(source, day);
  const folderDate = jalaliKey(day), folderIso = jalaliToIso(day);
  const sightings: Sighting[] = [];
  const archives: ArchiveSighting[] = [];
  let requests = 0, entries = 0;

  const absorb = (folderUrl: string, list: ListingEntry[]) => {
    for (const entry of list) {
      if (entries >= source.maxEntriesPerDay) return;
      if (entry.isDir) continue;
      const url = `${folderUrl}${encodeSegment(entry.name)}`;
      if (isAudioName(entry.name)) {
        const parsed = parseTrackName(entry.name);
        if (!parsed.title) continue;
        entries += 1;
        sightings.push({
          sourceId: source.id, sourcePriority: source.priority, url, fileName: entry.name,
          artist: parsed.artist, title: parsed.title, quality: parsed.quality, size: entry.size,
          folderDate, folderIso, modified: entry.modified, discoveredAt: deps.now(),
        });
      } else if (source.scanZips && isZipName(entry.name)) {
        const z = parseZipName(entry.name);
        entries += 1;
        archives.push({
          sourceId: source.id, url, fileName: entry.name, collection: z.collection, artist: z.artist, artistKey: artistKey(z.artist),
          size: entry.size, folderDate, folderIso, modified: entry.modified, discoveredAt: deps.now(),
        });
      }
    }
  };

  budget -= 1; requests += 1;
  const root = await fetchListing(base, deps.fetchImpl, timeoutMs);
  if (!root.ok) return { status: root.status, detail: root.detail, sightings, archives, entries, requests };
  absorb(base, root.entries);

  if (source.scanSubfolders) {
    const folders = root.entries.filter((e) => e.isDir).slice(0, MAX_SUBFOLDERS);
    for (const folder of folders) {
      if (budget <= 0 || entries >= source.maxEntriesPerDay) break;
      budget -= 1; requests += 1;
      const url = `${base}${encodeSegment(folder.name)}/`;
      const sub = await fetchListing(url, deps.fetchImpl, timeoutMs);
      // A broken sub-folder must not discard what the day folder itself gave us.
      if (sub.ok) absorb(url, sub.entries);
    }
  }

  const found = sightings.length + archives.length;
  return { status: found > 0 ? "ok" : "empty", detail: found > 0 ? null : "no_media", sightings, archives, entries, requests };
}
