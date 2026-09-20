const encoder = new TextEncoder();

export function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
      ...headers,
    },
  });
}

export function base64UrlEncodeBytes(value: Uint8Array): string {
  let binary = "";
  value.forEach((byte) => { binary += String.fromCharCode(byte); });
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=/g, "");
}
export function base64UrlEncode(value: string): string { return base64UrlEncodeBytes(encoder.encode(value)); }
export function base64UrlBytes(value: string): Uint8Array {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((value.length + 3) % 4);
  return Uint8Array.from(atob(padded), (char) => char.charCodeAt(0));
}
export function base64UrlDecode(value: string): string { return new TextDecoder().decode(base64UrlBytes(value)); }

export function timingSafeEqual(left: string, right: string): boolean {
  if (left.length !== right.length) return false;
  let difference = 0;
  for (let i = 0; i < left.length; i += 1) difference |= left.charCodeAt(i) ^ right.charCodeAt(i);
  return difference === 0;
}

export async function hmacBase64Url(value: string, secret: string): Promise<string> {
  const key = await crypto.subtle.importKey("raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const signed = await crypto.subtle.sign("HMAC", key, encoder.encode(value));
  return base64UrlEncodeBytes(new Uint8Array(signed));
}

export async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", encoder.encode(value));
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

export type BodyResult<T> = { ok: true; value: T } | { ok: false; error: "unsupported_media_type" | "payload_too_large" | "invalid_json" };

/** Reads a small JSON object body with hard limits (size, content type, shape). */
export async function readJson<T extends object>(request: Request, maxBytes = 16 * 1024): Promise<BodyResult<T>> {
  const type = request.headers.get("content-type") ?? "";
  if (!type.toLowerCase().includes("application/json")) return { ok: false, error: "unsupported_media_type" };
  const declared = Number.parseInt(request.headers.get("content-length") ?? "0", 10);
  if (Number.isFinite(declared) && declared > maxBytes) return { ok: false, error: "payload_too_large" };
  const text = await request.text();
  if (text.length > maxBytes) return { ok: false, error: "payload_too_large" };
  try {
    const parsed = JSON.parse(text);
    if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) return { ok: false, error: "invalid_json" };
    return { ok: true, value: parsed as T };
  } catch {
    return { ok: false, error: "invalid_json" };
  }
}
