import { jalaliKey, pad2, type JalaliDate } from "./jalali";

/**
 * A music source. Everything here is DATA (stored/overridable remotely), so priority, enabling, URL templates and limits
 * can change without shipping a new Android build. Templates use {YYYY} (4-digit Jalali year), {YY} (2-digit), {MM}, {DD}.
 *
 * Verified against the live servers (Oct 2026): all four organise folders by JALALI date.
 *  - ahaang : https://ahaaang.musitraf.com/05/07/19/            (2-digit year)
 *  - giso   : https://dl.gisomusic.com/Music/1405/07/19/        (4-digit year)
 *  - mellody: https://mellody98.musitraf.com/music/1405/07/19/  (4-digit year; the site root is a login page, dated listings are open)
 *  - roz    : https://dl.rozmusic.com/Music/1405/07/19/         (4-digit year, may contain sub-folders)
 */
export interface MusicSource {
  id: string;
  name: string;
  /** Lower number = tried first. */
  priority: number;
  enabled: boolean;
  baseUrl: string;
  dayPathTemplate: string;
  /** Scan ZIP archives ("گلچین خوانندگان") on this source. */
  scanZips: boolean;
  /** Descend one level into sub-folders of a day folder (album/collection folders). */
  scanSubfolders: boolean;
  /** Safety caps per day folder. */
  maxEntriesPerDay: number;
}

export const DEFAULT_SOURCES: MusicSource[] = [
  { id: "ahaang", name: "آهنگ", priority: 1, enabled: true, baseUrl: "https://ahaaang.musitraf.com", dayPathTemplate: "/{YY}/{MM}/{DD}/", scanZips: true, scanSubfolders: false, maxEntriesPerDay: 400 },
  { id: "giso", name: "گیسو موزیک", priority: 2, enabled: true, baseUrl: "https://dl.gisomusic.com", dayPathTemplate: "/Music/{YYYY}/{MM}/{DD}/", scanZips: false, scanSubfolders: false, maxEntriesPerDay: 400 },
  { id: "mellody", name: "ملودی ۹۸", priority: 3, enabled: true, baseUrl: "https://mellody98.musitraf.com", dayPathTemplate: "/music/{YYYY}/{MM}/{DD}/", scanZips: false, scanSubfolders: false, maxEntriesPerDay: 400 },
  { id: "roz", name: "روز موزیک", priority: 4, enabled: true, baseUrl: "https://dl.rozmusic.com", dayPathTemplate: "/Music/{YYYY}/{MM}/{DD}/", scanZips: false, scanSubfolders: true, maxEntriesPerDay: 400 },
];

const SOURCE_ID = /^[a-z0-9_-]{1,32}$/;
const ALLOWED_TEMPLATE = /^\/[A-Za-z0-9_./{}-]*$/;

/** Validates one source object coming from remote configuration. Returns null when anything is unsafe or malformed. */
export function validateSource(raw: unknown): MusicSource | null {
  if (!raw || typeof raw !== "object") return null;
  const s = raw as Partial<MusicSource>;
  if (typeof s.id !== "string" || !SOURCE_ID.test(s.id)) return null;
  if (typeof s.name !== "string" || !s.name || s.name.length > 64) return null;
  if (typeof s.priority !== "number" || !Number.isFinite(s.priority) || s.priority < 0 || s.priority > 1000) return null;
  if (typeof s.baseUrl !== "string") return null;
  let url: URL;
  try { url = new URL(s.baseUrl); } catch { return null; }
  // https only, no credentials, no query/fragment: the scanner must never be pointed at arbitrary schemes or internal hosts.
  if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash || url.pathname !== "/") return null;
  if (isInternalHost(url.hostname)) return null;
  if (typeof s.dayPathTemplate !== "string" || !ALLOWED_TEMPLATE.test(s.dayPathTemplate) || s.dayPathTemplate.includes("..")) return null;
  if (!/\{MM\}/.test(s.dayPathTemplate) || !/\{DD\}/.test(s.dayPathTemplate) || !/\{YY(YY)?\}/.test(s.dayPathTemplate)) return null;
  const cap = typeof s.maxEntriesPerDay === "number" ? Math.floor(s.maxEntriesPerDay) : 400;
  return {
    id: s.id, name: s.name, priority: s.priority, enabled: s.enabled !== false,
    baseUrl: `${url.protocol}//${url.host}`, dayPathTemplate: s.dayPathTemplate,
    scanZips: s.scanZips === true, scanSubfolders: s.scanSubfolders === true,
    maxEntriesPerDay: Math.min(2000, Math.max(10, cap)),
  };
}

export function isInternalHost(host: string): boolean {
  const h = host.toLowerCase();
  if (h === "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")) return true;
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(h)) return true; // raw IPv4 is never a legitimate music host here
  if (h.includes(":") || h.startsWith("[")) return true; // IPv6 literals
  return !h.includes(".");
}

/** Sorted (priority asc) list of valid, enabled sources. Falls back to defaults when the stored configuration is unusable. */
export function activeSources(configured: unknown): MusicSource[] {
  const list = Array.isArray(configured) ? configured.map(validateSource).filter((s): s is MusicSource => s !== null) : [];
  const base = list.length ? list : DEFAULT_SOURCES;
  const seen = new Set<string>();
  return base
    .filter((s) => s.enabled && !seen.has(s.id) && !!seen.add(s.id))
    .sort((a, b) => a.priority - b.priority);
}

export function dayPath(source: MusicSource, d: JalaliDate): string {
  return source.dayPathTemplate
    .replace("{YYYY}", String(d.jy))
    .replace("{YY}", pad2(d.jy % 100))
    .replace("{MM}", pad2(d.jm))
    .replace("{DD}", pad2(d.jd));
}

export function dayUrl(source: MusicSource, d: JalaliDate): string {
  return `${source.baseUrl}${dayPath(source, d)}`;
}

export const dayKey = (source: MusicSource, d: JalaliDate) => `${source.id}:${jalaliKey(d)}`;
