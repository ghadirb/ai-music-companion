// Parser for nginx/Apache "Index of /" directory listings.
// Entry names are taken from the href (the visible text is truncated by nginx with "..>" for long names).

export interface ListingEntry {
  /** Decoded file or folder name, no slashes. */
  name: string;
  isDir: boolean;
  /** Bytes, or null for directories / unknown. */
  size: number | null;
  /** Server-reported modification time as "YYYY-MM-DDTHH:mm" WITHOUT a timezone (the servers do not state one). */
  modified: string | null;
}

export type ListingResult =
  | { kind: "listing"; entries: ListingEntry[] }
  | { kind: "not_a_listing"; reason: "html_page" | "empty" };

const MONTHS: Record<string, string> = { jan: "01", feb: "02", mar: "03", apr: "04", may: "05", jun: "06", jul: "07", aug: "08", sep: "09", oct: "10", nov: "11", dec: "12" };
const ROW = /<a href="([^"]*)">[^<]*<\/a>\s*(?:(\d{2})-([A-Za-z]{3})-(\d{4}) (\d{2}):(\d{2})\s+(\d+|-))?/g;

export function parseAutoindex(html: string): ListingResult {
  if (!html || !html.trim()) return { kind: "not_a_listing", reason: "empty" };
  // A real autoindex always carries this title. Login pages, error pages and SPA shells do not.
  if (!/<title>\s*Index of \//i.test(html)) return { kind: "not_a_listing", reason: "html_page" };

  const entries: ListingEntry[] = [];
  for (const m of html.matchAll(ROW)) {
    const href = m[1];
    if (!href || href === "../" || href.startsWith("/") || href.startsWith("?") || href.startsWith("#") || /^[a-z][a-z0-9+.-]*:/i.test(href)) continue;
    const isDir = href.endsWith("/");
    let name: string;
    try { name = decodeURIComponent(isDir ? href.slice(0, -1) : href); } catch { continue; }
    if (!name || name.includes("/") || name.includes("\\") || name === "." || name === ".." || /[\u0000-\u001f]/.test(name)) continue;
    const modified = m[2] && MONTHS[m[3].toLowerCase()] ? `${m[4]}-${MONTHS[m[3].toLowerCase()]}-${m[2]}T${m[5]}:${m[6]}` : null;
    const size = !isDir && m[7] && m[7] !== "-" ? Number.parseInt(m[7], 10) : null;
    entries.push({ name, isDir, size: Number.isFinite(size) ? size : null, modified });
  }
  return { kind: "listing", entries };
}

export const AUDIO_EXTENSIONS = new Set(["mp3", "m4a", "aac", "flac", "ogg", "opus", "wav"]);

export function extensionOf(name: string): string {
  const i = name.lastIndexOf(".");
  return i > 0 && i < name.length - 1 ? name.slice(i + 1).toLowerCase() : "";
}

export const isAudioName = (name: string) => AUDIO_EXTENSIONS.has(extensionOf(name));
export const isZipName = (name: string) => extensionOf(name) === "zip";
