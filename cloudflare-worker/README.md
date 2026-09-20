# AI Music Companion — Gateway (Cloudflare Worker)

The only place where provider keys, Myket credentials and the entitlement signing key exist.
The Android app never contains an API secret; it talks to this Worker over HTTPS.

## Endpoints (all `POST`, JSON, `Authorization: Bearer <session>` except session)

| Path | Purpose |
|---|---|
| `/v1/session/anonymous` | Issues a 7-day anonymous session (rate-limited per network address). |
| `/v1/music-embedding` | Embedding for on-device "similar tracks" re-ranking (quota enforced). |
| `/v1/dj/intent` | **Premium.** Turns a short text request into a *structured playlist intent* (the app then picks tracks locally; the model never sees the library). |
| `/v1/myket/purchase-nonce` | Server-signed, single-use, 10-minute developer payload bound to user + SKU. |
| `/v1/myket/verify` | Verifies a purchase with Myket, redeems the nonce once, grants the entitlement. |
| `/v1/myket/restore` | Re-verifies a Myket purchase token after reinstall and moves the entitlement to the new identity (transfer count + cooldown limited). |
| `/v1/entitlements/me` | Current plan, SKUs, quota and an **ES256-signed entitlement token** the app verifies offline. |
| `GET /healthz` | Liveness. |

## Security model
* **Auth**: HS256 session JWT (alg pinned, `exp` required). No static app tokens.
* **Quota**: enforced here with a Durable Object (atomic). Free users are additionally capped per IP so minting many anonymous IDs does not multiply the quota. Failed provider calls are refunded.
* **Premium** is decided from server-side records (KV) and delivered as a signed token; a boolean in the APK is never trusted.
* **Replay protection**: nonces are single-use; a purchase token can belong to one identity at a time.
* **Validation**: content-type, size limits, strict field checks, SKU whitelist, sanitized model output. Upstream error bodies are never forwarded.
* **Logging**: request id + event only. No tokens, purchase tokens, prompts, IPs or secrets.
* **Privacy**: the only user text sent to a provider is the DJ prompt / the short track descriptors used for embeddings; nothing is stored beyond counters.

## Configuration
Plain values live in `wrangler.toml [vars]` (quotas, SKU table). Secrets only via `wrangler secret put`:
`GAPGPT_API_KEY`, `JWT_SIGNING_SECRET`, `MYKET_ACCESS_TOKEN`, `ENTITLEMENT_SIGNING_JWK`.
Generate the signing key pair with `node scripts/generate-entitlement-keys.mjs` and put the public key in the Android build (`ENTITLEMENT_PUBLIC_KEY`).

## Develop
```
npm install
npm run typecheck
npm test
```
