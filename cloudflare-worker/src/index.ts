export interface Env {
  GAPGPT_API_KEY: string;
  JWT_SIGNING_SECRET: string;
}

type EmbeddingRequest = {
  model?: "text-embedding-3-small" | "text-embedding-3-large" | "gemini-embedding-001";
  input: string;
};

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status,
  headers: { "content-type": "application/json", "cache-control": "no-store" },
});

/**
 * Commercial boundary: the Android app never receives GAPGPT_API_KEY. A
 * verified short-lived JWT is required before paid embedding capacity is used.
 * Connect this verifier to the product's actual authentication service before
 * deployment; do not weaken it to a static token embedded in the APK.
 */
export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method !== "POST" || new URL(request.url).pathname !== "/v1/music-embedding") {
      return json({ error: "not_found" }, 404);
    }
    const bearer = request.headers.get("authorization")?.replace(/^Bearer\s+/i, "");
    if (!bearer || !(await verifyHs256Jwt(bearer, env.JWT_SIGNING_SECRET))) {
      return json({ error: "unauthorized" }, 401);
    }
    const payload = await request.json<EmbeddingRequest>().catch(() => null);
    if (!payload?.input || payload.input.length > 6_000) return json({ error: "invalid_input" }, 400);

    const upstream = await fetch("https://api.gapgpt.app/v1/embeddings", {
      method: "POST",
      headers: {
        authorization: `Bearer ${env.GAPGPT_API_KEY}`,
        "content-type": "application/json",
      },
      body: JSON.stringify({ model: payload.model ?? "text-embedding-3-small", input: payload.input }),
    });
    const data = await upstream.json();
    return json(data, upstream.status);
  },
};

async function verifyHs256Jwt(token: string, secret: string): Promise<boolean> {
  const [header, payload, signature] = token.split(".");
  if (!header || !payload || !signature) return false;
  try {
    const parsed = JSON.parse(atob(payload.replace(/-/g, "+").replace(/_/g, "/")));
    if (parsed.exp && Date.now() >= parsed.exp * 1000) return false;
    const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
    const bytes = Uint8Array.from(atob(signature.replace(/-/g, "+").replace(/_/g, "/")), c => c.charCodeAt(0));
    return crypto.subtle.verify("HMAC", key, bytes, new TextEncoder().encode(`${header}.${payload}`));
  } catch { return false; }
}
