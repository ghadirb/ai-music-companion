import { describe, expect, it } from "vitest";
import {
  gregorianToJalali, isJalaliLeap, jalaliKey, jalaliMonthLength, jalaliToGregorian, jalaliToday, jalaliToIso, recentJalaliDays,
} from "../src/music/jalali";
import { activeSources, dayUrl, DEFAULT_SOURCES, validateSource, type MusicSource } from "../src/music/sources";
import { isAudioName, isZipName, parseAutoindex } from "../src/music/listing";
import { artistKey, normalizeText, parseTrackName, parseZipName, splitArtists, trackKey } from "../src/music/track";
import { mergeSightings, type Sighting } from "../src/music/catalog";
import { looksLikeAudio, probeAudio, resolvePlayable, SourceHealth } from "../src/music/resolve";
import ahaangDay from "./fixtures/ahaang-day.html?raw";
import ahaang404 from "./fixtures/ahaang-404.html?raw";
import gisoDay from "./fixtures/giso-day.html?raw";
import gisoMonth from "./fixtures/giso-month.html?raw";
import rozDay from "./fixtures/roz-day.html?raw";
import mellodyDay from "./fixtures/mellody-day.html?raw";

const J = (jy: number, jm: number, jd: number) => ({ jy, jm, jd });
const src = (id: string): MusicSource => DEFAULT_SOURCES.find((s) => s.id === id)!;

describe("Jalali calendar", () => {
  it("converts known dates (verified against the live servers: 1405/07/01 is 23 Sep 2026)", () => {
    expect(gregorianToJalali(2026, 10, 11)).toEqual(J(1405, 7, 19));
    expect(gregorianToJalali(2026, 9, 23)).toEqual(J(1405, 7, 1));
    expect(gregorianToJalali(2026, 3, 21)).toEqual(J(1405, 1, 1));
    expect(gregorianToJalali(2026, 3, 20)).toEqual(J(1404, 12, 29));
  });

  it("handles leap years: 1403 has an Esfand 30, 1404 and 1405 do not", () => {
    expect(isJalaliLeap(1403)).toBe(true);
    expect(isJalaliLeap(1404)).toBe(false);
    expect(isJalaliLeap(1405)).toBe(false);
    expect(jalaliMonthLength(1403, 12)).toBe(30);
    expect(jalaliMonthLength(1404, 12)).toBe(29);
    expect(gregorianToJalali(2025, 3, 20)).toEqual(J(1403, 12, 30));
    expect(gregorianToJalali(2025, 3, 21)).toEqual(J(1404, 1, 1));
  });

  it("round-trips every day of several years including leap years", () => {
    for (let jy = 1399; jy <= 1408; jy += 1) {
      for (let jm = 1; jm <= 12; jm += 1) {
        for (let jd = 1; jd <= jalaliMonthLength(jy, jm); jd += 1) {
          const g = jalaliToGregorian(jy, jm, jd);
          expect(gregorianToJalali(g.gy, g.gm, g.gd)).toEqual(J(jy, jm, jd));
        }
      }
    }
  });

  it("uses Asia/Tehran, not UTC, to decide 'today'", () => {
    // 21:00 UTC on 10 Oct is 00:30 on 11 Oct in Tehran (+03:30).
    expect(jalaliToday(Date.UTC(2026, 9, 10, 21, 0))).toEqual(J(1405, 7, 19));
    expect(jalaliToday(Date.UTC(2026, 9, 10, 20, 0))).toEqual(J(1405, 7, 18));
  });

  it("walks back across month and year boundaries", () => {
    const aban = recentJalaliDays(Date.UTC(2026, 9, 23, 12, 0), 3).map(jalaliKey);
    expect(aban).toEqual(["1405-08-01", "1405-07-30", "1405-07-29"]);
    const nowruz = recentJalaliDays(Date.UTC(2026, 2, 21, 10, 0), 3).map(jalaliKey);
    expect(nowruz).toEqual(["1405-01-01", "1404-12-29", "1404-12-28"]);
    const leap = recentJalaliDays(Date.UTC(2025, 2, 21, 10, 0), 2).map(jalaliKey);
    expect(leap).toEqual(["1404-01-01", "1403-12-30"]);
  });

  it("returns exactly the requested number of distinct days (3 months ~ 92)", () => {
    const days = recentJalaliDays(Date.UTC(2026, 9, 11, 9, 0), 92).map(jalaliKey);
    expect(days).toHaveLength(92);
    expect(new Set(days).size).toBe(92);
    expect(days[0]).toBe("1405-07-19");
  });

  it("exposes the Gregorian date of a Jalali folder", () => {
    expect(jalaliToIso(J(1405, 7, 19))).toBe("2026-10-11");
  });
});

describe("source paths (all four sources use the Jalali date)", () => {
  const d = J(1405, 7, 5);
  it("builds the dated URL for every source", () => {
    expect(dayUrl(src("ahaang"), d)).toBe("https://ahaaang.musitraf.com/05/07/05/");
    expect(dayUrl(src("giso"), d)).toBe("https://dl.gisomusic.com/Music/1405/07/05/");
    expect(dayUrl(src("mellody"), d)).toBe("https://mellody98.musitraf.com/music/1405/07/05/");
    expect(dayUrl(src("roz"), d)).toBe("https://dl.rozmusic.com/Music/1405/07/05/");
  });

  it("uses a 2-digit year only for Ahaang, including century rollovers", () => {
    expect(dayUrl(src("ahaang"), J(1399, 12, 29))).toBe("https://ahaaang.musitraf.com/99/12/29/");
    expect(dayUrl(src("ahaang"), J(1400, 1, 1))).toBe("https://ahaaang.musitraf.com/00/01/01/");
  });

  it("orders sources Ahaang → Giso → Mellody → Roz by default", () => {
    expect(activeSources(undefined).map((s) => s.id)).toEqual(["ahaang", "giso", "mellody", "roz"]);
  });

  it("honours remote configuration: disable, re-order, ignore invalid entries", () => {
    const cfg = [
      { ...src("roz"), priority: 1 },
      { ...src("giso"), enabled: false },
      { ...src("ahaang"), priority: 2 },
      { id: "evil", name: "x", priority: 0, baseUrl: "http://127.0.0.1", dayPathTemplate: "/{YYYY}/{MM}/{DD}/" },
    ];
    expect(activeSources(cfg).map((s) => s.id)).toEqual(["roz", "ahaang"]);
    expect(activeSources("garbage").map((s) => s.id)).toEqual(["ahaang", "giso", "mellody", "roz"]);
  });

  it("rejects unsafe or malformed source definitions", () => {
    const ok = { ...src("giso") };
    expect(validateSource(ok)).not.toBeNull();
    for (const bad of [
      { ...ok, baseUrl: "http://dl.gisomusic.com" },
      { ...ok, baseUrl: "https://user:pw@dl.gisomusic.com" },
      { ...ok, baseUrl: "https://localhost" },
      { ...ok, baseUrl: "https://10.0.0.5" },
      { ...ok, baseUrl: "https://[::1]" },
      { ...ok, baseUrl: "https://intranet" },
      { ...ok, baseUrl: "https://dl.gisomusic.com/path" },
      { ...ok, dayPathTemplate: "/Music/../{YYYY}/{MM}/{DD}/" },
      { ...ok, dayPathTemplate: "/Music/{YYYY}/{MM}/" },
      { ...ok, dayPathTemplate: "Music/{YYYY}/{MM}/{DD}/" },
      { ...ok, id: "Bad Id" },
      { ...ok, priority: -1 },
    ]) expect(validateSource(bad)).toBeNull();
  });
});

describe("directory listing parser (real server responses)", () => {
  it("reads full names from href, not from the truncated link text", () => {
    const r = parseAutoindex(ahaangDay);
    expect(r.kind).toBe("listing");
    if (r.kind !== "listing") return;
    expect(r.entries.map((e) => e.name)).toContain("DJM6 And Sajjad Gholipour - Man Koja Baran Koja.mp3");
    const rose = r.entries.find((e) => e.name.startsWith("DJ Hamid"))!;
    expect(rose.size).toBe(70521147);
    expect(rose.modified).toBe("2026-10-10T19:36");
    expect(r.entries.every((e) => !e.isDir)).toBe(true);
  });

  it("separates folders from files and decodes %28 %29", () => {
    const r = parseAutoindex(rozDay);
    if (r.kind !== "listing") throw new Error("expected listing");
    const dirs = r.entries.filter((e) => e.isDir).map((e) => e.name);
    expect(dirs).toContain("Ragheb - Faryad (128)");
    expect(r.entries.find((e) => e.name === "Ali Dousti - Age Be Mane (128).mp3")?.size).toBe(2815540);
  });

  it("parses month-level listings (folders only)", () => {
    const r = parseAutoindex(gisoMonth);
    if (r.kind !== "listing") throw new Error("expected listing");
    const dirs = r.entries.filter((e) => e.isDir);
    expect(dirs.length).toBe(r.entries.length);
    expect(dirs.length).toBe((gisoMonth.match(/<a href="\d{2}\/">/g) ?? []).length);
    expect(dirs.every((e) => /^\d{2}$/.test(e.name))).toBe(true);
    expect(dirs[0].name).toBe("01");
  });

  it("parses the open dated listing of Mellody98 with bracketed quality tags", () => {
    const r = parseAutoindex(mellodyDay);
    if (r.kind !== "listing") throw new Error("expected listing");
    expect(r.entries.some((e) => e.name === "Ai - Delam Abrist Va Midanam [320].mp3")).toBe(true);
    expect(r.entries.find((e) => e.name.startsWith("Amir Tataloo x Pishro"))!.name.endsWith(".mp3")).toBe(true);
  });

  it("does not treat login pages, 404 pages or empty bodies as listings", () => {
    expect(parseAutoindex(ahaang404).kind).toBe("not_a_listing");
    expect(parseAutoindex("<html><head><title>ورود</title></head><body><form method=post></form></body></html>")).toEqual({ kind: "not_a_listing", reason: "html_page" });
    expect(parseAutoindex("")).toEqual({ kind: "not_a_listing", reason: "empty" });
    expect(parseAutoindex("404")).toEqual({ kind: "not_a_listing", reason: "html_page" });
  });

  it("ignores hostile hrefs (absolute, external, traversal, encoded slashes, control chars)", () => {
    const html = `<html><head><title>Index of /x/</title></head><body><pre><a href="../">../</a>
<a href="/etc/passwd">a</a> 10-Oct-2026 10:00 5
<a href="http://evil.example/a.mp3">a</a> 10-Oct-2026 10:00 5
<a href="..%2F..%2Fsecret.mp3">a</a> 10-Oct-2026 10:00 5
<a href="a%2Fb.mp3">a</a> 10-Oct-2026 10:00 5
<a href="bad%00.mp3">a</a> 10-Oct-2026 10:00 5
<a href="?C=M;O=A">a</a> 10-Oct-2026 10:00 5
<a href="good.mp3">good.mp3</a> 10-Oct-2026 10:00 5
</pre></body></html>`;
    const r = parseAutoindex(html);
    if (r.kind !== "listing") throw new Error("expected listing");
    expect(r.entries.map((e) => e.name)).toEqual(["good.mp3"]);
  });

  it("classifies audio and zip by extension (content is validated separately)", () => {
    expect(isAudioName("A - B.MP3")).toBe(true);
    expect(isAudioName("cover.jpg")).toBe(false);
    expect(isZipName("Artoosh - Goldenhits.zip")).toBe(true);
    expect(isZipName("zip")).toBe(false);
  });
});

describe("file name analysis and Persian normalization", () => {
  it("extracts artist, title and quality from every source's convention", () => {
    expect(parseTrackName("Ahmad Solo - Tar - 128.mp3")).toEqual({ artist: "Ahmad Solo", title: "Tar", quality: 128, ext: "mp3" });
    expect(parseTrackName("Dj Sia - Oxygen 25 (128).mp3")).toMatchObject({ artist: "Dj Sia", title: "Oxygen 25", quality: 128 });
    expect(parseTrackName("Ai - In Pesar Hame Kasame [320].mp3")).toMatchObject({ artist: "Ai", title: "In Pesar Hame Kasame", quality: 320 });
    expect(parseTrackName("DJ Hamid Khareji - Rose 91.mp3")).toMatchObject({ title: "Rose 91", quality: null });
  });

  it("splits only on the first ' - ' and keeps hyphens inside titles", () => {
    expect(parseTrackName("Artist - Song - Part 2.mp3")).toMatchObject({ artist: "Artist", title: "Song - Part 2", quality: null });
  });

  it("copes with files that have no artist, underscores and site tags", () => {
    expect(parseTrackName("Just_A_Title.mp3")).toMatchObject({ artist: null, title: "Just A Title" });
    expect(parseTrackName("Artist - Song [www.somesite.com].mp3").title).toBe("Song");
  });

  it("folds Arabic/Persian letter variants, digits, ZWNJ and diacritics", () => {
    expect(normalizeText("علي كريمي ۱۲۳")).toBe("علی کریمی 123");
    expect(normalizeText("می\u200Cخواهم")).toBe("می خواهم");
    expect(normalizeText("  Hello,   WORLD!! ")).toBe("hello world");
    expect(normalizeText("سَلام")).toBe(normalizeText("سلام"));
  });

  it("gives the same key regardless of artist order, feature syntax or quality tag", () => {
    const a = trackKey(parseTrackName("Amir x Sina - Song (128).mp3").artist, parseTrackName("Amir x Sina - Song (128).mp3").title);
    const b = trackKey(parseTrackName("Sina & Amir - Song.mp3").artist, parseTrackName("Sina & Amir - Song.mp3").title);
    expect(a).toBe(b);
    expect(trackKey("Amir", "Song")).not.toBe(trackKey("Amir", "Other Song"));
    expect(trackKey("Amir", "Song")).not.toBe(trackKey("Sina", "Song"));
  });

  it("splits credits with Persian and Latin conjunctions", () => {
    expect(splitArtists("A x B")).toEqual(["A", "B"]);
    expect(splitArtists("A و B")).toEqual(["A", "B"]);
    expect(splitArtists("A ft. B, C")).toEqual(["A", "B", "C"]);
  });

  it("recognises the singer and collection of an archive", () => {
    expect(parseZipName("Artoosh - Goldenhits.zip")).toEqual({ artist: "Artoosh", collection: "Goldenhits" });
    expect(parseZipName("Best Of 2026.zip")).toEqual({ artist: null, collection: "Best Of 2026" });
    expect(artistKey("Artoosh")).toBe(artistKey("ARTOOSH "));
  });
});

describe("catalog: duplicates, versions and ordering", () => {
  const s = (over: Partial<Sighting>): Sighting => ({
    sourceId: "ahaang", sourcePriority: 1, url: "https://x/a.mp3", fileName: "a.mp3", artist: "Artist", title: "Song", quality: null, size: 1,
    folderDate: "1405-07-18", folderIso: "2026-10-10", modified: "2026-10-10T10:00", discoveredAt: 1000, ...over,
  });

  it("shows one result per song but keeps every source's link, in priority order", () => {
    const tracks = mergeSightings([
      s({ sourceId: "roz", sourcePriority: 4, url: "https://roz/a.mp3", folderDate: "1405-07-19", folderIso: "2026-10-11" }),
      s({ sourceId: "giso", sourcePriority: 2, url: "https://giso/a.mp3", folderDate: "1405-07-19", folderIso: "2026-10-11" }),
      s({ sourceId: "ahaang", sourcePriority: 1, url: "https://ahaang/a.mp3" }),
    ]);
    expect(tracks).toHaveLength(1);
    expect(tracks[0].versions.map((v) => v.sourceId)).toEqual(["ahaang", "giso", "roz"]);
  });

  it("orders versions inside one source by quality (original/unmarked first, then 320, 128)", () => {
    const [t] = mergeSightings([
      s({ url: "https://x/128.mp3", quality: 128 }), s({ url: "https://x/orig.mp3", quality: null }), s({ url: "https://x/320.mp3", quality: 320 }),
    ]);
    expect(t.versions.map((v) => v.quality)).toEqual([null, 320, 128]);
  });

  it("collapses identical URLs and never merges different songs or different artists", () => {
    expect(mergeSightings([s({}), s({})])[0].versions).toHaveLength(1);
    expect(mergeSightings([s({}), s({ artist: "Other", url: "https://x/b.mp3" })])).toHaveLength(2);
    expect(mergeSightings([s({}), s({ title: "Song 2", url: "https://x/c.mp3" })])).toHaveLength(2);
  });

  it("merges Persian spelling variants of the same song", () => {
    const tracks = mergeSightings([
      s({ artist: "علي", title: "كوه", url: "https://x/1.mp3" }),
      s({ artist: "علی", title: "کوه", url: "https://y/1.mp3", sourceId: "giso", sourcePriority: 2 }),
    ]);
    expect(tracks).toHaveLength(1);
    expect(tracks[0].versions).toHaveLength(2);
  });

  it("sorts newest first by date, NOT by source priority", () => {
    const tracks = mergeSightings([
      s({ title: "OldTop", url: "https://a/1.mp3", folderIso: "2026-10-01", folderDate: "1405-07-09" }),                 // best source, old
      s({ title: "NewLow", sourceId: "roz", sourcePriority: 4, url: "https://r/2.mp3", folderIso: "2026-10-11", folderDate: "1405-07-19" }), // worst source, new
    ]);
    expect(tracks.map((t) => t.title)).toEqual(["NewLow", "OldTop"]);
  });

  it("uses the earliest folder date across sources as the release proxy and mtime only as a same-day tie-break", () => {
    const [t] = mergeSightings([
      s({ url: "https://a/1.mp3", folderIso: "2026-10-10", folderDate: "1405-07-18" }),
      s({ sourceId: "giso", sourcePriority: 2, url: "https://g/1.mp3", folderIso: "2026-10-11", folderDate: "1405-07-19" }),
    ]);
    expect(t.firstFolderDate).toBe("1405-07-18");
    const tied = mergeSightings([
      s({ title: "Early", url: "https://a/e.mp3", modified: "2026-10-10T08:00" }),
      s({ title: "Late", url: "https://a/l.mp3", modified: "2026-10-10T20:00" }),
    ]);
    expect(tied.map((x) => x.title)).toEqual(["Late", "Early"]);
  });

  it("builds from a real Giso listing: 6 songs, each with a 128 and an original version", () => {
    const r = parseAutoindex(gisoDay);
    if (r.kind !== "listing") throw new Error("expected listing");
    const sightings = r.entries.filter((e) => isAudioName(e.name)).map((e): Sighting => {
      const p = parseTrackName(e.name);
      return { sourceId: "giso", sourcePriority: 2, url: `https://dl.gisomusic.com/Music/1405/07/18/${encodeURIComponent(e.name)}`, fileName: e.name, artist: p.artist, title: p.title, quality: p.quality, size: e.size, folderDate: "1405-07-18", folderIso: "2026-10-10", modified: e.modified, discoveredAt: 1 };
    });
    const tracks = mergeSightings(sightings);
    expect(tracks).toHaveLength(6);
    expect(tracks.every((t) => t.versions.length === 2 && t.versions[0].quality === null && t.versions[1].quality === 128)).toBe(true);
  });
});

// ---------- link validation & fallback ----------

const ID3 = new Uint8Array([0x49, 0x44, 0x33, 4, 0, 0, 0, 0, 0, 0, 0x10, 0x20]);
const audio = () => new Response(ID3, { status: 206, headers: { "content-type": "audio/mpeg", "content-range": "bytes 0-11/5000" } });
const html404 = () => new Response("<html>404</html>", { status: 404, headers: { "content-type": "text/html" } });
const v = (sourceId: string, priority: number, url: string, quality: number | null = null) => ({ sourceId, sourcePriority: priority, url, fileName: "f.mp3", quality, size: 1, folderDate: "1405-07-19", modified: null });
const router = (routes: Record<string, () => Response | Promise<Response>>) => (async (input: RequestInfo | URL) => {
  const handler = routes[String(input)];
  if (!handler) throw new Error("unexpected url " + String(input));
  return handler();
}) as unknown as typeof fetch;

describe("audio probing", () => {
  it("recognises real audio containers by magic bytes", () => {
    expect(looksLikeAudio(ID3)).toBe(true);
    expect(looksLikeAudio(new Uint8Array([0xff, 0xfb, 0x90, 0x00]))).toBe(true);
    expect(looksLikeAudio(new TextEncoder().encode("fLaC...."))).toBe(true);
    expect(looksLikeAudio(new TextEncoder().encode("<!DOCTYPE html>"))).toBe(false);
    expect(looksLikeAudio(new Uint8Array([1, 2]))).toBe(false);
  });

  it("maps 404, 403, HTML-instead-of-audio, HTML disguised as 200 octet-stream, 5xx, redirects, timeout and network errors", async () => {
    const cases: Array<[() => Response | Promise<Response>, string]> = [
      [html404, "not_found"],
      [() => new Response("no", { status: 403 }), "forbidden"],
      [() => new Response("<html>login</html>", { status: 200, headers: { "content-type": "text/html" } }), "not_audio"],
      [() => new Response("<html>login</html>", { status: 200, headers: { "content-type": "application/octet-stream" } }), "not_audio"],
      [() => new Response("x", { status: 503 }), "server_error"],
      [() => new Response(null, { status: 302, headers: { location: "https://evil.example/" } }), "bad_status"],
      [() => { throw Object.assign(new Error("t"), { name: "TimeoutError" }); }, "timeout"],
      [() => { throw new TypeError("fetch failed"); }, "network"],
    ];
    for (const [handler, reason] of cases) {
      const r = await probeAudio("https://x/a.mp3", router({ "https://x/a.mp3": handler }));
      expect(r).toMatchObject({ ok: false, reason });
    }
    expect(await probeAudio("https://x/a.mp3", router({ "https://x/a.mp3": audio }))).toEqual({ ok: true, contentType: "audio/mpeg", contentLength: 5000 });
  });
});

describe("fallback to another source's copy of the SAME song", () => {
  it("uses the first source when it works and does not touch the others", async () => {
    const fetchImpl = router({ "https://a/1.mp3": audio });
    const res = await resolvePlayable([v("ahaang", 1, "https://a/1.mp3"), v("giso", 2, "https://g/1.mp3")], { fetchImpl, health: new SourceHealth(), now: () => 0 });
    expect(res.ok && res.version.sourceId).toBe("ahaang");
    expect(res.attempts).toHaveLength(1);
  });

  it("falls through 404 → HTML → working copy", async () => {
    const fetchImpl = router({
      "https://a/1.mp3": html404,
      "https://g/1.mp3": () => new Response("<html>x</html>", { status: 200, headers: { "content-type": "text/html" } }),
      "https://m/1.mp3": audio,
    });
    const res = await resolvePlayable([v("ahaang", 1, "https://a/1.mp3"), v("giso", 2, "https://g/1.mp3"), v("mellody", 3, "https://m/1.mp3")], { fetchImpl, health: new SourceHealth(), now: () => 0 });
    expect(res.ok && res.version.sourceId).toBe("mellody");
    expect(res.attempts.map((a) => a.result)).toEqual(["not_found", "not_audio", "ok"]);
  });

  it("reports every attempt when all sources fail so the app can show a message and keep the queue going", async () => {
    const fetchImpl = router({ "https://a/1.mp3": html404, "https://g/1.mp3": () => new Response("x", { status: 403 }) });
    const res = await resolvePlayable([v("ahaang", 1, "https://a/1.mp3"), v("giso", 2, "https://g/1.mp3")], { fetchImpl, health: new SourceHealth(), now: () => 0 });
    expect(res.ok).toBe(false);
    expect(res.attempts.map((a) => a.result)).toEqual(["not_found", "forbidden"]);
  });

  it("a broken LINK does not mark the whole site unhealthy", async () => {
    const health = new SourceHealth();
    const fetchImpl = router({ "https://a/1.mp3": html404, "https://a/2.mp3": html404, "https://a/3.mp3": html404, "https://a/4.mp3": audio });
    for (const n of [1, 2, 3]) await resolvePlayable([v("ahaang", 1, `https://a/${n}.mp3`)], { fetchImpl, health, now: () => 0 });
    expect(health.isOpen("ahaang", 0)).toBe(false);
    const res = await resolvePlayable([v("ahaang", 1, "https://a/4.mp3")], { fetchImpl, health, now: () => 0 });
    expect(res.ok).toBe(true);
  });

  it("repeated timeouts open the breaker: the sick source is skipped without network calls, then retried after the cool-down", async () => {
    const health = new SourceHealth();
    let hits = 0;
    const fetchImpl = router({
      "https://a/1.mp3": () => { hits += 1; throw Object.assign(new Error("t"), { name: "TimeoutError" }); },
      "https://g/1.mp3": audio,
    });
    const versions = [v("ahaang", 1, "https://a/1.mp3"), v("giso", 2, "https://g/1.mp3")];
    let now = 0;
    for (let i = 0; i < 3; i += 1) await resolvePlayable(versions, { fetchImpl, health, now: () => now });
    expect(hits).toBe(3);
    expect(health.isOpen("ahaang", now)).toBe(true);
    const skipped = await resolvePlayable(versions, { fetchImpl, health, now: () => now });
    expect(hits).toBe(3);
    expect(skipped.attempts[0].result).toBe("skipped_unhealthy");
    expect(skipped.ok && skipped.version.sourceId).toBe("giso");
    now = 11 * 60_000;
    await resolvePlayable(versions, { fetchImpl, health, now: () => now });
    expect(hits).toBe(4);
  });

  it("many different dead links with no successes also open the breaker (site gone or blocking us)", () => {
    const health = new SourceHealth();
    for (let i = 0; i < 8; i += 1) health.recordFailure("mellody", `https://m/${i}.mp3`, "not_found", 0);
    expect(health.isOpen("mellody", 1)).toBe(true);
    const healthy = new SourceHealth();
    healthy.recordSuccess("mellody", 0);
    for (let i = 0; i < 8; i += 1) healthy.recordFailure("mellody", `https://m/${i}.mp3`, "not_found", 0);
    expect(healthy.isOpen("mellody", 1)).toBe(false);
  });

  it("caps the number of probes per resolution", async () => {
    const routes: Record<string, () => Response> = {};
    const versions = Array.from({ length: 10 }, (_, i) => { routes[`https://s${i}/x.mp3`] = html404; return v(`s${i}`, i, `https://s${i}/x.mp3`); });
    const res = await resolvePlayable(versions, { fetchImpl: router(routes), health: new SourceHealth(), now: () => 0, maxAttempts: 3 });
    expect(res.ok).toBe(false);
    expect(res.attempts).toHaveLength(3);
  });
});
