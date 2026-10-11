import { mergeSightings, type CatalogTrack } from "./catalog";
import type { ArchiveSighting, DayScan } from "./scanner";
import type { Sighting } from "./catalog";
import { normalizeText } from "./track";
import type { ZipEntry } from "./zip";

/** The slice of the D1 API this module needs. Real D1 and the test adapter (node:sqlite) both satisfy it. */
export interface D1Statement {
  bind(...values: unknown[]): D1Statement;
  all<T = Record<string, unknown>>(): Promise<{ results: T[] }>;
  first<T = Record<string, unknown>>(): Promise<T | null>;
  run(): Promise<unknown>;
}
export interface D1Like {
  prepare(sql: string): D1Statement;
  batch(statements: D1Statement[]): Promise<unknown[]>;
}

export type LinkStatus = "unknown" | "ok" | "bad";

export interface TrackRow {
  id: string; key: string; artist: string | null; title: string;
  first_folder_date: string; first_folder_iso: string; latest_modified: string | null; first_discovered_at: number;
}
export interface VersionRow {
  url: string; track_id: string; source_id: string; source_priority: number; file_name: string;
  quality: number | null; size: number | null; folder_date: string; modified: string | null; status: LinkStatus; checked_at: number | null;
}
export interface ArchiveRow {
  url: string; source_id: string; file_name: string; collection: string; artist: string | null; artist_key: string;
  size: number | null; folder_date: string; folder_iso: string; discovered_at: number; status: LinkStatus | "rejected"; detail: string | null; checked_at: number | null; entry_count: number | null;
}
export interface ArtistRow { artist_key: string; artist: string | null; collections: number; latest_iso: string }
export interface ScanDayRow { day_key: string; status: string; scanned_at: number }

const CHUNK = 40;
const chunks = <T>(list: T[], size = CHUNK): T[][] => { const out: T[][] = []; for (let i = 0; i < list.length; i += size) out.push(list.slice(i, i + size)); return out; };

/** Escapes LIKE wildcards so a user typing "%" or "_" cannot widen the query. */
const likeToken = (token: string) => `%${token.replace(/[\\%_]/g, (c) => `\\${c}`)}%`;

export class MusicStore {
  constructor(private readonly db: D1Like) {}

  // ---------------------------------------------------------------- writes (scanner)

  /** Stores everything one (source, day) scan found. Idempotent: re-scanning a day never duplicates rows. */
  async saveDayScan(dayKey: string, sourceId: string, folderDate: string, scan: DayScan, now: number): Promise<void> {
    const statements: D1Statement[] = [];
    for (const track of mergeSightings(scan.sightings)) statements.push(...this.trackStatements(track));
    for (const archive of scan.archives) statements.push(this.archiveStatement(archive));
    statements.push(
      this.db.prepare(
        `INSERT INTO music_scan_days (day_key, source_id, folder_date, status, detail, entries, scanned_at) VALUES (?,?,?,?,?,?,?)
         ON CONFLICT(day_key) DO UPDATE SET status=excluded.status, detail=excluded.detail, entries=excluded.entries, scanned_at=excluded.scanned_at`,
      ).bind(dayKey, sourceId, folderDate, scan.status, scan.detail, scan.entries, now),
    );
    for (const group of chunks(statements)) await this.db.batch(group);
  }

  private trackStatements(t: CatalogTrack): D1Statement[] {
    const displayPriority = Math.min(...t.versions.map((v) => v.sourcePriority));
    const out: D1Statement[] = [
      this.db.prepare(
        `INSERT INTO music_tracks (id,key,artist,title,search,first_folder_date,first_folder_iso,latest_modified,first_discovered_at,display_priority)
         VALUES (?,?,?,?,?,?,?,?,?,?)
         ON CONFLICT(id) DO UPDATE SET
           first_folder_date = CASE WHEN excluded.first_folder_iso < first_folder_iso THEN excluded.first_folder_date ELSE first_folder_date END,
           first_folder_iso  = MIN(first_folder_iso, excluded.first_folder_iso),
           latest_modified   = CASE WHEN COALESCE(excluded.latest_modified,'') > COALESCE(latest_modified,'') THEN excluded.latest_modified ELSE latest_modified END,
           first_discovered_at = MIN(first_discovered_at, excluded.first_discovered_at),
           artist  = CASE WHEN excluded.display_priority < display_priority OR (artist IS NULL AND excluded.artist IS NOT NULL) THEN excluded.artist ELSE artist END,
           title   = CASE WHEN excluded.display_priority < display_priority OR (artist IS NULL AND excluded.artist IS NOT NULL) THEN excluded.title ELSE title END,
           search  = CASE WHEN excluded.display_priority < display_priority OR (artist IS NULL AND excluded.artist IS NOT NULL) THEN excluded.search ELSE search END,
           display_priority = MIN(display_priority, excluded.display_priority)`,
      ).bind(t.id, t.key, t.artist, t.title, normalizeText(`${t.artist ?? ""} ${t.title}`), t.firstFolderDate, t.firstFolderIso, t.latestModified, t.firstDiscoveredAt, displayPriority),
    ];
    for (const v of t.versions) {
      out.push(
        this.db.prepare(
          `INSERT INTO music_versions (url,track_id,source_id,source_priority,file_name,quality,size,folder_date,modified,status,discovered_at)
           VALUES (?,?,?,?,?,?,?,?,?,'unknown',?)
           ON CONFLICT(url) DO UPDATE SET track_id=excluded.track_id, source_priority=excluded.source_priority, quality=excluded.quality, size=excluded.size, modified=excluded.modified`,
        ).bind(v.url, t.id, v.sourceId, v.sourcePriority, v.fileName, v.quality, v.size, v.folderDate, v.modified, t.firstDiscoveredAt),
      );
    }
    return out;
  }

  private archiveStatement(a: ArchiveSighting): D1Statement {
    return this.db.prepare(
      `INSERT INTO music_archives (url,source_id,file_name,collection,artist,artist_key,size,folder_date,folder_iso,discovered_at,status)
       VALUES (?,?,?,?,?,?,?,?,?,?,'unknown')
       ON CONFLICT(url) DO UPDATE SET collection=excluded.collection, artist=excluded.artist, artist_key=excluded.artist_key, size=excluded.size`,
    ).bind(a.url, a.sourceId, a.fileName, a.collection, a.artist, a.artistKey, a.size, a.folderDate, a.folderIso, a.discoveredAt);
  }

  // ---------------------------------------------------------------- scan bookkeeping

  async scanDays(dayKeys: string[]): Promise<Map<string, ScanDayRow>> {
    const map = new Map<string, ScanDayRow>();
    for (const group of chunks(dayKeys, 80)) {
      const { results } = await this.db.prepare(`SELECT day_key,status,scanned_at FROM music_scan_days WHERE day_key IN (${group.map(() => "?").join(",")})`).bind(...group).all<ScanDayRow>();
      for (const row of results) map.set(row.day_key, row);
    }
    return map;
  }

  async lastScanAt(): Promise<number | null> {
    const row = await this.db.prepare(`SELECT MAX(scanned_at) AS at FROM music_scan_days`).first<{ at: number | null }>();
    return row?.at ?? null;
  }

  async counts(): Promise<{ tracks: number; versions: number; archives: number; badVersions: number; errorDays: number }> {
    const one = async (sql: string) => (await this.db.prepare(sql).first<{ n: number }>())?.n ?? 0;
    return {
      tracks: await one(`SELECT COUNT(*) AS n FROM music_tracks`),
      versions: await one(`SELECT COUNT(*) AS n FROM music_versions`),
      archives: await one(`SELECT COUNT(*) AS n FROM music_archives`),
      badVersions: await one(`SELECT COUNT(*) AS n FROM music_versions WHERE status='bad'`),
      errorDays: await one(`SELECT COUNT(*) AS n FROM music_scan_days WHERE status='error'`),
    };
  }

  // ---------------------------------------------------------------- settings

  async getSetting(key: string): Promise<string | null> {
    return (await this.db.prepare(`SELECT value FROM music_settings WHERE key=?`).bind(key).first<{ value: string }>())?.value ?? null;
  }
  async setSetting(key: string, value: string, now: number): Promise<void> {
    await this.db.prepare(`INSERT INTO music_settings (key,value,updated_at) VALUES (?,?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at`).bind(key, value, now).run();
  }

  // ---------------------------------------------------------------- reads (API)

  /**
   * Newest first by the most reliable date we have; NEVER by source priority. Tracks whose every link is known-bad are hidden.
   * `query` is normalised like the stored text, every word must match (artist or title).
   */
  async listTracks(opts: { sinceIso: string; query: string; limit: number; offset: number; badRetryBefore: number }): Promise<{ tracks: (TrackRow & { versions: VersionRow[] })[]; total: number }> {
    const words = normalizeText(opts.query).split(" ").filter(Boolean).slice(0, 6);
    // A query made only of punctuation ("%", "_", "!!") has nothing searchable: it matches nothing instead of everything.
    if (opts.query.trim() && words.length === 0) return { tracks: [], total: 0 };
    // A link marked bad is trusted for 24 h; after that the song is offered again so a transient 403 cannot hide it forever.
    const where = [`t.first_folder_iso >= ?`, `EXISTS (SELECT 1 FROM music_versions v WHERE v.track_id = t.id AND (v.status <> 'bad' OR COALESCE(v.checked_at,0) < ?))`, ...words.map(() => `t.search LIKE ? ESCAPE '\\'`)];
    const params: unknown[] = [opts.sinceIso, opts.badRetryBefore, ...words.map(likeToken)];
    const total = (await this.db.prepare(`SELECT COUNT(*) AS n FROM music_tracks t WHERE ${where.join(" AND ")}`).bind(...params).first<{ n: number }>())?.n ?? 0;
    const { results } = await this.db.prepare(
      `SELECT t.id,t.key,t.artist,t.title,t.first_folder_date,t.first_folder_iso,t.latest_modified,t.first_discovered_at FROM music_tracks t
       WHERE ${where.join(" AND ")} ORDER BY t.first_folder_iso DESC, COALESCE(t.latest_modified,'') DESC, t.key ASC LIMIT ? OFFSET ?`,
    ).bind(...params, opts.limit, opts.offset).all<TrackRow>();
    const versions = await this.versionsFor(results.map((r) => r.id));
    return { tracks: results.map((r) => ({ ...r, versions: versions.get(r.id) ?? [] })), total };
  }

  async versionsFor(trackIds: string[]): Promise<Map<string, VersionRow[]>> {
    const map = new Map<string, VersionRow[]>();
    for (const group of chunks(trackIds, 80)) {
      const { results } = await this.db.prepare(
        `SELECT url,track_id,source_id,source_priority,file_name,quality,size,folder_date,modified,status,checked_at FROM music_versions
         WHERE track_id IN (${group.map(() => "?").join(",")}) ORDER BY source_priority ASC, COALESCE(quality, 100000) DESC, url ASC`,
      ).bind(...group).all<VersionRow>();
      for (const row of results) { const list = map.get(row.track_id); if (list) list.push(row); else map.set(row.track_id, [row]); }
    }
    return map;
  }

  async trackVersions(trackId: string): Promise<VersionRow[]> {
    return (await this.versionsFor([trackId])).get(trackId) ?? [];
  }

  async markVersion(url: string, status: LinkStatus, now: number): Promise<void> {
    await this.db.prepare(`UPDATE music_versions SET status=?, checked_at=? WHERE url=?`).bind(status, now, url).run();
  }

  /** Singers that have at least one usable collection, most recently active first. */
  async listArtists(): Promise<ArtistRow[]> {
    const { results } = await this.db.prepare(
      `SELECT artist_key, MAX(artist) AS artist, COUNT(*) AS collections, MAX(folder_iso) AS latest_iso FROM music_archives
       WHERE artist_key <> '' AND status <> 'rejected' GROUP BY artist_key ORDER BY latest_iso DESC, artist_key ASC LIMIT 500`,
    ).all<ArtistRow>();
    return results;
  }

  async listCollections(artistKey: string): Promise<ArchiveRow[]> {
    const { results } = await this.db.prepare(
      `SELECT url,source_id,file_name,collection,artist,artist_key,size,folder_date,folder_iso,discovered_at,status,detail,checked_at,entry_count FROM music_archives
       WHERE artist_key = ? AND status <> 'rejected' ORDER BY folder_iso DESC, file_name ASC LIMIT 200`,
    ).bind(artistKey).all<ArchiveRow>();
    return results;
  }

  async getArchive(url: string): Promise<ArchiveRow | null> {
    return await this.db.prepare(
      `SELECT url,source_id,file_name,collection,artist,artist_key,size,folder_date,folder_iso,discovered_at,status,detail,checked_at,entry_count FROM music_archives WHERE url = ?`,
    ).bind(url).first<ArchiveRow>();
  }

  /** Archives never validated (newest first) plus temporary failures last tried before `retryBefore`, for the scheduled pass. */
  async unvalidatedArchives(limit: number, retryBefore: number): Promise<ArchiveRow[]> {
    const { results } = await this.db.prepare(
      `SELECT url,source_id,file_name,collection,artist,artist_key,size,folder_date,folder_iso,discovered_at,status,detail,checked_at,entry_count FROM music_archives
       WHERE checked_at IS NULL OR (status = 'bad' AND checked_at < ?) ORDER BY checked_at IS NOT NULL, folder_iso DESC LIMIT ?`,
    ).bind(retryBefore, limit).all<ArchiveRow>();
    return results;
  }

  async saveArchiveListing(url: string, audio: ZipEntry[], now: number): Promise<void> {
    const statements: D1Statement[] = [
      this.db.prepare(`DELETE FROM music_archive_entries WHERE archive_url = ?`).bind(url),
      this.db.prepare(`UPDATE music_archives SET status='ok', detail=NULL, checked_at=?, entry_count=? WHERE url=?`).bind(now, audio.length, url),
      ...audio.map((e) => this.db.prepare(`INSERT OR REPLACE INTO music_archive_entries (archive_url,path,name,size) VALUES (?,?,?,?)`).bind(url, e.path, e.name, e.size)),
    ];
    for (const group of chunks(statements, 60)) await this.db.batch(group);
  }

  /** `rejected` archives (unsafe/encrypted/bomb) are hidden from the UI; `bad` ones (temporary network trouble) stay visible. */
  async markArchive(url: string, status: LinkStatus | "rejected", detail: string | null, now: number): Promise<void> {
    await this.db.prepare(`UPDATE music_archives SET status=?, detail=?, checked_at=? WHERE url=?`).bind(status, detail, now, url).run();
  }

  async archiveEntries(url: string): Promise<{ path: string; name: string; size: number }[]> {
    const { results } = await this.db.prepare(`SELECT path,name,size FROM music_archive_entries WHERE archive_url = ? ORDER BY path ASC`).bind(url).all<{ path: string; name: string; size: number }>();
    return results;
  }

  // ---------------------------------------------------------------- retention

  /**
   * Drops tracks/archives filed before the cutoff. The same day is passed in both calendars because folder dates are
   * stored Jalali (scan_days) and Gregorian (tracks/archives); comparing across calendars would delete everything.
   */
  async prune(olderThanIso: string, olderThanJalali: string): Promise<void> {
    await this.db.batch([
      this.db.prepare(`DELETE FROM music_versions WHERE track_id IN (SELECT id FROM music_tracks WHERE first_folder_iso < ?)`).bind(olderThanIso),
      this.db.prepare(`DELETE FROM music_tracks WHERE first_folder_iso < ?`).bind(olderThanIso),
      this.db.prepare(`DELETE FROM music_archive_entries WHERE archive_url IN (SELECT url FROM music_archives WHERE folder_iso < ?)`).bind(olderThanIso),
      this.db.prepare(`DELETE FROM music_archives WHERE folder_iso < ?`).bind(olderThanIso),
      this.db.prepare(`DELETE FROM music_scan_days WHERE folder_date < ?`).bind(olderThanJalali),
    ]);
  }
}

export type { Sighting };
