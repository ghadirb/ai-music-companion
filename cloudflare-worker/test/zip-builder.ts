// Builds small ZIP files in memory for tests, including deliberately hostile ones.
export interface TestZipEntry {
  name: string;
  data?: Uint8Array;
  /** Override what the central directory CLAIMS (used for zip-bomb tests; no real data needed). */
  declaredSize?: number;
  declaredCompressedSize?: number;
  encrypted?: boolean;
  method?: number;
  /** Unix mode placed in the external attributes (e.g. 0o120777 for a symlink). */
  unixMode?: number;
}

const enc = new TextEncoder();

export function buildZip(entries: TestZipEntry[], opts: { comment?: string; corruptDirectorySignature?: boolean } = {}): Uint8Array {
  const parts: Uint8Array[] = [];
  const central: Uint8Array[] = [];
  let offset = 0;
  for (const e of entries) {
    const name = enc.encode(e.name);
    const data = e.data ?? new Uint8Array(0);
    const size = e.declaredSize ?? data.length;
    const csize = e.declaredCompressedSize ?? data.length;
    const local = new Uint8Array(30 + name.length);
    const lv = new DataView(local.buffer);
    lv.setUint32(0, 0x04034b50, true); lv.setUint16(4, 20, true); lv.setUint16(6, e.encrypted ? 1 : 0x800, true);
    lv.setUint16(8, e.method ?? 0, true); lv.setUint32(18, csize, true); lv.setUint32(22, size, true); lv.setUint16(26, name.length, true);
    local.set(name, 30);
    const header = new Uint8Array(46 + name.length);
    const hv = new DataView(header.buffer);
    hv.setUint32(0, opts.corruptDirectorySignature ? 0xdeadbeef : 0x02014b50, true);
    hv.setUint16(4, 0x031e, true); hv.setUint16(6, 20, true); hv.setUint16(8, (e.encrypted ? 1 : 0) | 0x800, true); hv.setUint16(10, e.method ?? 0, true);
    hv.setUint32(20, csize, true); hv.setUint32(24, size, true); hv.setUint16(28, name.length, true);
    hv.setUint32(38, ((e.unixMode ?? 0o100644) << 16) >>> 0, true); hv.setUint32(42, offset, true);
    header.set(name, 46);
    parts.push(local, data);
    central.push(header);
    offset += local.length + data.length;
  }
  const directory = concat(central);
  const comment = enc.encode(opts.comment ?? "");
  const eocd = new Uint8Array(22 + comment.length);
  const ev = new DataView(eocd.buffer);
  ev.setUint32(0, 0x06054b50, true); ev.setUint16(8, entries.length, true); ev.setUint16(10, entries.length, true);
  ev.setUint32(12, directory.length, true); ev.setUint32(16, offset, true); ev.setUint16(20, comment.length, true);
  eocd.set(comment, 22);
  return concat([...parts, directory, eocd]);
}

export function concat(chunks: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(chunks.reduce((n, c) => n + c.length, 0));
  let at = 0;
  for (const c of chunks) { out.set(c, at); at += c.length; }
  return out;
}

/** A fetch that serves `body` with proper HTTP Range semantics (206 + only the requested bytes). */
export function rangeFetch(files: Record<string, Uint8Array>, opts: { ignoreRange?: boolean } = {}): typeof fetch {
  return (async (input: any, init?: any) => {
    const body = files[String(input)];
    if (!body) return new Response("nope", { status: 404 });
    const range = init?.headers?.range as string | undefined;
    const m = range?.match(/^bytes=(\d+)-(\d+)$/);
    if (!m || opts.ignoreRange) return new Response(body, { status: 200 });
    const from = Number(m[1]), to = Math.min(Number(m[2]), body.length - 1);
    return new Response(body.subarray(from, to + 1), { status: 206, headers: { "content-range": `bytes ${from}-${to}/${body.length}` } });
  }) as typeof fetch;
}

/** Valid-looking MP3 bytes (ID3 header) so audio probes succeed. */
export const MP3 = (n = 2048) => { const b = new Uint8Array(n); b.set([0x49, 0x44, 0x33, 4, 0]); return b; };
