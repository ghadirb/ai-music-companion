import { extensionOf } from "./listing";

/** Folds the many ways Persian/Arabic text is typed into one comparable form (ی/ي/ى, ک/ك, digits, ZWNJ, diacritics, punctuation). */
export function normalizeText(input: string): string {
  return input
    .normalize("NFKC")
    .replace(/[\u064B-\u065F\u0670\u0640]/g, "")
    .replace(/[\u064A\u0649]/g, "\u06CC")
    .replace(/\u0643/g, "\u06A9")
    .replace(/[\u06F0-\u06F9]/g, (c) => String(c.charCodeAt(0) - 0x06F0))
    .replace(/[\u0660-\u0669]/g, (c) => String(c.charCodeAt(0) - 0x0660))
    .replace(/[\u200C\u200B\u200D\u200E\u200F\u202A-\u202E\u2066-\u2069\uFEFF]/g, " ")
    .toLowerCase()
    .replace(/_+/g, " ")
    .replace(/[^\p{L}\p{N}\s]/gu, " ")
    .replace(/\s+/g, " ")
    .trim();
}

const KNOWN_BITRATES = new Set([48, 64, 96, 112, 128, 160, 192, 224, 256, 320]);
const SITE_TAG = /[\[(]?\s*(?:https?:\/\/)?(?:www\.)?[a-z0-9-]+\.(?:com|ir|net|org|info)\s*[\])]?/gi;

export interface ParsedTrackName {
  /** Display strings (original spelling, trimmed). */
  artist: string | null;
  title: string;
  /** kbps when the file name states it; null when unknown (typically the original/highest quality file). */
  quality: number | null;
  ext: string;
}

function stripQualityMarker(text: string): { text: string; quality: number | null } {
  let current = text.trim();
  let quality: number | null = null;
  for (let guard = 0; guard < 3; guard += 1) {
    const patterns: RegExp[] = [/\s*\[(\d{2,3})\s*(?:kbps)?\]\s*$/i, /\s*\((\d{2,3})\s*(?:kbps)?\)\s*$/i, /\s+-\s+(\d{2,3})\s*(?:kbps)?\s*$/i, /\s+(\d{2,3})\s*kbps\s*$/i];
    let changed = false;
    for (const pattern of patterns) {
      const m = current.match(pattern);
      if (m && KNOWN_BITRATES.has(Number(m[1]))) {
        quality = quality ?? Number(m[1]);
        current = current.slice(0, m.index).trim();
        changed = true;
        break;
      }
    }
    if (!changed) break;
  }
  return { text: current, quality };
}

/** "Artist - Title (128).mp3" -> parts. Splits on the FIRST " - " only; titles may contain hyphens. */
export function parseTrackName(fileName: string): ParsedTrackName {
  const ext = extensionOf(fileName);
  const base = ext ? fileName.slice(0, -(ext.length + 1)) : fileName;
  const { text, quality } = stripQualityMarker(base.replace(/_+/g, " "));
  const cleaned = text.replace(SITE_TAG, " ").replace(/\s+/g, " ").trim();
  const i = cleaned.indexOf(" - ");
  if (i <= 0 || i >= cleaned.length - 3) return { artist: null, title: cleaned || base.trim(), quality, ext };
  return { artist: cleaned.slice(0, i).trim(), title: cleaned.slice(i + 3).trim(), quality, ext };
}

const ARTIST_SPLIT = /\s+(?:x|and|ft\.?|feat\.?|featuring|with|\u0648)\s+|\s*[&,+\/]\s*|\s+\u0648\s+/i;

/** Individual artists of a credit like "A x B & C". */
export function splitArtists(credit: string): string[] {
  return credit.split(ARTIST_SPLIT).map((s) => s.trim()).filter(Boolean);
}

/** Stable identity of a song across sources: order of featured artists must not matter. */
export function trackKey(artist: string | null, title: string): string {
  const artists = artist ? splitArtists(artist).map(normalizeText).filter(Boolean).sort() : [];
  return `${artists.join("+")}|${normalizeText(title)}`;
}

export interface ParsedZipName { artist: string | null; collection: string }

/** "Artoosh - Goldenhits.zip" -> { artist: "Artoosh", collection: "Goldenhits" }. Without " - " the artist is unknown. */
export function parseZipName(fileName: string): ParsedZipName {
  const p = parseTrackName(fileName);
  return { artist: p.artist, collection: p.title };
}

/** Singer identity for grouping archives: first credited artist, normalized. */
export function artistKey(artist: string | null): string {
  if (!artist) return "";
  const first = splitArtists(artist)[0] ?? artist;
  return normalizeText(first);
}
