import { describe, expect, it } from "vitest";
import { DEFAULT_SOURCES, mirrorFetch, validateSource, type MusicSource } from "../src/music/sources";

const mellody = DEFAULT_SOURCES.find((s) => s.id === "mellody")!;
const giso = DEFAULT_SOURCES.find((s) => s.id === "giso")!;

describe("source mirrors (hosts that redirect datacenter traffic)", () => {
  it("rewrites only URLs of a source that has a mirror, keeping path and encoding intact", async () => {
    const seen: string[] = [];
    const base = (async (input: any) => { seen.push(String(input)); return new Response("ok"); }) as typeof fetch;
    const f = mirrorFetch([mellody, giso], base);
    await f(`${mellody.baseUrl}/music/1405/07/19/A%20-%20B.mp3`);
    await f(`${giso.baseUrl}/Music/1405/07/19/x.mp3`);
    await f("https://elsewhere.example/music/1405/07/19/");
    expect(seen).toEqual([
      "https://dl.melody98.ir/music/1405/07/19/A%20-%20B.mp3",
      "https://dl.gisomusic.com/Music/1405/07/19/x.mp3",
      "https://elsewhere.example/music/1405/07/19/",
    ]);
  });

  it("is a no-op when no source declares a mirror", () => {
    const base = (async () => new Response("")) as typeof fetch;
    expect(mirrorFetch([giso], base)).toBe(base);
  });

  it("does not rewrite look-alike hosts (prefix must include the slash)", async () => {
    const seen: string[] = [];
    const base = (async (input: any) => { seen.push(String(input)); return new Response(""); }) as typeof fetch;
    await mirrorFetch([mellody], base)("https://mellody98.musitraf.com.evil.example/x");
    expect(seen).toEqual(["https://mellody98.musitraf.com.evil.example/x"]);
  });

  it("validates the mirror like the main host: https only, no credentials/paths, no internal hosts", () => {
    const ok = (extra: Partial<MusicSource>) => validateSource({ ...giso, ...extra });
    expect(ok({ fetchBaseUrl: "https://dl.melody98.ir" })?.fetchBaseUrl).toBe("https://dl.melody98.ir");
    expect(ok({ fetchBaseUrl: "http://dl.melody98.ir" })).toBeNull();
    expect(ok({ fetchBaseUrl: "https://user:pw@dl.melody98.ir" })).toBeNull();
    expect(ok({ fetchBaseUrl: "https://dl.melody98.ir/music" })).toBeNull();
    expect(ok({ fetchBaseUrl: "https://127.0.0.1" })).toBeNull();
    expect(ok({ fetchBaseUrl: "https://localhost" })).toBeNull();
    expect(ok({ fetchBaseUrl: 42 as unknown as string })).toBeNull();
    expect(ok({ fetchBaseUrl: "" })?.fetchBaseUrl).toBeUndefined();
  });
});
