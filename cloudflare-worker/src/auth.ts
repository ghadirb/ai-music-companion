import { base64UrlBytes, base64UrlDecode, base64UrlEncode, hmacBase64Url } from "./util";

export interface Claims { sub: string; iat?: number; exp: number; iss?: string }

export const SESSION_TTL_SECONDS = 7 * 24 * 60 * 60;
export const ISSUER = "aimusic-gateway";

export async function signSession(secret: string, installationId: string, nowMs: number): Promise<{ token: string; expiresInSeconds: number }> {
  const header = base64UrlEncode(JSON.stringify({ alg: "HS256", typ: "JWT" }));
  const iat = Math.floor(nowMs / 1000);
  const payload = base64UrlEncode(JSON.stringify({ iss: ISSUER, sub: `anon:${installationId}`, iat, exp: iat + SESSION_TTL_SECONDS }));
  const signature = await hmacBase64Url(`${header}.${payload}`, secret);
  return { token: `${header}.${payload}.${signature}`, expiresInSeconds: SESSION_TTL_SECONDS };
}

/** Verifies an HS256 session JWT. Rejects other algorithms (incl. "none"), missing/expired exp and bad signatures. */
export async function verifySession(request: Request, secret: string, nowMs: number): Promise<Claims | null> {
  const bearer = request.headers.get("authorization")?.replace(/^Bearer\s+/i, "");
  if (!bearer || bearer.length > 2048) return null;
  const parts = bearer.split(".");
  if (parts.length !== 3) return null;
  const [header, encodedClaims, signature] = parts;
  try {
    const head = JSON.parse(base64UrlDecode(header)) as { alg?: string };
    if (head.alg !== "HS256") return null;
    const claims = JSON.parse(base64UrlDecode(encodedClaims)) as Partial<Claims>;
    if (typeof claims.sub !== "string" || !claims.sub || typeof claims.exp !== "number") return null;
    if (nowMs >= claims.exp * 1000) return null;
    if (claims.iss !== undefined && claims.iss !== ISSUER) return null;
    const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
    const valid = await crypto.subtle.verify("HMAC", key, base64UrlBytes(signature), new TextEncoder().encode(`${header}.${encodedClaims}`));
    return valid ? (claims as Claims) : null;
  } catch {
    return null;
  }
}
