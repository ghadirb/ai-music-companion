// Jalali (Solar Hijri) <-> Gregorian conversion, based on the well-known 33-year-cycle algorithm (jalaali-js, MIT).
// The music sources organise folders by Jalali date, and "today" must be computed in Asia/Tehran, not UTC.

export interface JalaliDate { jy: number; jm: number; jd: number }
export interface GregorianDate { gy: number; gm: number; gd: number }

const BREAKS = [-61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210, 1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178];
const div = (a: number, b: number) => Math.trunc(a / b);
const mod = (a: number, b: number) => a - Math.trunc(a / b) * b;

function jalCal(jy: number, withoutLeap = false): { leap: number; gy: number; march: number } {
  const bl = BREAKS.length;
  const gy = jy + 621;
  let leapJ = -14;
  let jp = BREAKS[0];
  let jump = 0;
  if (jy < jp || jy >= BREAKS[bl - 1]) throw new RangeError(`Invalid Jalali year ${jy}`);
  for (let i = 1; i < bl; i += 1) {
    const jm = BREAKS[i];
    jump = jm - jp;
    if (jy < jm) break;
    leapJ += div(jump, 33) * 8 + div(mod(jump, 33), 4);
    jp = jm;
  }
  let n = jy - jp;
  leapJ += div(n, 33) * 8 + div(mod(n, 33) + 3, 4);
  if (mod(jump, 33) === 4 && jump - n === 4) leapJ += 1;
  const leapG = div(gy, 4) - div((div(gy, 100) + 1) * 3, 4) - 150;
  const march = 20 + leapJ - leapG;
  if (withoutLeap) return { leap: 0, gy, march };
  if (jump - n < 6) n = n - jump + div(jump + 4, 33) * 33;
  let leap = mod(mod(n + 1, 33) - 1, 4);
  if (leap === -1) leap = 4;
  return { leap, gy, march };
}

function g2d(gy: number, gm: number, gd: number): number {
  let d = div((gy + div(gm - 8, 6) + 100100) * 1461, 4) + div(153 * mod(gm + 9, 12) + 2, 5) + gd - 34840408;
  d = d - div(div(gy + 100100 + div(gm - 8, 6), 100) * 3, 4) + 752;
  return d;
}

function d2g(jdn: number): GregorianDate {
  let j = 4 * jdn + 139361631;
  j += div(div(4 * jdn + 183187720, 146097) * 3, 4) * 4 - 3908;
  const i = div(mod(j, 1461), 4) * 5 + 308;
  const gd = div(mod(i, 153), 5) + 1;
  const gm = mod(div(i, 153), 12) + 1;
  const gy = div(j, 1461) - 100100 + div(8 - gm, 6);
  return { gy, gm, gd };
}

function j2d(jy: number, jm: number, jd: number): number {
  const r = jalCal(jy, true);
  return g2d(r.gy, 3, r.march) + (jm - 1) * 31 - div(jm, 7) * (jm - 7) + jd - 1;
}

function d2j(jdn: number): JalaliDate {
  const gy = d2g(jdn).gy;
  let jy = gy - 621;
  const r = jalCal(jy, false);
  const jdn1f = g2d(gy, 3, r.march);
  let k = jdn - jdn1f;
  if (k >= 0) {
    if (k <= 185) return { jy, jm: 1 + div(k, 31), jd: mod(k, 31) + 1 };
    k -= 186;
  } else {
    jy -= 1;
    k += 179;
    if (r.leap === 1) k += 1;
  }
  return { jy, jm: 7 + div(k, 30), jd: mod(k, 30) + 1 };
}

export function gregorianToJalali(gy: number, gm: number, gd: number): JalaliDate { return d2j(g2d(gy, gm, gd)); }
export function jalaliToGregorian(jy: number, jm: number, jd: number): GregorianDate { return d2g(j2d(jy, jm, jd)); }
export function isJalaliLeap(jy: number): boolean { return jalCal(jy, false).leap === 0; }

export function jalaliMonthLength(jy: number, jm: number): number {
  if (jm <= 6) return 31;
  if (jm <= 11) return 30;
  return isJalaliLeap(jy) ? 30 : 29;
}

/** Calendar date in Asia/Tehran for an instant (Iran has no DST since 2022; Intl handles historic rules too). */
export function tehranGregorianDate(nowMs: number): GregorianDate {
  const parts = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Tehran", year: "numeric", month: "2-digit", day: "2-digit" })
    .formatToParts(new Date(nowMs));
  const pick = (type: string) => Number(parts.find((p) => p.type === type)?.value);
  return { gy: pick("year"), gm: pick("month"), gd: pick("day") };
}

export function jalaliToday(nowMs: number): JalaliDate {
  const g = tehranGregorianDate(nowMs);
  return gregorianToJalali(g.gy, g.gm, g.gd);
}

/** Jalali dates for today and the previous `days - 1` days (newest first). Walks the Gregorian calendar, so month/year/leap boundaries are exact. */
export function recentJalaliDays(nowMs: number, days: number): JalaliDate[] {
  const g = tehranGregorianDate(nowMs);
  const base = Date.UTC(g.gy, g.gm - 1, g.gd);
  const out: JalaliDate[] = [];
  for (let i = 0; i < days; i += 1) {
    const d = new Date(base - i * 86_400_000);
    out.push(gregorianToJalali(d.getUTCFullYear(), d.getUTCMonth() + 1, d.getUTCDate()));
  }
  return out;
}

export const pad2 = (n: number) => String(n).padStart(2, "0");
export const jalaliKey = (d: JalaliDate) => `${d.jy}-${pad2(d.jm)}-${pad2(d.jd)}`;
export function jalaliToIso(d: JalaliDate): string {
  const g = jalaliToGregorian(d.jy, d.jm, d.jd);
  return `${g.gy}-${pad2(g.gm)}-${pad2(g.gd)}`;
}
