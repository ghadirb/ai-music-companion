import { trackKey } from "./track";

/** One occurrence of a file on one source, as found by the scanner. */
export interface Sighting {
  sourceId: string;
  sourcePriority: number;
  url: string;
  fileName: string;
  artist: string | null;
  title: string;
  /** kbps if the file name states it, else null (unmarked files are the original/highest quality variant). */
  quality: number | null;
  size: number | null;
  /** Jalali folder date "1405-07-19" and its Gregorian equivalent. This is when the file was FILED, not proven release time. */
  folderDate: string;
  folderIso: string;
  /** Server-reported mtime without timezone; only a tie-breaker inside one day. */
  modified: string | null;
  discoveredAt: number;
}

export interface TrackVersion {
  sourceId: string;
  sourcePriority: number;
  url: string;
  fileName: string;
  quality: number | null;
  size: number | null;
  folderDate: string;
  modified: string | null;
}

export interface CatalogTrack {
  id: string;
  key: string;
  artist: string | null;
  title: string;
  /** Earliest folder date this song was seen in on any source: the best available "release date" proxy. */
  firstFolderDate: string;
  firstFolderIso: string;
  latestModified: string | null;
  firstDiscoveredAt: number;
  versions: TrackVersion[];
}

/** 64-bit FNV-1a as 16 hex chars: stable, synchronous, good enough for ids (not for security). */
export function stableId(input: string): string {
  let h1 = 0x811c9dc5, h2 = 0x01000193 ^ 0xdeadbeef;
  for (let i = 0; i < input.length; i += 1) {
    const c = input.charCodeAt(i);
    h1 = Math.imul(h1 ^ c, 0x01000193) >>> 0;
    h2 = Math.imul(h2 ^ c, 0x85ebca6b) >>> 0;
  }
  return h1.toString(16).padStart(8, "0") + h2.toString(16).padStart(8, "0");
}

const qualityRank = (q: number | null) => (q === null ? Number.POSITIVE_INFINITY : q);

/** Playback order for versions of ONE song: source priority first, then best quality within a source. */
export function compareVersions(a: TrackVersion, b: TrackVersion): number {
  if (a.sourcePriority !== b.sourcePriority) return a.sourcePriority - b.sourcePriority;
  const qa = qualityRank(a.quality), qb = qualityRank(b.quality);
  if (qa !== qb) return qb > qa ? 1 : -1;
  return a.url < b.url ? -1 : a.url > b.url ? 1 : 0;
}

/** Groups sightings of the same song (same normalized artists + title) into one entry that keeps every source's link. */
export function mergeSightings(sightings: Sighting[]): CatalogTrack[] {
  const groups = new Map<string, Sighting[]>();
  for (const s of sightings) {
    const key = trackKey(s.artist, s.title);
    if (key === "|" || key.endsWith("|")) continue; // nothing identifiable
    const list = groups.get(key);
    if (list) list.push(s); else groups.set(key, [s]);
  }
  const tracks: CatalogTrack[] = [];
  for (const [key, list] of groups) {
    const seenUrls = new Set<string>();
    const versions: TrackVersion[] = [];
    for (const s of list) {
      if (seenUrls.has(s.url)) continue;
      seenUrls.add(s.url);
      versions.push({ sourceId: s.sourceId, sourcePriority: s.sourcePriority, url: s.url, fileName: s.fileName, quality: s.quality, size: s.size, folderDate: s.folderDate, modified: s.modified });
    }
    versions.sort(compareVersions);
    // Display text comes from the most trusted source that has an artist, falling back to any sighting.
    const byPriority = [...list].sort((a, b) => a.sourcePriority - b.sourcePriority);
    const display = byPriority.find((s) => s.artist) ?? byPriority[0];
    const earliest = [...list].sort((a, b) => (a.folderIso < b.folderIso ? -1 : a.folderIso > b.folderIso ? 1 : 0))[0];
    const modifieds = list.map((s) => s.modified).filter((m): m is string => !!m).sort();
    tracks.push({
      id: stableId(key), key, artist: display.artist, title: display.title,
      firstFolderDate: earliest.folderDate, firstFolderIso: earliest.folderIso,
      latestModified: modifieds.length ? modifieds[modifieds.length - 1] : null,
      firstDiscoveredAt: Math.min(...list.map((s) => s.discoveredAt)),
      versions,
    });
  }
  return sortCatalog(tracks);
}

/** Newest first by the most reliable date we have (folder date, then server mtime). Never by source priority. */
export function sortCatalog(tracks: CatalogTrack[]): CatalogTrack[] {
  return [...tracks].sort((a, b) => {
    if (a.firstFolderIso !== b.firstFolderIso) return a.firstFolderIso < b.firstFolderIso ? 1 : -1;
    const ma = a.latestModified ?? "", mb = b.latestModified ?? "";
    if (ma !== mb) return ma < mb ? 1 : -1;
    return a.key < b.key ? -1 : a.key > b.key ? 1 : 0;
  });
}
