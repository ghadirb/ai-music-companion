export interface Env {
  GAPGPT_API_KEY: string;
  JWT_SIGNING_SECRET: string;
  MYKET_ACCESS_TOKEN?: string;
  MYKET_PACKAGE_NAME?: string;
  PURCHASE_ENTITLEMENTS?: KVNamespace;
}

type Claims = { sub?: string; exp?: number };
type EmbeddingRequest = {
  model?: "text-embedding-3-small" | "text-embedding-3-large" | "gemini-embedding-001";
  input: string;
};
type MyketVerificationRequest = { sku?: string; tokenId?: string; developerPayload?: string };

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status,
  headers: { "content-type": "application/json", "cache-control": "no-store" },
});

/**
 * Commercial boundary: provider and Myket access tokens only exist here.
 * This Worker must be connected to a real sign-in service that issues the
 * short-lived JWTs. It deliberately refuses static app tokens.
 */
export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const path = new URL(request.url).pathname;
    if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);

    const claims = await authenticatedClaims(request, env.JWT_SIGNING_SECRET);
    if (!claims?.sub) return json({ error: "unauthorized" }, 401);

    if (path === "/v1/music-embedding") return createEmbedding(request, env);
    if (path === "/v1/myket/purchase-nonce") return issuePurchaseNonce(request, env, claims);
    if (path === "/v1/myket/verify") return verifyMyketPurchase(request, env, claims);
    return json({ error: "not_found" }, 404);
  },
};

async function createEmbedding(request: Request, env: Env): Promise<Response> {
  const payload = await request.json<EmbeddingRequest>().catch(() => null);
  if (!payload?.input || payload.input.length > 6_000) return json({ error: "invalid_input" }, 400);

  const upstream = await fetch("https://api.gapgpt.app/v1/embeddings", {
    method: "POST",
    headers: { authorization: `Bearer ${env.GAPGPT_API_KEY}`, "content-type": "application/json" },
    body: JSON.stringify({ model: payload.model ?? "text-embedding-3-small", input: payload.input }),
  });
  return json(await upstream.json(), upstream.status);
}

async function issuePurchaseNonce(request: Request, env: Env, claims: Claims): Promise<Response> {
  if (!env.MYKET_ACCESS_TOKEN || !env.MYKET_PACKAGE_NAME || !env.PURCHASE_ENTITLEMENTS) {
    return json({ error: "payment_not_configured" }, 503);
  }
  const body = await request.json<{ sku?: string }>().catch(() => null);
  if (!body?.sku || body.sku.length > 100) return json({ error: "invalid_sku" }, 400);

  const payload = base64UrlEncode(JSON.stringify({
    sub: claims.sub,
    sku: body.sku,
    nonce: crypto.randomUUID(),
    exp: Math.floor(Date.now() / 1000) + 10 * 60,
  }));
  const signature = await hmacBase64Url(payload, env.JWT_SIGNING_SECRET);
  return json({ developerPayload: `v1.${payload}.${signature}` });
}

async function verifyMyketPurchase(request: Request, env: Env, claims: Claims): Promise<Response> {
  if (!env.MYKET_ACCESS_TOKEN || !env.MYKET_PACKAGE_NAME || !env.PURCHASE_ENTITLEMENTS) {
    return json({ error: "payment_not_configured" }, 503);
  }
  const input = await request.json<MyketVerificationRequest>().catch(() => null);
  if (!input?.sku || !input.tokenId || !input.developerPayload) return json({ error: "invalid_purchase" }, 400);
  if (!(await isValidPurchasePayload(input.developerPayload, input.sku, claims.sub!, env.JWT_SIGNING_SECRET))) {
    return json({ error: "invalid_developer_payload" }, 400);
  }

  const usedTokenKey = `myket-token:${input.tokenId}`;
  const alreadyGrantedTo = await env.PURCHASE_ENTITLEMENTS.get(usedTokenKey);
  if (alreadyGrantedTo && alreadyGrantedTo !== claims.sub) return json({ error: "purchase_already_claimed" }, 409);

  const endpoint = `https://developer.myket.ir/api/partners/applications/${encodeURIComponent(env.MYKET_PACKAGE_NAME)}/purchases/products/${encodeURIComponent(input.sku)}/verify`;
  const upstream = await fetch(endpoint, {
    method: "POST",
    headers: { "X-Access-Token": env.MYKET_ACCESS_TOKEN, "content-type": "application/json" },
    body: JSON.stringify({ tokenId: input.tokenId }),
  });
  const verified = await upstream.json<Record<string, unknown>>().catch(() => null);
  if (!upstream.ok) return json({ error: "myket_verification_failed" }, 502);
  if (verified?.purchaseState !== 0 || verified.consumptionState !== 0 || verified.developerPayload !== input.developerPayload) {
    return json({ error: "purchase_not_valid" }, 400);
  }

  // A non-consumable SKU grants a durable entitlement. KV blocks token replay
  // and binds the purchase to the authenticated account in its signed payload.
  await env.PURCHASE_ENTITLEMENTS.put(usedTokenKey, claims.sub!);
  await env.PURCHASE_ENTITLEMENTS.put(`entitlement:${claims.sub}:${input.sku}`, JSON.stringify({ tokenId: input.tokenId, grantedAt: Date.now() }));
  return json({ premium: true, sku: input.sku });
}

async function authenticatedClaims(request: Request, secret: string): Promise<Claims | null> {
  const bearer = request.headers.get("authorization")?.replace(/^Bearer\s+/i, "");
  if (!bearer) return null;
  const [header, encodedClaims, signature] = bearer.split(".");
  if (!header || !encodedClaims || !signature) return null;
  try {
    const claims = JSON.parse(base64UrlDecode(encodedClaims)) as Claims;
    if (!claims.sub || (claims.exp && Date.now() >= claims.exp * 1000)) return null;
    const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
    const valid = await crypto.subtle.verify("HMAC", key, base64UrlBytes(signature), new TextEncoder().encode(`${header}.${encodedClaims}`));
    return valid ? claims : null;
  } catch { return null; }
}

async function isValidPurchasePayload(payload: string, sku: string, subject: string, secret: string): Promise<boolean> {
  const [version, encoded, signature] = payload.split(".");
  if (version !== "v1" || !encoded || !signature) return false;
  try {
    const expected = await hmacBase64Url(encoded, secret);
    if (!timingSafeEqual(signature, expected)) return false;
    const value = JSON.parse(base64UrlDecode(encoded)) as { sub?: string; sku?: string; exp?: number };
    return value.sub === subject && value.sku === sku && !!value.exp && Date.now() < value.exp * 1000;
  } catch { return false; }
}

async function hmacBase64Url(value: string, secret: string): Promise<string> {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const signed = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(value));
  return base64UrlEncodeBytes(new Uint8Array(signed));
}

function base64UrlEncode(value: string): string { return base64UrlEncodeBytes(new TextEncoder().encode(value)); }
function base64UrlEncodeBytes(value: Uint8Array): string {
  let binary = "";
  value.forEach(byte => { binary += String.fromCharCode(byte); });
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=/g, "");
}
function base64UrlDecode(value: string): string { return new TextDecoder().decode(base64UrlBytes(value)); }
function base64UrlBytes(value: string): Uint8Array {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((value.length + 3) % 4);
  return Uint8Array.from(atob(padded), char => char.charCodeAt(0));
}
function timingSafeEqual(left: string, right: string): boolean {
  if (left.length !== right.length) return false;
  let difference = 0;
  for (let i = 0; i < left.length; i += 1) difference |= left.charCodeAt(i) ^ right.charCodeAt(i);
  return difference === 0;
}
