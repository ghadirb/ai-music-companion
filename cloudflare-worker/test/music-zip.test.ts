import { describe, expect, it } from "vitest";
import { buildZip, concat, MP3, rangeFetch } from "./zip-builder";
import {
  DEFAULT_ZIP_LIMITS, findEocd, parseCentralDirectory, readZipListing, sanitizeZipPath,
} from "../src/music/zip";

const enc = new TextEncoder();
const audio = (name: string, n = 1000) => ({ name, data: new Uint8Array(n) });

describe("ZIP path safety (Zip Slip)", () => {
  it("accepts normal relative paths, including Persian names and one trailing slash", () => {
    expect(sanitizeZipPath("Artoosh/01 - Dooset Daram.mp3")).toBe("Artoosh/01 - Dooset Daram.mp3");
    expect(sanitizeZipPath("گلچین/آهنگ اول.mp3")).toBe("گلچین/آهنگ اول.mp3");
    expect(sanitizeZipPath("folder/")).toBe("folder");
  });

  it.each([
    ["../evil.mp3"], ["a/../../evil.mp3"], ["/etc/passwd.mp3"], ["C:/Windows/x.mp3"], ["c:evil.mp3"], ["..\\evil.mp3"], ["a\\b.mp3"],
    ["a//b.mp3"], ["./x.mp3"], ["a/./b.mp3"], [""], ["a/b/c/d/e/f.mp3"], ["bad\u0000name.mp3"], ["x".repeat(300) + ".mp3"], [" lead.mp3"], ["a/b?.mp3"],
  ])("rejects %j", (name) => { expect(sanitizeZipPath(name)).toBeNull(); });
});

describe("central directory parsing", () => {
  const listing = (zip: Uint8Array, limits = DEFAULT_ZIP_LIMITS) => {
    const eocd = findEocd(zip);
    if ("failure" in eocd) return { ok: false as const, reason: eocd.failure };
    return parseCentralDirectory(zip.subarray(eocd.directoryOffset, eocd.directoryOffset + eocd.directorySize), eocd.entries, limits);
  };

  it("lists audio files and reports what it skipped", () => {
    const result = listing(buildZip([
      audio("Artoosh/01 - Dooset Daram.mp3", 5000), audio("Artoosh/02 - Shab.m4a", 4000), audio("cover.jpg"), audio("readme.txt"),
      audio("setup.exe"), audio("inner.zip"), { name: "link.mp3", unixMode: 0o120777 }, { name: "Artoosh/", data: new Uint8Array(0) },
    ]));
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.audio.map((e) => e.path)).toEqual(["Artoosh/01 - Dooset Daram.mp3", "Artoosh/02 - Shab.m4a"]);
    expect(result.audio[0]).toMatchObject({ name: "01 - Dooset Daram.mp3", size: 5000 });
    expect(Object.fromEntries(result.skipped.map((s) => [s.path, s.reason]))).toEqual({
      "cover.jpg": "not_audio", "readme.txt": "not_audio", "setup.exe": "executable", "inner.zip": "nested_archive", "link.mp3": "symlink",
    });
  });

  it("decodes Persian (UTF-8) file names", () => {
    const result = listing(buildZip([audio("آرتوش/گلچین - دوستت دارم.mp3")]));
    expect(result.ok && result.audio[0].name).toBe("گلچین - دوستت دارم.mp3");
  });

  it("refuses the WHOLE archive when any entry tries to escape the destination", () => {
    for (const name of ["../../evil.mp3", "/abs/evil.mp3", "a\\..\\evil.mp3"]) {
      expect(listing(buildZip([audio("fine.mp3"), audio(name)]))).toEqual({ ok: false, reason: "unsafe_path" });
    }
  });

  it("refuses duplicate names (overwrite trick)", () => {
    expect(listing(buildZip([audio("a.mp3"), audio("a.mp3")]))).toEqual({ ok: false, reason: "unsafe_path" });
  });

  it("refuses encrypted archives", () => {
    expect(listing(buildZip([audio("a.mp3"), { name: "b.mp3", data: new Uint8Array(10), encrypted: true }]))).toEqual({ ok: false, reason: "encrypted" });
  });

  it("detects zip bombs by compression ratio and by size limits", () => {
    expect(listing(buildZip([{ name: "boom.mp3", declaredSize: 400 * 1024 ** 2, declaredCompressedSize: 1024, method: 8 }]))).toEqual({ ok: false, reason: "too_large" });
    expect(listing(buildZip([{ name: "boom.mp3", declaredSize: 100 * 1024 ** 2, declaredCompressedSize: 1024, method: 8 }]))).toEqual({ ok: false, reason: "zip_bomb" });
    expect(listing(buildZip([{ name: "boom.mp3", declaredSize: 5000, declaredCompressedSize: 0, method: 8 }]))).toEqual({ ok: false, reason: "zip_bomb" });
    const many = Array.from({ length: 12 }, (_, i) => ({ name: `s${i}.mp3`, declaredSize: 200 * 1024 ** 2, declaredCompressedSize: 190 * 1024 ** 2 }));
    expect(listing(buildZip(many))).toEqual({ ok: false, reason: "too_large" }); // 12 x 200 MB exceeds the 2 GB total
  });

  it("limits the number of entries before parsing them", () => {
    const zip = buildZip(Array.from({ length: 5 }, (_, i) => audio(`s${i}.mp3`)));
    expect(listing(zip, { ...DEFAULT_ZIP_LIMITS, maxEntries: 4 })).toEqual({ ok: false, reason: "too_many_entries" });
  });

  it("flags corrupt, truncated and unsupported archives", () => {
    expect(listing(buildZip([audio("a.mp3")], { corruptDirectorySignature: true }))).toEqual({ ok: false, reason: "corrupt_directory" });
    const good = buildZip([audio("a.mp3")]);
    expect(findEocd(good.subarray(0, good.length - 30))).toEqual({ failure: "no_eocd" });
    expect(findEocd(new Uint8Array(10))).toEqual({ failure: "too_small" });

    const lying = buildZip([audio("a.mp3")]);
    new DataView(lying.buffer).setUint16(lying.length - 22 + 10, 3, true); // EOCD claims 3 entries, directory holds 1
    new DataView(lying.buffer).setUint16(lying.length - 22 + 8, 3, true);
    expect(listing(lying)).toEqual({ ok: false, reason: "truncated" });

    const zip64 = buildZip([audio("a.mp3")]);
    new DataView(zip64.buffer).setUint16(zip64.length - 22 + 10, 0xffff, true);
    expect(findEocd(zip64)).toEqual({ failure: "zip64_unsupported" });

    const multi = buildZip([audio("a.mp3")]);
    new DataView(multi.buffer).setUint16(multi.length - 22 + 4, 1, true);
    expect(findEocd(multi)).toEqual({ failure: "multi_disk" });
  });

  it("rejects unexpected compression methods", () => {
    expect(listing(buildZip([{ name: "a.mp3", data: new Uint8Array(50), method: 99 }]))).toEqual({ ok: false, reason: "corrupt_directory" });
  });

  it("finds the real EOCD even when the archive comment contains a fake signature", () => {
    const zip = buildZip([audio("a.mp3")], { comment: "PK\u0005\u0006 not the real end" });
    const eocd = findEocd(zip);
    expect("failure" in eocd).toBe(false);
    expect(listing(zip)).toMatchObject({ ok: true });
  });
});

describe("reading a remote ZIP with Range requests only", () => {
  const URL_ = "https://ahaaang.musitraf.com/05/07/18/Artoosh%20-%20Goldenhits.zip";

  it("lists contents from the tail without downloading the archive", async () => {
    const big = buildZip([{ name: "Artoosh/01.mp3", data: MP3(300_000) }, { name: "Artoosh/02.mp3", data: MP3(200_000) }]);
    const requested: string[] = [];
    const fetchImpl = rangeFetch({ [URL_]: big });
    const spy = (async (i: any, init: any) => { requested.push(init.headers.range); return fetchImpl(i, init); }) as typeof fetch;
    const result = await readZipListing(URL_, big.length, spy);
    expect(result.ok && result.audio.map((e) => e.name)).toEqual(["01.mp3", "02.mp3"]);
    expect(requested).toHaveLength(1); // directory fit in the tail: one small request
    const [from, to] = requested[0].replace("bytes=", "").split("-").map(Number);
    expect(to - from + 1).toBeLessThanOrEqual(66_000);
    expect(big.length).toBeGreaterThan(500_000);
  });

  it("makes a second ranged request when the central directory is larger than the tail window", async () => {
    const entries = Array.from({ length: 1500 }, (_, i) => audio(`Artoosh/${String(i).padStart(4, "0")} - ${"x".repeat(40)}.mp3`, 10));
    const zip = buildZip(entries);
    const requested: string[] = [];
    const fetchImpl = rangeFetch({ [URL_]: zip });
    const spy = (async (i: any, init: any) => { requested.push(init.headers.range); return fetchImpl(i, init); }) as typeof fetch;
    const result = await readZipListing(URL_, zip.length, spy);
    expect(result.ok && result.audio.length).toBe(1500);
    expect(requested).toHaveLength(2);
  });

  it("never pulls a whole archive from a server that ignores Range", async () => {
    const zip = buildZip([audio("a.mp3")]);
    expect(await readZipListing(URL_, zip.length, rangeFetch({ [URL_]: zip }, { ignoreRange: true }))).toEqual({ ok: false, reason: "range_unsupported" });
  });

  it("maps HTTP and network failures to reasons", async () => {
    const fail = (status: number) => (async () => new Response("x", { status })) as unknown as typeof fetch;
    expect(await readZipListing(URL_, 5000, fail(404))).toEqual({ ok: false, reason: "not_found" });
    expect(await readZipListing(URL_, 5000, fail(403))).toEqual({ ok: false, reason: "forbidden" });
    expect(await readZipListing(URL_, 5000, fail(503))).toEqual({ ok: false, reason: "network" });
    expect(await readZipListing(URL_, 5000, fail(302))).toEqual({ ok: false, reason: "bad_status" });
    const slow = (async () => { throw Object.assign(new Error("t"), { name: "TimeoutError" }); }) as unknown as typeof fetch;
    expect(await readZipListing(URL_, 5000, slow)).toEqual({ ok: false, reason: "timeout" });
    expect(await readZipListing(URL_, 10, fail(200))).toEqual({ ok: false, reason: "too_small" });
  });

  it("rejects a directory offset that points outside the file", async () => {
    const zip = buildZip([audio("a.mp3")]);
    new DataView(zip.buffer).setUint32(zip.length - 22 + 16, zip.length + 5000, true);
    expect(await readZipListing(URL_, zip.length, rangeFetch({ [URL_]: zip }))).toEqual({ ok: false, reason: "corrupt_directory" });
  });

  it("is not fooled by a ZIP whose last bytes are another archive's signature", async () => {
    const inner = buildZip([audio("inner.mp3")]);
    const outer = buildZip([{ name: "carrier.mp3", data: concat([MP3(100), inner]) }]);
    const result = await readZipListing(URL_, outer.length, rangeFetch({ [URL_]: outer }));
    expect(result.ok && result.audio.map((e) => e.name)).toEqual(["carrier.mp3"]);
  });
});

void enc;
