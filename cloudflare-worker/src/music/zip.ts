// Safe ZIP *listing* for the "گلچین خوانندگان" feature.
// The Worker never downloads or extracts an archive: it reads the central directory with HTTP Range requests
// (a few KB), validates every entry, and reports which audio files the archive contains. Extraction happens
// on the phone (see ZipSafeExtractor.kt) with the same rules.

import { extensionOf, isAudioName } from "./listing";

export type ZipFailure =
  | "too_small" | "no_eocd" | "zip64_unsupported" | "multi_disk" | "corrupt_directory" | "truncated"
  | "encrypted" | "too_many_entries" | "too_large" | "zip_bomb" | "unsafe_path"
  | "range_unsupported" | "not_found" | "forbidden" | "timeout" | "network" | "bad_status";

export interface ZipLimits {
  maxEntries: number;
  /** Sum of uncompressed sizes of the listed audio files. */
  maxTotalBytes: number;
  maxEntryBytes: number;
  /** uncompressed/compressed ratio above which an entry is treated as a bomb (audio barely compresses). */
  maxRatio: number;
  maxPathLength: number;
  maxDepth: number;
  /** Largest central directory we are willing to fetch. */
  maxDirectoryBytes: number;
}

export const DEFAULT_ZIP_LIMITS: ZipLimits = {
  maxEntries: 2000,
  maxTotalBytes: 2 * 1024 ** 3,
  maxEntryBytes: 300 * 1024 ** 2,
  maxRatio: 25,
  maxPathLength: 255,
  maxDepth: 4,
  maxDirectoryBytes: 1024 * 1024,
};

export interface ZipEntry {
  /** Sanitised relative path inside the archive, '/'-separated. */
  path: string;
  /** Last path segment: what the user sees. */
  name: string;
  size: number;
  compressedSize: number;
  method: number;
}

export type ZipListing =
  | { ok: true; audio: ZipEntry[]; skipped: { path: string; reason: "not_audio" | "executable" | "nested_archive" | "symlink" }[] }
  | { ok: false; reason: ZipFailure };

const EXECUTABLE = new Set(["exe", "dll", "bat", "cmd", "com", "scr", "msi", "apk", "jar", "sh", "ps1", "vbs", "js", "jse", "wsf", "lnk", "so", "dex", "bin", "app", "pif", "reg"]);
const NESTED = new Set(["zip", "rar", "7z", "gz", "tgz", "tar", "bz2", "xz", "iso"]);

/** Returns the cleaned path, or null if it could escape the destination folder or is otherwise unsafe (Zip Slip). */
export function sanitizeZipPath(raw: string, limits: ZipLimits = DEFAULT_ZIP_LIMITS): string | null {
  if (!raw || raw.length > limits.maxPathLength) return null;
  if (/[\u0000-\u001f\u007f]/.test(raw)) return null;
  if (raw.includes("\\")) return null;                 // Windows separators are how traversal slips through
  if (raw.startsWith("/") || /^[a-zA-Z]:/.test(raw)) return null; // absolute paths and drive letters
  const parts = raw.split("/").filter((p, i, a) => !(p === "" && i === a.length - 1)); // allow one trailing slash
  if (!parts.length || parts.length > limits.maxDepth) return null;
  for (const part of parts) {
    if (part === "" || part === "." || part === "..") return null;
    if (part.trim() !== part || /[<>:"|?*]/.test(part)) return null;
  }
  return parts.join("/");
}

const u16 = (v: DataView, o: number) => v.getUint16(o, true);
const u32 = (v: DataView, o: number) => v.getUint32(o, true);

export interface Eocd { entries: number; directorySize: number; directoryOffset: number }

/** Finds the End Of Central Directory record in the tail of a ZIP. */
export function findEocd(tail: Uint8Array): Eocd | { failure: ZipFailure } {
  if (tail.length < 22) return { failure: "too_small" };
  const view = new DataView(tail.buffer, tail.byteOffset, tail.byteLength);
  for (let i = tail.length - 22; i >= 0; i -= 1) {
    if (u32(view, i) !== 0x06054b50) continue;
    const commentLength = u16(view, i + 20);
    if (i + 22 + commentLength !== tail.length) continue; // a signature inside file data / a longer comment: keep looking
    const disk = u16(view, i + 4), cdDisk = u16(view, i + 6);
    const entriesHere = u16(view, i + 8), entries = u16(view, i + 10);
    const directorySize = u32(view, i + 12), directoryOffset = u32(view, i + 16);
    if (entries === 0xffff || directorySize === 0xffffffff || directoryOffset === 0xffffffff) return { failure: "zip64_unsupported" };
    if (disk !== 0 || cdDisk !== 0 || entriesHere !== entries) return { failure: "multi_disk" };
    return { entries, directorySize, directoryOffset };
  }
  // A ZIP64 archive keeps a locator right before the (possibly oversized) EOCD; without a plain EOCD we cannot continue anyway.
  return { failure: "no_eocd" };
}

function decodeName(bytes: Uint8Array): string {
  // The UTF-8 flag (bit 11) is honoured implicitly: many Persian packers write UTF-8 WITHOUT the flag, so UTF-8 is always tried first.
  try { return new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(bytes); } catch { /* not UTF-8 */ }
  return Array.from(bytes, (b) => String.fromCharCode(b)).join("");
}

/** Parses and validates a central directory. Pure function: no network, fully unit-tested. */
export function parseCentralDirectory(cd: Uint8Array, expectedEntries: number, limits: ZipLimits = DEFAULT_ZIP_LIMITS): ZipListing {
  if (expectedEntries > limits.maxEntries) return { ok: false, reason: "too_many_entries" };
  const view = new DataView(cd.buffer, cd.byteOffset, cd.byteLength);
  const audio: ZipEntry[] = [];
  const skipped: { path: string; reason: "not_audio" | "executable" | "nested_archive" | "symlink" }[] = [];
  const seen = new Set<string>();
  let offset = 0, total = 0;
  for (let n = 0; n < expectedEntries; n += 1) {
    if (offset + 46 > cd.length) return { ok: false, reason: "truncated" };
    if (u32(view, offset) !== 0x02014b50) return { ok: false, reason: "corrupt_directory" };
    const flags = u16(view, offset + 8), method = u16(view, offset + 10);
    const compressedSize = u32(view, offset + 20), size = u32(view, offset + 24);
    const nameLength = u16(view, offset + 28), extraLength = u16(view, offset + 30), commentLength = u16(view, offset + 32);
    const externalAttributes = u32(view, offset + 38);
    const next = offset + 46 + nameLength + extraLength + commentLength;
    if (next > cd.length) return { ok: false, reason: "truncated" };
    if (compressedSize === 0xffffffff || size === 0xffffffff) return { ok: false, reason: "zip64_unsupported" };

    const rawName = decodeName(cd.subarray(offset + 46, offset + 46 + nameLength));
    offset = next;
    const path = sanitizeZipPath(rawName, limits);
    // One hostile path makes the whole archive untrustworthy: refuse it instead of "cleaning" it.
    if (path === null) return { ok: false, reason: "unsafe_path" };
    if (rawName.endsWith("/")) continue; // directory entry
    if ((flags & 0x1) !== 0) return { ok: false, reason: "encrypted" };
    if (seen.has(path)) return { ok: false, reason: "unsafe_path" }; // duplicate names are a classic overwrite trick
    seen.add(path);

    const unixMode = externalAttributes >>> 16;
    if ((unixMode & 0xf000) === 0xa000) { skipped.push({ path, reason: "symlink" }); continue; }
    const ext = extensionOf(path);
    if (EXECUTABLE.has(ext)) { skipped.push({ path, reason: "executable" }); continue; }
    if (NESTED.has(ext)) { skipped.push({ path, reason: "nested_archive" }); continue; }
    if (!isAudioName(path)) { skipped.push({ path, reason: "not_audio" }); continue; }

    if (method !== 0 && method !== 8) return { ok: false, reason: "corrupt_directory" }; // only stored/deflate are expected
    if (size > limits.maxEntryBytes) return { ok: false, reason: "too_large" };
    if (size > 0 && (compressedSize === 0 || size / compressedSize > limits.maxRatio)) return { ok: false, reason: "zip_bomb" };
    total += size;
    if (total > limits.maxTotalBytes) return { ok: false, reason: "too_large" };
    audio.push({ path, name: path.slice(path.lastIndexOf("/") + 1), size, compressedSize, method });
  }
  return { ok: true, audio, skipped };
}

async function rangeGet(url: string, from: number, to: number, fetchImpl: typeof fetch, timeoutMs: number): Promise<{ ok: true; bytes: Uint8Array } | { ok: false; reason: ZipFailure }> {
  let res: Response;
  try {
    res = await fetchImpl(url, { method: "GET", headers: { range: `bytes=${from}-${to}`, "user-agent": "NovasaMusicBot/1.0" }, redirect: "manual", signal: AbortSignal.timeout(timeoutMs) });
  } catch (e) {
    return { ok: false, reason: e instanceof Error && (e.name === "TimeoutError" || e.name === "AbortError") ? "timeout" : "network" };
  }
  if (res.status === 404 || res.status === 410) return { ok: false, reason: "not_found" };
  if (res.status === 401 || res.status === 403) return { ok: false, reason: "forbidden" };
  if (res.status >= 500) return { ok: false, reason: "network" };
  if (res.status === 200) { await res.body?.cancel().catch(() => undefined); return { ok: false, reason: "range_unsupported" }; } // never pull a whole archive
  if (res.status !== 206) return { ok: false, reason: "bad_status" };
  try { return { ok: true, bytes: new Uint8Array(await res.arrayBuffer()) }; } catch { return { ok: false, reason: "network" }; }
}

/**
 * Lists the audio files of a remote ZIP using two small Range requests (tail, then the central directory if it did
 * not fit in the tail). `size` is the byte size from the directory listing.
 */
export async function readZipListing(url: string, size: number, fetchImpl: typeof fetch, limits: ZipLimits = DEFAULT_ZIP_LIMITS, timeoutMs = 10_000): Promise<ZipListing> {
  if (!Number.isFinite(size) || size < 22) return { ok: false, reason: "too_small" };
  const tailLength = Math.min(size, 66_000);
  const tail = await rangeGet(url, size - tailLength, size - 1, fetchImpl, timeoutMs);
  if (!tail.ok) return tail;
  const eocd = findEocd(tail.bytes);
  if ("failure" in eocd) return { ok: false, reason: eocd.failure };
  if (eocd.directorySize > limits.maxDirectoryBytes) return { ok: false, reason: "too_large" };
  if (eocd.directoryOffset + eocd.directorySize > size) return { ok: false, reason: "corrupt_directory" };

  const tailStart = size - tail.bytes.length;
  let directory: Uint8Array;
  if (eocd.directoryOffset >= tailStart) {
    directory = tail.bytes.subarray(eocd.directoryOffset - tailStart, eocd.directoryOffset - tailStart + eocd.directorySize);
  } else {
    const fetched = await rangeGet(url, eocd.directoryOffset, eocd.directoryOffset + eocd.directorySize - 1, fetchImpl, timeoutMs);
    if (!fetched.ok) return fetched;
    directory = fetched.bytes;
  }
  if (directory.length < eocd.directorySize) return { ok: false, reason: "truncated" };
  return parseCentralDirectory(directory, eocd.entries, limits);
}
