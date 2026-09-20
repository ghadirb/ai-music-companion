# Release & go-live checklist

## 1. Release signing (owner action — keep the keystore safe; losing it means you can never update the app)
```bash
keytool -genkeypair -v -keystore release.keystore -alias aimusic -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.keystore      # paste into the GitHub secret below
```
GitHub → Settings → Secrets and variables → Actions:
`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.
CI then produces a **signed** `app-release.apk` and `.aab` (artifact `app-release`, R8 enabled, `mapping.txt` included). Never commit the keystore.

Version: `versionCode` = CI run number, `versionName` = `-PVERSION_NAME` (default `1.0.0-rc1`).

## 2. Gateway Worker (Cloudflare)
```bash
cd cloudflare-worker && npm ci && npm test
node scripts/generate-entitlement-keys.mjs           # prints PRIVATE JWK + PUBLIC key
npx wrangler secret put ENTITLEMENT_SIGNING_JWK      # paste the PRIVATE JWK (server only)
npx wrangler secret put JWT_SIGNING_SECRET           # long random string
npx wrangler secret put GAPGPT_API_KEY
npx wrangler deploy
```
Put the PUBLIC key in `gradle.properties` → `ENTITLEMENT_PUBLIC_KEY` (the value committed today matches the key pair created for this build; replace it if you generate your own).
Alternatively run the manual GitHub workflow *Deploy gateway Worker* after adding `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID` (and optionally `ENTITLEMENT_SIGNING_JWK`) as repository secrets. Delete those secrets/tokens after deploying.
`wrangler.toml` binds the KV namespace and the `QuotaCounter` Durable Object (SQLite class, migration `v1`).

## 3. Myket (only real values are missing)
* Worker: `wrangler secret put MYKET_ACCESS_TOKEN`; `MYKET_PACKAGE_NAME` in `wrangler.toml [vars]`.
* App: Gradle property `MYKET_IAB_PUBLIC_KEY` (public key from the Myket panel). SKUs: `premium_lifetime` (add `premium_monthly`/`premium_yearly` to `MYKET_PREMIUM_SKUS` and to the Myket panel when needed).
* No code changes are required.

## 4. Before publishing
* Fill the support e-mail and governing law in `PRIVACY_POLICY.md` / `TERMS.md`; host them at a stable URL and update the two links in `SettingsScreen.kt` if you move them.
* Add store screenshots to `docs/screenshots/`.
* Manual QA on real devices (Android 8, 10, 12, 13, 14, 15): playback in background, headphone unplug, incoming call, sleep timer, resume after kill, LRC import/folder grant, backup/restore on a second device, Premium purchase + restore after reinstall (needs real Myket credentials).
* Revoke every token that was pasted into chats or project files.
