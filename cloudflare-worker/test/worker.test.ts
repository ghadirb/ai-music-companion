import { describe, expect, it } from "vitest";
import { makeHarness, okEmbedding, SECRET, NOW, installId, signSession } from "./helpers";
import { base64UrlDecode, base64UrlEncode } from "../src/util";

const purchaseOk = (payload: string | null) => () => new Response(JSON.stringify({ purchaseState: 0, consumptionState: 0, developerPayload: payload }), { status: 200 });

async function buyPremium(h: ReturnType<typeof makeHarness>, token: string, sku = "premium_lifetime", tokenId = "tok-123") {
  const nonce = await h.call("/v1/myket/purchase-nonce", { token, body: { sku } });
  expect(nonce.status).toBe(200);
  const payload = nonce.body.developerPayload as string;
  h.setUpstream(purchaseOk(payload));
  return { payload, res: await h.call("/v1/myket/verify", { token, body: { sku, tokenId, developerPayload: payload } }) };
}

describe("auth & request validation", () => {
  it("rejects requests without a token", async () => {
    const h = makeHarness();
    expect((await h.call("/v1/entitlements/me")).status).toBe(401);
  });

  it("rejects malformed, tampered, expired and alg=none tokens", async () => {
    const h = makeHarness();
    const good = await h.session();
    expect((await h.call("/v1/entitlements/me", { token: "garbage" })).status).toBe(401);
    const [head, payload, sig] = good.split(".");
    const forged = `${head}.${base64UrlEncode(JSON.stringify({ sub: "anon:other", exp: 9_999_999_999 }))}.${sig}`;
    expect((await h.call("/v1/entitlements/me", { token: forged })).status).toBe(401);
    const none = `${base64UrlEncode(JSON.stringify({ alg: "none" }))}.${payload}.`;
    expect((await h.call("/v1/entitlements/me", { token: none })).status).toBe(401);
    const expired = (await signSession(SECRET, installId(9), NOW - 8 * 86_400_000)).token;
    expect((await h.call("/v1/entitlements/me", { token: expired })).status).toBe(401);
    const wrongKey = (await signSession("another-secret-".padEnd(40, "y"), installId(9), NOW)).token;
    expect((await h.call("/v1/entitlements/me", { token: wrongKey })).status).toBe(401);
  });

  it("rejects non-POST, wrong content type, bad JSON and oversized bodies", async () => {
    const h = makeHarness();
    const token = await h.session();
    expect((await h.call("/v1/entitlements/me", { method: "GET", token })).status).toBe(405);
    expect((await h.call("/v1/music-embedding", { token, raw: "hi", contentType: "text/plain" })).status).toBe(415);
    expect((await h.call("/v1/music-embedding", { token, raw: "{not json" })).status).toBe(400);
    expect((await h.call("/v1/music-embedding", { token, raw: JSON.stringify({ input: "x".repeat(70_000) }) })).status).toBe(413);
    expect((await h.call("/v1/nope", { token })).status).toBe(404);
  });

  it("validates the installation id and health endpoint", async () => {
    const h = makeHarness();
    expect((await h.call("/v1/session/anonymous", { body: { installationId: "short" } })).status).toBe(400);
    expect((await h.call("/v1/session/anonymous", { body: {} })).status).toBe(400);
    expect((await h.call("/healthz", { method: "GET" })).status).toBe(200);
  });

  it("caps anonymous sessions per IP", async () => {
    const h = makeHarness({ SESSION_LIMIT_PER_IP_DAY: "3" });
    for (let i = 1; i <= 3; i++) expect((await h.call("/v1/session/anonymous", { body: { installationId: installId(i) }, ip: "198.51.100.1" })).status).toBe(200);
    expect((await h.call("/v1/session/anonymous", { body: { installationId: installId(4) }, ip: "198.51.100.1" })).status).toBe(429);
    expect((await h.call("/v1/session/anonymous", { body: { installationId: installId(5) }, ip: "198.51.100.2" })).status).toBe(200);
  });

  it("returns 503 when secrets/bindings are missing", async () => {
    const h = makeHarness({ JWT_SIGNING_SECRET: "" });
    expect((await h.call("/v1/session/anonymous", { body: { installationId: installId() } })).status).toBe(503);
  });
});

describe("AI quota", () => {
  it("enforces the free daily limit server-side and reports it", async () => {
    const h = makeHarness({ FREE_DAILY_EMBEDDING_LIMIT: "3", FREE_IP_DAILY_FACTOR: "10" });
    h.setUpstream(okEmbedding);
    const token = await h.session();
    for (let i = 0; i < 3; i++) {
      const r = await h.call("/v1/music-embedding", { token, body: { input: "hello" } });
      expect(r.status).toBe(200);
      expect(r.headers.get("x-ai-tier")).toBe("free");
      expect(r.headers.get("x-ai-daily-remaining")).toBe(String(2 - i));
    }
    const blocked = await h.call("/v1/music-embedding", { token, body: { input: "hello" } });
    expect(blocked.status).toBe(429);
    expect(blocked.body.error).toBe("daily_quota_exhausted");
    expect(h.fetchCalls.length).toBe(3); // upstream never called once exhausted
  });

  it("does not let new anonymous IDs from one IP multiply the free quota", async () => {
    const h = makeHarness({ FREE_DAILY_EMBEDDING_LIMIT: "2", FREE_IP_DAILY_FACTOR: "2" });
    h.setUpstream(okEmbedding);
    let ok = 0;
    for (let i = 1; i <= 6; i++) {
      const token = await h.session(i, "198.51.100.9");
      for (let j = 0; j < 2; j++) if ((await h.call("/v1/music-embedding", { token, body: { input: "x" }, ip: "198.51.100.9" })).status === 200) ok++;
    }
    expect(ok).toBe(4); // 2 * factor 2
  });

  it("refunds quota when the provider fails and hides upstream errors/extra fields", async () => {
    const h = makeHarness({ FREE_DAILY_EMBEDDING_LIMIT: "1" });
    const token = await h.session();
    h.setUpstream(() => new Response(JSON.stringify({ error: "provider secret detail" }), { status: 500 }));
    const failed = await h.call("/v1/music-embedding", { token, body: { input: "a" } });
    expect(failed.status).toBe(502);
    expect(JSON.stringify(failed.body)).not.toContain("provider secret detail");
    h.setUpstream(okEmbedding);
    const retry = await h.call("/v1/music-embedding", { token, body: { input: "a" } });
    expect(retry.status).toBe(200);
    expect(retry.body.secret_debug).toBeUndefined();
  });

  it("validates embedding input and model", async () => {
    const h = makeHarness();
    const token = await h.session();
    expect((await h.call("/v1/music-embedding", { token, body: { input: [] } })).status).toBe(400);
    expect((await h.call("/v1/music-embedding", { token, body: { input: [1, 2] } })).status).toBe(400);
    expect((await h.call("/v1/music-embedding", { token, body: { input: "x", model: "gpt-evil" } })).status).toBe(400);
  });

  it("rate limits bursts per subject", async () => {
    const h = makeHarness({ REQUEST_LIMIT_PER_MINUTE: "3" });
    const token = await h.session();
    for (let i = 0; i < 3; i++) expect((await h.call("/v1/entitlements/me", { token })).status).toBe(200);
    expect((await h.call("/v1/entitlements/me", { token })).status).toBe(429);
  });
});

describe("purchases & entitlements", () => {
  it("free by default; purchase requires a valid SKU and configuration", async () => {
    const h = makeHarness();
    const token = await h.session();
    const me = await h.call("/v1/entitlements/me", { token });
    expect(me.body.premium).toBe(false);
    expect(me.body.aiDailyLimit).toBe(8);
    expect(me.body.entitlementToken).toBeNull();
    expect((await h.call("/v1/myket/purchase-nonce", { token, body: { sku: "hacked_sku" } })).status).toBe(400);
    const unconfigured = makeHarness({ MYKET_ACCESS_TOKEN: undefined });
    expect((await unconfigured.call("/v1/myket/purchase-nonce", { token: await unconfigured.session(), body: { sku: "premium_lifetime" } })).status).toBe(503);
  });

  it("verifies a purchase server-side and grants premium with the higher quota", async () => {
    const h = makeHarness();
    const token = await h.session();
    const { res } = await buyPremium(h, token);
    expect(res.status).toBe(200);
    expect(res.body.premium).toBe(true);
    expect(res.body.skus).toEqual(["premium_lifetime"]);
    const me = await h.call("/v1/entitlements/me", { token });
    expect(me.body.premium).toBe(true);
    expect(me.body.aiDailyLimit).toBe(120);
    expect(h.fetchCalls[0].url).toContain("/purchases/products/premium_lifetime/verify");
  });

  it("signs an ES256 entitlement token that verifies with the public key", async () => {
    const pair = (await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"])) as CryptoKeyPair;
    const jwk = await crypto.subtle.exportKey("jwk", pair.privateKey);
    const h = makeHarness({ ENTITLEMENT_SIGNING_JWK: JSON.stringify(jwk) });
    const token = await h.session();
    const { res } = await buyPremium(h, token);
    const jwt: string = res.body.entitlementToken;
    const [head, payload, sig] = jwt.split(".");
    const raw = Uint8Array.from(atob(sig.replace(/-/g, "+").replace(/_/g, "/") + "=="), (c) => c.charCodeAt(0));
    const valid = await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, pair.publicKey, raw, new TextEncoder().encode(`${head}.${payload}`));
    expect(valid).toBe(true);
    const claims = JSON.parse(atob(payload.replace(/-/g, "+").replace(/_/g, "/")));
    expect(claims.plan).toBe("premium");
    expect(claims.skus).toEqual(["premium_lifetime"]);
    expect(claims.exp - claims.iat).toBe(7 * 86400);
  });

  it("rejects invalid, forged, expired and cross-user payloads", async () => {
    const h = makeHarness();
    const token = await h.session(1);
    const other = await h.session(2);
    const nonce = await h.call("/v1/myket/purchase-nonce", { token, body: { sku: "premium_lifetime" } });
    const payload = nonce.body.developerPayload as string;
    h.setUpstream(purchaseOk(payload));
    const verify = (t: string, p: string, sku = "premium_lifetime") => h.call("/v1/myket/verify", { token: t, body: { sku, tokenId: "tok-1", developerPayload: p } });
    expect((await verify(token, "v1.abc.def")).status).toBe(400);
    expect((await verify(other, payload)).status).toBe(400); // bound to another subject
    expect((await verify(token, payload, "premium_yearly")).status).toBe(400); // bound to another SKU
    h.clock.now += 11 * 60_000; // payload expires after 10 minutes
    expect((await verify(token, payload)).status).toBe(400);
    expect((await h.call("/v1/myket/verify", { token, body: { sku: "premium_lifetime", tokenId: "bad token!", developerPayload: payload } })).status).toBe(400);
  });

  it("rejects purchases Myket does not confirm (refunded/consumed/unknown) and does not burn the nonce on outages", async () => {
    const h = makeHarness();
    const token = await h.session();
    const nonce = await h.call("/v1/myket/purchase-nonce", { token, body: { sku: "premium_lifetime" } });
    const payload = nonce.body.developerPayload as string;
    const body = { sku: "premium_lifetime", tokenId: "tok-9", developerPayload: payload };
    h.setUpstream(() => new Response(JSON.stringify({ purchaseState: 1, consumptionState: 0, developerPayload: payload }), { status: 200 }));
    expect((await h.call("/v1/myket/verify", { token, body })).status).toBe(400);
    h.setUpstream(() => new Response("{}", { status: 503 }));
    expect((await h.call("/v1/myket/verify", { token, body })).status).toBe(503);
    h.setUpstream(() => new Response(JSON.stringify({ purchaseState: 0, consumptionState: 0, developerPayload: "someone-elses-payload" }), { status: 200 }));
    expect((await h.call("/v1/myket/verify", { token, body })).status).toBe(400);
    h.setUpstream(purchaseOk(payload));
    expect((await h.call("/v1/myket/verify", { token, body })).status).toBe(200); // nonce was still usable
  });

  it("detects replay: nonce reuse and a token claimed by another user", async () => {
    const h = makeHarness();
    const a = await h.session(1);
    const b = await h.session(2);
    const { payload, res } = await buyPremium(h, a, "premium_lifetime", "tok-A");
    expect(res.status).toBe(200);
    // lost-response retry by the same owner is idempotent
    h.setUpstream(purchaseOk(payload));
    expect((await h.call("/v1/myket/verify", { token: a, body: { sku: "premium_lifetime", tokenId: "tok-A", developerPayload: payload } })).status).toBe(200);
    // another user cannot claim the same purchase token
    const nonceB = await h.call("/v1/myket/purchase-nonce", { token: b, body: { sku: "premium_lifetime" } });
    h.setUpstream(purchaseOk(nonceB.body.developerPayload));
    const stolen = await h.call("/v1/myket/verify", { token: b, body: { sku: "premium_lifetime", tokenId: "tok-A", developerPayload: nonceB.body.developerPayload } });
    expect(stolen.status).toBe(409);
    expect(stolen.body.error).toBe("purchase_already_claimed");
    // a reused nonce for a different token is a replay
    h.setUpstream(purchaseOk(payload));
    const replay = await h.call("/v1/myket/verify", { token: a, body: { sku: "premium_lifetime", tokenId: "tok-A2", developerPayload: payload } });
    expect(replay.status).toBe(409);
    expect(replay.body.error).toBe("replay_detected");
  });

  it("restores a purchase after reinstall (new identity) and enforces transfer limits", async () => {
    const h = makeHarness();
    const original = await h.session(1);
    await buyPremium(h, original, "premium_lifetime", "tok-R");
    h.setUpstream(purchaseOk(null));

    const reinstall = await h.session(2);
    const restored = await h.call("/v1/myket/restore", { token: reinstall, body: { sku: "premium_lifetime", tokenId: "tok-R" } });
    expect(restored.status).toBe(200);
    expect(restored.body.premium).toBe(true);
    expect((await h.call("/v1/entitlements/me", { token: original })).body.premium).toBe(false); // moved, not duplicated
    expect((await h.call("/v1/entitlements/me", { token: reinstall })).body.premium).toBe(true);

    const third = await h.session(3);
    expect((await h.call("/v1/myket/restore", { token: third, body: { sku: "premium_lifetime", tokenId: "tok-R" } })).status).toBe(429); // cooldown
    h.clock.now += 25 * 3600_000;
    expect((await h.call("/v1/myket/restore", { token: third, body: { sku: "premium_lifetime", tokenId: "tok-R" } })).status).toBe(200);
    h.clock.now += 25 * 3600_000;
    expect((await h.call("/v1/myket/restore", { token: await h.session(4), body: { sku: "premium_lifetime", tokenId: "tok-R" } })).status).toBe(200);
    h.clock.now += 25 * 3600_000;
    const capped = await h.call("/v1/myket/restore", { token: await h.session(5), body: { sku: "premium_lifetime", tokenId: "tok-R" } });
    expect(capped.status).toBe(409);
    expect(capped.body.error).toBe("transfer_limit_reached");
  });

  it("restore fails when Myket does not know the purchase", async () => {
    const h = makeHarness();
    const token = await h.session();
    h.setUpstream(() => new Response("{}", { status: 404 }));
    expect((await h.call("/v1/myket/restore", { token, body: { sku: "premium_lifetime", tokenId: "nope" } })).status).toBe(400);
  });

  it("expires time-limited SKUs", async () => {
    const h = makeHarness();
    const token = await h.session();
    await buyPremium(h, token, "premium_monthly", "tok-M");
    expect((await h.call("/v1/entitlements/me", { token })).body.premium).toBe(true);
    h.clock.now += 32 * 86_400_000;
    const later = await h.session(1); // a fresh session for the same install id
    const me = await h.call("/v1/entitlements/me", { token: later });
    expect(me.body.premium).toBe(false);
    expect(me.body.entitlementToken).toBeNull();
  });

  it("treats corrupt entitlement records as no entitlement", async () => {
    const h = makeHarness();
    const token = await h.session();
    h.kv.store.set(`entitlement:anon:${installId(1)}:premium_lifetime`, "{not json");
    expect((await h.call("/v1/entitlements/me", { token })).body.premium).toBe(false);
  });
});

describe("AI DJ intent", () => {
  const chat = (content: string) => () => new Response(JSON.stringify({ choices: [{ message: { content } }] }), { status: 200 });

  it("is Premium-only (server-enforced)", async () => {
    const h = makeHarness();
    const token = await h.session();
    const res = await h.call("/v1/dj/intent", { token, body: { prompt: "calm music" } });
    expect(res.status).toBe(403);
    expect(res.body.error).toBe("premium_required");
    expect(h.fetchCalls.length).toBe(0);
  });

  it("returns only a sanitized structured intent (never track ids/paths)", async () => {
    const h = makeHarness();
    const token = await h.session();
    await buyPremium(h, token);
    h.setUpstream(chat('Sure! {"moods":["CALM","evil"],"energy":"low","limit":9999,"duration_minutes":60,"path":"/sdcard/x.mp3","sort":"drop table","favorite_only":"yes","title":"مطالعه"}'));
    const res = await h.call("/v1/dj/intent", { token, body: { prompt: "یک ساعت موسیقی آرام برای مطالعه" } });
    expect(res.status).toBe(200);
    expect(res.body.intent).toEqual({
      moods: ["calm"], energy: "low", genre: null, artist: null, language: null, duration_minutes: 60,
      exclude_recent_days: null, favorite_only: false, similar_to_current: false, sort: "best_match", limit: 100, title: "مطالعه",
      bpm_min: null, bpm_max: null, exploration: null, energy_shift: 0,
    });
    const sent = h.fetchCalls[h.fetchCalls.length - 1].body;
    expect(JSON.stringify(sent)).not.toContain("sdcard");
    expect(sent.messages[1].content).toBe("یک ساعت موسیقی آرام برای مطالعه"); // only the prompt is sent
  });

  it("validates the prompt, counts against quota and refunds on AI failure", async () => {
    const h = makeHarness({ PREMIUM_DAILY_EMBEDDING_LIMIT: "2" });
    const token = await h.session();
    await buyPremium(h, token);
    expect((await h.call("/v1/dj/intent", { token, body: { prompt: "" } })).status).toBe(400);
    expect((await h.call("/v1/dj/intent", { token, body: { prompt: "x".repeat(301) } })).status).toBe(400);
    h.setUpstream(() => new Response("oops", { status: 500 }));
    expect((await h.call("/v1/dj/intent", { token, body: { prompt: "calm" } })).status).toBe(502);
    h.setUpstream(chat('{"moods":["calm"]}'));
    expect((await h.call("/v1/dj/intent", { token, body: { prompt: "calm" } })).status).toBe(200);
    expect((await h.call("/v1/dj/intent", { token, body: { prompt: "calm" } })).status).toBe(200);
    expect((await h.call("/v1/dj/intent", { token, body: { prompt: "calm" } })).status).toBe(429);
  });

  it("ignores non-JSON model output", async () => {
    const h = makeHarness();
    const token = await h.session();
    await buyPremium(h, token);
    h.setUpstream(chat("I cannot do that"));
    expect((await h.call("/v1/dj/intent", { token, body: { prompt: "calm" } })).status).toBe(502);
  });
});

describe("free trial", () => {
  const DAY = 86_400_000;
  const start = (h: ReturnType<typeof makeHarness>, token: string, ip?: string) => h.call("/v1/trial/start", { token, ip });

  it("is offered once, starts on request and grants premium for 7 days with the trial quota", async () => {
    const h = makeHarness();
    const token = await h.session();
    const before = await h.call("/v1/entitlements/me", { token });
    expect(before.body.premium).toBe(false);
    expect(before.body.trial).toEqual({ eligible: true, active: false, startedAt: null, expiresAt: null });

    const res = await start(h, token);
    expect(res.status).toBe(200);
    expect(res.body.premium).toBe(true);
    expect(res.body.skus).toEqual(["trial"]);
    expect(res.body.expiresAt).toBe(NOW + 7 * DAY);
    expect(res.body.trial).toEqual({ eligible: false, active: true, startedAt: NOW, expiresAt: NOW + 7 * DAY });

    const me = await h.call("/v1/entitlements/me", { token });
    expect(me.body.aiDailyLimit).toBe(40);
  });

  it("does not extend or restart the trial when called again (idempotent)", async () => {
    const h = makeHarness();
    const token = await h.session();
    await start(h, token);
    h.clock.now += 3 * DAY;
    const again = await start(h, await h.session(1));
    expect(again.body.expiresAt).toBe(NOW + 7 * DAY);
    expect(again.body.trial.startedAt).toBe(NOW);
  });

  it("ends after 7 days and can never be restarted by the same identity", async () => {
    const h = makeHarness();
    await start(h, await h.session());
    h.clock.now += 7 * DAY + 1000;
    const token = await h.session(1);
    const me = await h.call("/v1/entitlements/me", { token });
    expect(me.body.premium).toBe(false);
    expect(me.body.trial.eligible).toBe(false);
    expect(me.body.trial.active).toBe(false);
    const retry = await start(h, token);
    expect(retry.body.premium).toBe(false);
    expect((await h.call("/v1/dj/intent", { token, body: { prompt: "calm" } })).status).toBe(403);
  });

  it("unlocks premium-only server features during the trial", async () => {
    const h = makeHarness();
    const token = await h.session();
    await start(h, token);
    h.setUpstream(() => new Response(JSON.stringify({ choices: [{ message: { content: '{"moods":["calm"]}' } }] }), { status: 200 }));
    const res = await h.call("/v1/dj/intent", { token, body: { prompt: "calm music" } });
    expect(res.status).toBe(200);
    expect(res.headers.get("x-ai-tier")).toBe("premium");
    expect(res.headers.get("x-ai-daily-limit")).toBe("40");
  });

  it("a purchase takes over from the trial (full premium quota, purchase SKU)", async () => {
    const h = makeHarness();
    const token = await h.session();
    await start(h, token);
    await buyPremium(h, token);
    const me = await h.call("/v1/entitlements/me", { token });
    expect(me.body.skus).toEqual(["premium_lifetime"]);
    expect(me.body.aiDailyLimit).toBe(120);
  });

  it("treats a corrupt trial record as already used, never as a new trial", async () => {
    const h = makeHarness();
    const token = await h.session();
    h.kv.store.set(`trial:anon:${installId(1)}`, "{broken");
    const res = await start(h, token);
    expect(res.body.premium).toBe(false);
    expect(res.body.trial.eligible).toBe(false);
  });

  it("caps trial starts per network address per day", async () => {
    const h = makeHarness({ TRIAL_PER_IP_DAY: "2" });
    const ip = "198.51.100.9";
    for (const n of [1, 2]) expect((await start(h, await h.session(n, ip), ip)).status).toBe(200);
    const third = await start(h, await h.session(3, ip), ip);
    expect(third.status).toBe(429);
    expect(third.body.error).toBe("trial_limit_reached");
  });

  it("requires a session and the entitlement store", async () => {
    const h = makeHarness();
    expect((await h.call("/v1/trial/start", {})).status).toBe(401);
    const noKv = makeHarness({ PURCHASE_ENTITLEMENTS: undefined });
    expect((await noKv.call("/v1/trial/start", { token: await noKv.session() })).status).toBe(503);
  });

  it("signs an entitlement token whose expiry is the trial end", async () => {
    const pair = (await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"])) as CryptoKeyPair;
    const jwk = JSON.stringify(await crypto.subtle.exportKey("jwk", pair.privateKey));
    const h = makeHarness({ ENTITLEMENT_SIGNING_JWK: jwk });
    const res = await start(h, await h.session());
    const payload = JSON.parse(base64UrlDecode(res.body.entitlementToken.split(".")[1]));
    expect(payload.skus).toEqual(["trial"]);
    expect(payload.exp).toBe(Math.floor((NOW + 7 * DAY) / 1000));
  });
});
