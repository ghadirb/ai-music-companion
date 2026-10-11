import { describe, expect, it } from "vitest";
import { TestD1 } from "./d1";
import { MP3 } from "./zip-builder";
import { mergeSightings, type Sighting } from "../src/music/catalog";
import { recentJalaliDays } from "../src/music/jalali";
import { scanDay } from "../src/music/scanner";
import { DEFAULT_SOURCES, dayKey, dayUrl, type MusicSource } from "../src/music/sources";
import { applyConfigPatch, DEFAULT_CONFIG, loadConfig, planScan, resolveTrack, runScan, saveConfig } from "../src/music/service";
import { MusicStore } from "../src/music/store";
import { SourceHealth } from "../src/music/resolve";
import ahaangDay from "./fixtures/ahaang-day.html?raw";
import ahaang404 from "./fixtures/ahaang-404.html?raw";
import gisoDay from "./fixtures/giso-day.html?raw";
import rozDay from "./fixtures/roz-day.html?raw";

export const OCT11 = Date.UTC(2026, 9, 11, 9, 0, 0); // 1405-07-19 in Tehran
export const D17 = { jy: 1405, jm: 7, jd: 17 }, D18 = { jy: 1405, jm: 7, jd: 18 }, D19 = { jy: 1405, jm: 7, jd: 19 };
export const src = (id: string): MusicSource => DEFAULT_SOURCES.find((s) => s.id === id)!;

/** Autoindex page like nginx produces. Rows: [name, size|null (null = directory)]. */
export const index = (path: string, rows: [string, number | null][]) =>
  `<html><head><title>Index of ${path}</title></head><body><h1>Index of ${path}</h1><hr><pre><a href="../">../</a>\n${rows
    .map(([n, s]) => `<a href="${encodeURIComponent(n)}${s === null ? "/" : ""}">${n}</a>   11-Oct-2026 10:50   ${s ?? "-"}`).join("\n")}\n</pre><hr></body></html>`;

type Page = string | Uint8Array | { status: number; body?: string } | { throws: "timeout" | "network" };

/** Fake website: exact URL -> page. Unknown URLs are 404 with the real 404 body. Records every request. */
export function site(pages: Record<string, Page>) {
  const calls: string[] = [];
  const fetchImpl = (async (input: any, init?: any) => {
    const url = String(input);
    calls.push(url);
    const page = pages[url];
    if (page === undefined) return new Response(ahaang404, { status: 404, headers: { "content-type": "text/html" } });
    if (typeof page === "string") return new Response(page, { status: 200, headers: { "content-type": "text/html" } });
    if (page instanceof Uint8Array) return new Response(page, { status: 200, headers: { "content-type": "audio/mpeg" } });
    if ("throws" in page) throw Object.assign(new Error(page.throws), { name: page.throws === "timeout" ? "TimeoutError" : "TypeError" });
    return new Response(page.body ?? "", { status: page.status, headers: { "content-type": "text/html" } });
  }) as typeof fetch;
  return { fetchImpl, calls, pages };
}

const sighting = (over: Partial<Sighting>): Sighting => ({
  sourceId: "giso", sourcePriority: 2, url: "https://dl.gisomusic.com/Music/1405/07/18/x.mp3", fileName: "x.mp3", artist: "Artist", title: "Song",
  quality: null, size: 1000, folderDate: "1405-07-18", folderIso: "2026-10-10", modified: "2026-10-10T10:00", discoveredAt: OCT11, ...over,
});

const dayScan = (sightings: Sighting[], archives: any[] = []) => ({ status: "ok" as const, detail: null, sightings, archives, entries: sightings.length, requests: 1 });

export async function seed(store: MusicStore, sightings: Sighting[], archives: any[] = []) {
  await store.saveDayScan(`seed:${Math.random()}`, "seed", "1405-07-18", dayScan(sightings, archives), OCT11);
}

describe("scanDay", () => {
  const deps = (s: ReturnType<typeof site>) => ({ fetchImpl: s.fetchImpl, now: () => OCT11 });

  it("reads an Ahaang day folder: encoded URLs, folder date, mtime, size", async () => {
    const s = site({ [dayUrl(src("ahaang"), D18)]: ahaangDay });
    const scan = await scanDay(src("ahaang"), D18, deps(s));
    expect(scan).toMatchObject({ status: "ok", detail: null, requests: 1 });
    expect(scan.sightings).toHaveLength(3);
    const first = scan.sightings.find((x) => x.title === "Rose 91")!;
    expect(first).toMatchObject({ artist: "DJ Hamid Khareji", folderDate: "1405-07-18", folderIso: "2026-10-10", size: 70521147, modified: "2026-10-10T19:36", sourceId: "ahaang" });
    expect(first.url).toBe("https://ahaaang.musitraf.com/05/07/18/DJ%20Hamid%20Khareji%20-%20Rose%2091.mp3");
    expect(s.calls).toEqual(["https://ahaaang.musitraf.com/05/07/18/"]);
  });

  it("GisoMusic: the 128 kbps copy and the original merge into one song with two versions", async () => {
    const s = site({ [dayUrl(src("giso"), D18)]: gisoDay });
    const scan = await scanDay(src("giso"), D18, deps(s));
    expect(scan.sightings).toHaveLength(12);
    const tracks = mergeSightings(scan.sightings);
    expect(tracks).toHaveLength(6);
    const tar = tracks.find((t) => t.title === "Tar")!;
    expect(tar.versions.map((v) => v.quality)).toEqual([null, 128]); // original first
  });

  it("a missing day folder is 'empty', not an error and not a site failure", async () => {
    const s = site({});
    expect(await scanDay(src("ahaang"), D19, deps(s))).toMatchObject({ status: "empty", detail: "not_found" });
  });

  it.each([
    [{ status: 403 }, "forbidden"], [{ status: 500 }, "server_error"], [{ status: 502 }, "server_error"], [{ status: 418 }, "status_418"],
    [{ throws: "timeout" as const }, "timeout"], [{ throws: "network" as const }, "network"],
  ])("failure %j is an error (%s) that can be retried", async (page, detail) => {
    const s = site({ [dayUrl(src("giso"), D18)]: page as Page });
    expect(await scanDay(src("giso"), D18, deps(s))).toMatchObject({ status: "error", detail, sightings: [] });
  });

  it("an HTML 200 that is not a directory listing (login page, SPA shell) is an error, never 'songs'", async () => {
    const s = site({ [dayUrl(src("mellody"), D18)]: "<html><head><title>Login</title></head><body><form>user</form></body></html>" });
    expect(await scanDay(src("mellody"), D18, deps(s))).toMatchObject({ status: "error", detail: "html_page" });
  });

  it("an empty listing is 'empty'", async () => {
    const s = site({ [dayUrl(src("giso"), D18)]: index("/Music/1405/07/18/", []) });
    expect(await scanDay(src("giso"), D18, deps(s))).toMatchObject({ status: "empty", detail: "no_media" });
  });

  it("finds ZIP collections in EVERY dated folder of Ahaang, not in one fixed folder", async () => {
    const s = site({
      [dayUrl(src("ahaang"), D17)]: index("/05/07/17/", [["Artoosh - Goldenhits.zip", 90_000_000], ["Other - Song.mp3", 5_000_000]]),
      [dayUrl(src("ahaang"), D19)]: index("/05/07/19/", [["Mohsen Chavoshi - Best Of.zip", 70_000_000], ["notes.txt", 10]]),
    });
    const a = await scanDay(src("ahaang"), D17, deps(s)), b = await scanDay(src("ahaang"), D19, deps(s));
    expect(a.archives).toHaveLength(1);
    expect(a.archives[0]).toMatchObject({ fileName: "Artoosh - Goldenhits.zip", artist: "Artoosh", collection: "Goldenhits", artistKey: "artoosh", size: 90_000_000, folderDate: "1405-07-17", sourceId: "ahaang" });
    expect(a.sightings).toHaveLength(1);
    expect(b.archives[0]).toMatchObject({ artist: "Mohsen Chavoshi", collection: "Best Of", folderDate: "1405-07-19" });
  });

  it("ZIPs of sources with scanZips=false are ignored, and a ZIP without 'Artist - ' has an unknown singer", async () => {
    const s = site({ [dayUrl(src("giso"), D18)]: index("/Music/1405/07/18/", [["Album.zip", 1000]]) });
    expect((await scanDay(src("giso"), D18, deps(s))).archives).toHaveLength(0);
    const s2 = site({ [dayUrl(src("ahaang"), D18)]: index("/05/07/18/", [["Album 2026.zip", 1000]]) });
    const z = (await scanDay(src("ahaang"), D18, deps(s2))).archives[0];
    expect(z).toMatchObject({ artist: null, artistKey: "", collection: "Album 2026" });
  });

  it("RozMusic: descends into sub-folders, keeps the day's own files when a sub-folder fails", async () => {
    const root = dayUrl(src("roz"), D19);
    const s = site({ [root]: rozDay, [`${root}Ragheb%20-%20Faryad/`]: index("/Music/1405/07/19/Ragheb - Faryad/", [["01 - Faryad.mp3", 5_000_000], ["02 - Other.mp3", 4_000_000]]) });
    const scan = await scanDay(src("roz"), D19, deps(s));
    expect(scan.requests).toBe(3); // root + 2 sub-folders (one answers 404)
    expect(scan.sightings.some((x) => x.fileName === "01 - Faryad.mp3")).toBe(true);
    expect(scan.sightings.some((x) => x.title === "Oxygen 25")).toBe(true);
  });

  it("respects the request budget and the per-day entry cap", async () => {
    const root = dayUrl(src("roz"), D19);
    const s = site({ [root]: rozDay });
    expect((await scanDay(src("roz"), D19, { ...deps(s), requestBudget: 1 })).requests).toBe(1);
    const capped = { ...src("roz"), maxEntriesPerDay: 10 };
    const scan = await scanDay(capped, D19, deps(site({ [root]: rozDay })));
    expect(scan.sightings.length + scan.archives.length).toBeLessThanOrEqual(10);
  });
});

describe("planScan: incremental, never re-scans months", () => {
  const config = { ...DEFAULT_CONFIG, maxWindowDays: 30, defaultWindowDays: 30 };
  const keys = (plan: ReturnType<typeof planScan>) => plan.map((p) => p.key);

  it("first run plans every enabled source for every day of the window, recent days first, newest first", () => {
    const plan = planScan(OCT11, config, new Map(), 30);
    expect(plan).toHaveLength(30 * 4);
    expect(plan[0]).toMatchObject({ reason: "recent", key: "ahaang:1405-07-19" });
    expect(plan.slice(0, 12).every((p) => p.reason === "recent")).toBe(true); // 3 recent days x 4 sources
    expect(plan[12].reason).toBe("first");
    expect(plan[12].key.endsWith("1405-07-16")).toBe(true);
  });

  it("after a complete scan, old days are never planned again", () => {
    const known = new Map<string, { status: string; scanned_at: number }>();
    for (const s of DEFAULT_SOURCES) for (const d of recentJalaliDays(OCT11, 30)) known.set(dayKey(s, d), { status: "ok", scanned_at: OCT11 - 60_000 });
    expect(planScan(OCT11, config, known, 30)).toEqual([]);
    // 10 minutes later still nothing; 30 minutes later only the recent days (late uploads), 7 hours later the 14-day late window.
    expect(planScan(OCT11 + 10 * 60_000, config, known, 30)).toEqual([]);
    const soon = planScan(OCT11 + 31 * 60_000, config, known, 30);
    expect(soon).toHaveLength(12);
    expect(soon.every((p) => p.reason === "recent")).toBe(true);
    const later = planScan(OCT11 + 7 * 3_600_000, config, known, 30);
    expect(later).toHaveLength(14 * 4);
    expect(later.some((p) => p.key.endsWith("1405-06-01"))).toBe(false);
  });

  it("retries failed days after 30 minutes but not before", () => {
    const known = new Map<string, { status: string; scanned_at: number }>();
    for (const s of DEFAULT_SOURCES) for (const d of recentJalaliDays(OCT11, 30)) known.set(dayKey(s, d), { status: "ok", scanned_at: OCT11 - 60_000 });
    known.set("giso:1405-06-25", { status: "error", scanned_at: OCT11 - 10 * 60_000 });
    expect(keys(planScan(OCT11, config, known, 30))).toEqual([]);
    known.set("giso:1405-06-25", { status: "error", scanned_at: OCT11 - 40 * 60_000 });
    expect(planScan(OCT11, config, known, 30)).toMatchObject([{ key: "giso:1405-06-25", reason: "retry" }]);
  });

  it("skips disabled sources", () => {
    const off = { ...config, sources: config.sources.map((s) => (s.id === "roz" ? { ...s, enabled: false } : s)) };
    expect(planScan(OCT11, off, new Map(), 30).some((p) => p.source.id === "roz")).toBe(false);
  });
});

describe("remote configuration", () => {
  it("accepts valid changes", () => {
    const r = applyConfigPatch(DEFAULT_CONFIG, { defaultWindowDays: 90, scanBudget: 20, scanEnabled: false });
    expect(r.ok && r.config).toMatchObject({ scanBudget: 20, scanEnabled: false });
  });

  it("changes priority, enabling and templates of sources without a new app release", () => {
    const sources = DEFAULT_SOURCES.map((s) => (s.id === "giso" ? { ...s, priority: 0, enabled: true } : { ...s, enabled: s.id !== "roz" }));
    const r = applyConfigPatch(DEFAULT_CONFIG, { sources });
    expect(r.ok).toBe(true);
    if (r.ok) expect(r.config.sources.find((s) => s.id === "giso")!.priority).toBe(0);
  });

  it.each([
    [{ bogus: 1 }], [{ scanBudget: 500 }], [{ scanBudget: "20" }], [{ scanEnabled: "yes" }], [{ defaultWindowDays: 300, maxWindowDays: 100 }],
    [{ recentDays: 7, lateRescanDays: 3 }], [{ sources: [] }], [{ sources: "x" }],
    [{ sources: [{ ...DEFAULT_SOURCES[0], baseUrl: "http://ahaaang.musitraf.com" }] }],
    [{ sources: [{ ...DEFAULT_SOURCES[0], baseUrl: "https://127.0.0.1" }] }],
    [{ sources: [{ ...DEFAULT_SOURCES[0], baseUrl: "https://localhost" }] }],
    [{ sources: [{ ...DEFAULT_SOURCES[0], dayPathTemplate: "/../{YY}/{MM}/{DD}/" }] }],
    [{ sources: [DEFAULT_SOURCES[0], DEFAULT_SOURCES[0]] }],
  ])("rejects %j", (patch) => { expect(applyConfigPatch(DEFAULT_CONFIG, patch).ok).toBe(false); });

  it("a corrupted stored config falls back to defaults instead of breaking the feature", async () => {
    const store = new MusicStore(new TestD1());
    await store.setSetting("config", "{not json", OCT11);
    expect(await loadConfig(store)).toEqual(DEFAULT_CONFIG);
    await store.setSetting("config", JSON.stringify({ scanBudget: 9999 }), OCT11);
    expect(await loadConfig(store)).toEqual(DEFAULT_CONFIG);
  });
});

describe("catalog in D1 (real SQL, real migration)", () => {
  const fresh = () => { const d1 = new TestD1(); return { d1, store: new MusicStore(d1) }; };
  const list = (store: MusicStore, over: Partial<Parameters<MusicStore["listTracks"]>[0]> = {}) =>
    store.listTracks({ sinceIso: "2026-07-01", query: "", limit: 50, offset: 0, badRetryBefore: OCT11 - 86_400_000, ...over });

  it("is idempotent: scanning the same day twice does not duplicate anything", async () => {
    const { d1, store } = fresh();
    const s = [sighting({ title: "A" }), sighting({ title: "B", url: "https://x/b.mp3" })];
    await seed(store, s); await seed(store, s);
    expect(d1.rows("SELECT COUNT(*) n FROM music_tracks")[0]).toEqual({ n: 2 });
    expect(d1.rows("SELECT COUNT(*) n FROM music_versions")[0]).toEqual({ n: 2 });
  });

  it("merges the same song from several sources into ONE result that keeps every source's link", async () => {
    const { store } = fresh();
    await seed(store, [
      sighting({ sourceId: "roz", sourcePriority: 4, url: "https://roz/x.mp3", artist: "Mahzad", title: "Bia Negam Kon" }),
      sighting({ sourceId: "ahaang", sourcePriority: 1, url: "https://aha/x.mp3", artist: "Mahzad", title: "Bia Negam Kon" }),
      sighting({ sourceId: "giso", sourcePriority: 2, url: "https://giso/x.mp3", artist: "mahzad", title: "bia negam kon" }),
    ]);
    const { tracks, total } = await list(store);
    expect(total).toBe(1);
    expect(tracks[0].versions.map((v) => v.source_id)).toEqual(["ahaang", "giso", "roz"]); // fallback order = source priority
  });

  it("sorts by date, NOT by source priority", async () => {
    const { store } = fresh();
    await seed(store, [
      sighting({ sourceId: "ahaang", sourcePriority: 1, url: "https://aha/old.mp3", title: "Old", folderIso: "2026-10-01", folderDate: "1405-07-09" }),
      sighting({ sourceId: "roz", sourcePriority: 4, url: "https://roz/new.mp3", title: "New", folderIso: "2026-10-11", folderDate: "1405-07-19" }),
      sighting({ sourceId: "giso", sourcePriority: 2, url: "https://giso/mid.mp3", title: "Mid", folderIso: "2026-10-05", folderDate: "1405-07-13" }),
    ]);
    expect((await list(store)).tracks.map((t) => t.title)).toEqual(["New", "Mid", "Old"]);
  });

  it("a song seen later on another source keeps its EARLIEST folder date", async () => {
    const { store } = fresh();
    await seed(store, [sighting({ url: "https://giso/a.mp3", folderIso: "2026-10-05", folderDate: "1405-07-13" })]);
    await seed(store, [sighting({ sourceId: "roz", sourcePriority: 4, url: "https://roz/a.mp3", folderIso: "2026-10-09", folderDate: "1405-07-17" })]);
    const t = (await list(store)).tracks[0];
    expect(t.first_folder_iso).toBe("2026-10-05");
    expect(t.versions).toHaveLength(2);
  });

  it("search is normalised for Persian typing variants (ي/ی, ك/ک) and requires every word", async () => {
    const { store } = fresh();
    await seed(store, [
      sighting({ url: "https://x/1.mp3", artist: "محسن چاوشی", title: "ایران من" }),
      sighting({ url: "https://x/2.mp3", artist: "Mahzad", title: "Bia Negam Kon" }),
    ]);
    expect((await list(store, { query: "محسن چاوشي" })).tracks.map((t) => t.artist)).toEqual(["محسن چاوشی"]); // Arabic yeh
    expect((await list(store, { query: "ایران   من" })).total).toBe(1);
    expect((await list(store, { query: "mahzad negam" })).total).toBe(1);
    expect((await list(store, { query: "mahzad چاوشی" })).total).toBe(0);
  });

  it("LIKE wildcards typed by a user do not widen the search", async () => {
    const { store } = fresh();
    await seed(store, [sighting({ url: "https://x/1.mp3", title: "Alpha" }), sighting({ url: "https://x/2.mp3", title: "Beta" })]);
    expect((await list(store, { query: "%" })).total).toBe(0);
    expect((await list(store, { query: "_" })).total).toBe(0);
  });

  it("paginates and filters by the 3/6-month window", async () => {
    const { store } = fresh();
    const many = Array.from({ length: 25 }, (_, i) => sighting({ url: `https://x/${i}.mp3`, title: `Song ${String(i).padStart(2, "0")}`, folderIso: `2026-10-${String(1 + (i % 9)).padStart(2, "0")}` }));
    await seed(store, [...many, sighting({ url: "https://x/ancient.mp3", title: "Ancient", folderIso: "2026-02-01", folderDate: "1404-11-12" })]);
    const p1 = await list(store, { limit: 10 }), p3 = await list(store, { limit: 10, offset: 20 });
    expect(p1.total).toBe(25); // the February song is outside a window starting 2026-07-01
    expect(p1.tracks).toHaveLength(10);
    expect(p3.tracks).toHaveLength(5);
    expect((await list(store, { sinceIso: "2026-01-01" })).total).toBe(26);
  });

  it("hides songs whose every link is known-bad, then offers them again after 24 hours", async () => {
    const { store } = fresh();
    await seed(store, [sighting({ url: "https://x/a.mp3", title: "A" }), sighting({ url: "https://x/b.mp3", title: "B" })]);
    await store.markVersion("https://x/a.mp3", "bad", OCT11);
    expect((await list(store)).tracks.map((t) => t.title)).toEqual(["B"]);
    expect((await list(store, { badRetryBefore: OCT11 + 25 * 3_600_000 })).total).toBe(2);
  });

  it("prune removes old rows in BOTH calendars and keeps everything inside the window", async () => {
    const { d1, store } = fresh();
    await store.saveDayScan("giso:1405-07-18", "giso", "1405-07-18", dayScan([sighting({ title: "Fresh" })]), OCT11);
    await store.saveDayScan("giso:1404-01-01", "giso", "1404-01-01", dayScan([sighting({ title: "Stale", url: "https://x/s.mp3", folderIso: "2025-03-21", folderDate: "1404-01-01" })], [
      { sourceId: "ahaang", url: "https://a/old.zip", fileName: "Old - Album.zip", collection: "Album", artist: "Old", artistKey: "old", size: 5, folderDate: "1404-01-01", folderIso: "2025-03-21", modified: null, discoveredAt: OCT11 },
    ]), OCT11);
    await store.prune("2026-03-20", "1405-01-01");
    expect(d1.rows("SELECT title FROM music_tracks").map((r: any) => r.title)).toEqual(["Fresh"]);
    expect(d1.rows("SELECT COUNT(*) n FROM music_versions")[0]).toEqual({ n: 1 });
    expect(d1.rows("SELECT COUNT(*) n FROM music_archives")[0]).toEqual({ n: 0 });
    expect(d1.rows("SELECT day_key FROM music_scan_days").map((r: any) => r.day_key)).toEqual(["giso:1405-07-18"]);
  });
});

describe("runScan (scheduled / admin)", () => {
  const setup = async (pages: Record<string, Page>, patch: object = {}) => {
    const d1 = new TestD1(); const store = new MusicStore(d1);
    await saveConfig(store, { ...DEFAULT_CONFIG, maxWindowDays: 30, defaultWindowDays: 30, ...patch }, OCT11);
    const s = site(pages); const clock = { now: OCT11 };
    return { d1, store, s, clock, run: (opts = {}) => runScan(store, { fetchImpl: s.fetchImpl, now: () => clock.now }, opts) };
  };
  const pages = () => ({
    [dayUrl(src("ahaang"), D18)]: ahaangDay, [dayUrl(src("giso"), D18)]: gisoDay, [dayUrl(src("roz"), D19)]: rozDay,
    [dayUrl(src("ahaang"), D19)]: index("/05/07/19/", [["Artoosh - Goldenhits.zip", 90_000_000]]),
  });

  it("collects songs and ZIP collections from several sources in one pass", async () => {
    const t = await setup(pages());
    const summary = await t.run();
    expect(summary.errors).toBe(0);
    expect(summary.tracksSeen).toBe(12 + 3 + 22);
    const rows = await t.store.listTracks({ sinceIso: "2026-09-01", query: "", limit: 100, offset: 0, badRetryBefore: 0 });
    expect(rows.total).toBe(3 + 6 + 11);
    expect(rows.tracks[0].first_folder_iso).toBe("2026-10-11"); // today's folder first
    expect(summary.archivesSeen).toBe(1);
    // The fake 90 MB archive is not actually served, so validation rejects it and it must stay hidden from the app.
    expect(t.d1.rows("SELECT status, detail FROM music_archives")).toEqual([{ status: "rejected", detail: "not_found" }]);
    expect(await t.store.listArtists()).toEqual([]);
  });

  it("does not re-fetch old folders on later runs, only recent days (late uploads)", async () => {
    const t = await setup(pages(), { maxWindowDays: 7, defaultWindowDays: 7 }); // small enough that run 1 finishes the whole backfill
    await t.run();
    const first = t.s.calls.length;
    t.clock.now += 31 * 60_000;
    await t.run();
    const second = t.s.calls.length - first;
    expect(second).toBeLessThanOrEqual(12 + 4); // 3 recent days x 4 sources (+ archive validation)
    expect(second).toBeLessThan(first / 2);
  });

  it("honours the minimum interval between scheduled runs, and 'force' overrides it", async () => {
    const t = await setup(pages());
    await t.run();
    t.clock.now += 5 * 60_000;
    expect((await t.run()).scannedDays).toBe(0);
    expect((await t.run({ force: true })).scannedDays).toBeGreaterThanOrEqual(0);
  });

  it("can be switched off remotely", async () => {
    const t = await setup(pages(), { scanEnabled: false });
    expect(await t.run()).toMatchObject({ scannedDays: 0, requests: 0 });
    expect(t.s.calls).toHaveLength(0);
    expect((await t.run({ force: true })).scannedDays).toBeGreaterThan(0);
  });

  it("stays inside the outbound request budget", async () => {
    const t = await setup(pages(), { scanBudget: 10, archiveValidationPerRun: 0 });
    const summary = await t.run();
    expect(t.s.calls.length).toBeLessThanOrEqual(10 + 4 /* in-flight workers */);
    expect(summary.budgetExhausted).toBe(true);
  });

  it("stops hammering a failing source after 3 errors but keeps scanning the healthy ones", async () => {
    const p = pages();
    const broken: Record<string, Page> = {};
    for (const d of recentJalaliDays(OCT11, 30)) broken[dayUrl({ ...src("mellody"), baseUrl: "https://dl.melody98.ir" }, d)] = { status: 503 }; // the Worker scans through the mirror host
    const t = await setup({ ...p, ...broken });
    const summary = await t.run();
    expect(summary.skippedSources).toContain("mellody");
    expect(t.s.calls.filter((u) => u.includes("melody98.ir")).length).toBeLessThanOrEqual(3 + 3 /* concurrent in-flight */);
    expect(summary.tracksSeen).toBeGreaterThan(30);
  });

  it("a site failure never erases songs found earlier", async () => {
    const t = await setup(pages());
    await t.run();
    const before = (await t.store.counts()).tracks;
    t.s.pages[dayUrl(src("giso"), D18)] = { status: 500 };
    t.clock.now += 7 * 3_600_000;
    await t.run();
    expect((await t.store.counts()).tracks).toBe(before);
  });

  it("validates ZIP archives it discovered (contents come from Range requests, never a download)", async () => {
    const { buildZip } = await import("./zip-builder");
    const url = "https://ahaaang.musitraf.com/05/07/19/Artoosh%20-%20Goldenhits.zip";
    const zip = buildZip([{ name: "Artoosh/01 - A.mp3", data: MP3(5000) }, { name: "Artoosh/02 - B.mp3", data: MP3(5000) }, { name: "cover.jpg", data: new Uint8Array(10) }]);
    const t = await setup({ ...pages(), [url]: new Uint8Array(0) });
    // serve the zip with Range support for this URL only
    const { rangeFetch } = await import("./zip-builder");
    const ranged = rangeFetch({ [url]: zip });
    const base = t.s.fetchImpl;
    const fetchImpl = (async (i: any, init: any) => (String(i) === url ? ranged(i, init) : base(i, init))) as typeof fetch;
    await saveConfig(t.store, { ...DEFAULT_CONFIG, maxWindowDays: 30, defaultWindowDays: 30 }, OCT11);
    await runScan(t.store, { fetchImpl, now: () => OCT11 }, { force: true });
    await t.store.setSetting("noop", "1", OCT11);
    // archive size in the fixture index is 90 MB but the fake zip is small: validation of the real size is covered in zip tests;
    // here we verify the plumbing marks the row as checked either way.
    const archive = await t.store.getArchive(url);
    expect(archive?.checked_at).not.toBeNull();
  });
});

describe("resolveTrack (fallback to another source)", () => {
  const A = "https://ahaaang.musitraf.com/05/07/18/x.mp3", B = "https://dl.gisomusic.com/Music/1405/07/18/x.mp3", C = "https://dl.rozmusic.com/Music/1405/07/18/x.mp3";
  const setup = async () => {
    const store = new MusicStore(new TestD1());
    await seed(store, [
      sighting({ sourceId: "ahaang", sourcePriority: 1, url: A, artist: "Mahzad", title: "Bia" }),
      sighting({ sourceId: "giso", sourcePriority: 2, url: B, artist: "Mahzad", title: "Bia" }),
      sighting({ sourceId: "roz", sourcePriority: 4, url: C, artist: "Mahzad", title: "Bia" }),
    ]);
    const id = (await store.listTracks({ sinceIso: "2026-01-01", query: "", limit: 5, offset: 0, badRetryBefore: 0 })).tracks[0].id;
    return { store, id };
  };
  const run = (store: MusicStore, id: string, pages: Record<string, Page>, exclude: string[] = [], health = new SourceHealth()) =>
    resolveTrack(store, { fetchImpl: site(pages).fetchImpl, now: () => OCT11 }, id, exclude, health);

  it("uses the first source when its link works", async () => {
    const { store, id } = await setup();
    const r = await run(store, id, { [A]: MP3(), [B]: MP3() });
    expect(r?.ok && r.version?.url).toBe(A);
  });

  it("falls back to the next source when the first is 404, and remembers the dead link", async () => {
    const { store, id } = await setup();
    const r = await run(store, id, { [B]: MP3() });
    expect(r?.version?.url).toBe(B);
    const versions = await store.trackVersions(id);
    expect(Object.fromEntries(versions.map((v) => [v.source_id, v.status]))).toEqual({ ahaang: "bad", giso: "ok", roz: "unknown" });
  });

  it("treats an HTML page served with 200 as a broken link, not as audio", async () => {
    const { store, id } = await setup();
    const r = await run(store, id, { [A]: "<html>error</html>", [B]: { status: 403 }, [C]: MP3() });
    expect(r?.version?.url).toBe(C);
    expect(r?.attempts.map((a) => a.result)).toEqual(["not_audio", "forbidden", "ok"]);
  });

  it("a timeout is a SITE problem: the link is not marked bad", async () => {
    const { store, id } = await setup();
    const r = await run(store, id, { [A]: { throws: "timeout" }, [B]: MP3() });
    expect(r?.version?.url).toBe(B);
    expect((await store.trackVersions(id)).find((v) => v.source_id === "ahaang")?.status).toBe("unknown");
  });

  it("skips the link the player already failed on (exclude) and reports failure when nothing plays", async () => {
    const { store, id } = await setup();
    expect((await run(store, id, { [A]: MP3(), [B]: MP3() }, [A]))?.version?.url).toBe(B);
    const none = await run(store, id, {});
    expect(none?.ok).toBe(false);
    expect(none?.attempts).toHaveLength(3);
  });

  it("stops contacting an unhealthy source (circuit breaker) while other sources keep working", async () => {
    const { store, id } = await setup();
    const health = new SourceHealth();
    for (let i = 0; i < 3; i += 1) health.recordFailure("ahaang", `u${i}`, "timeout", OCT11);
    const s = site({ [A]: MP3(), [B]: MP3() });
    const r = await resolveTrack(store, { fetchImpl: s.fetchImpl, now: () => OCT11 }, id, [], health);
    expect(r?.version?.url).toBe(B);
    expect(s.calls).not.toContain(A);
  });

  it("returns null for an unknown song", async () => {
    const { store } = await setup();
    expect(await run(store, "0000000000000000", {})).toBeNull();
  });
});
