# Release & go-live checklist

## 1. Release signing — done
A 4096-bit RSA PKCS12 keystore (alias `aimusic`) was generated and stored in `Documents\AIMusicCompanion-secrets\` together with `release-signing-info.txt` (passwords). **Back this folder up now** (USB/cloud): losing the keystore means you can never publish updates of the same app.
GitHub Actions secrets `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` are set, so CI produces a **signed** `app-release.apk` / `.aab` (artifact `app-release`, R8 enabled, `mapping.txt` included). Never commit the keystore.
Version: `versionCode` = CI run number, `versionName` = `-PVERSION_NAME` (default `1.0.0-rc1`).

## 2. Gateway Worker (Cloudflare) — deployed
Worker `ai-music-companion-embedding` is deployed (`npx wrangler deploy` from `cloudflare-worker/`) with the KV binding and the `QuotaCounter` Durable Object.
Secrets set on the Worker: `GAPGPT_API_KEY`, `JWT_SIGNING_SECRET`, `ENTITLEMENT_SIGNING_JWK` (private ES256 key). Still to set: `MYKET_ACCESS_TOKEN`.
The matching PUBLIC key is `ENTITLEMENT_PUBLIC_KEY` in `gradle.properties`. The private key backup is in `Documents\AIMusicCompanion-secrets\` on the owner's PC (never in the repository).
To re-deploy: `cd cloudflare-worker && npm ci --include=dev && npm test && npx wrangler@4 deploy` (needs `CLOUDFLARE_API_TOKEN` + `CLOUDFLARE_ACCOUNT_ID` in the environment).
To rotate the signing key: `node scripts/generate-entitlement-keys.mjs`, `npx wrangler secret put ENTITLEMENT_SIGNING_JWK`, update `ENTITLEMENT_PUBLIC_KEY`, ship a new app build.

## 3. Myket (only real values are missing)
* Worker: `wrangler secret put MYKET_ACCESS_TOKEN`; `MYKET_PACKAGE_NAME` in `wrangler.toml [vars]` (already set to `com.ghadirb.aimusic`).
* App: Gradle property `MYKET_IAB_PUBLIC_KEY` — now set in `gradle.properties` with the real key from the Myket panel (it's a public key, not a secret, same as `ENTITLEMENT_PUBLIC_KEY`).
* SKUs offered: `MYKET_PREMIUM_SKUS=premium_monthly,premium_yearly` (set in `gradle.properties`). No code changes needed — the buy screen/dialog builds its buttons straight from this list.

### 3.1 Creating the in-app products in the Myket panel
Do this once, before submitting the release, in the Myket developer panel → your app → بخش «محصولات درون‌برنامه‌ای» (In-app products):
1. «افزودن محصول جدید» (Add new product) → type: اشتراک/subscription if Myket offers a subscription product type, otherwise a regular consumable/non-consumable product used as a subscription (Myket's IAB is receipt-based; the server already treats `premium_monthly` as 31 days and `premium_yearly` as 366 days regardless of the Myket product type).
2. **Product 1** — شناسه (SKU/Product ID): `premium_monthly` — must match exactly (case-sensitive) — عنوان: «Premium ماهانه» — قیمت: ۳۹٬۰۰۰ تومان.
3. **Product 2** — شناسه (SKU/Product ID): `premium_yearly` — عنوان: «Premium سالانه» — قیمت: ۲۹۹٬۰۰۰ تومان.
4. Publish/activate both products in the panel (a product left in draft won't be purchasable).
5. Sanity check the SKU spelling against `gradle.properties` (`MYKET_PREMIUM_SKUS`) and the worker's `PREMIUM_SKUS` table in `wrangler.toml` — all three must use the identical strings `premium_monthly` / `premium_yearly`.
6. Once `MYKET_ACCESS_TOKEN` is set on the Worker (step above) and the app is installed from Myket, test one real purchase of each plan and confirm "بازیابی خرید" restores it after a reinstall.
* No code changes are required.

## 4. Before publishing
* Support e-mail (`maliar.pro@zohomail.com`) and publisher (گروه نرم‌افزاری مالیار, https://myket.ir/developer/dev-106203) are filled in `PRIVACY_POLICY.md` / `TERMS.md`. Governing law is still generic ("طبق قوانین و سیاست‌های فروشگاه مایکت") — replace only if you want a specific jurisdiction named.
* Add store screenshots to `docs/screenshots/`.
* Manual QA on real devices (Android 8, 10, 12, 13, 14, 15): playback in background, headphone unplug, incoming call, sleep timer, resume after kill, LRC import/folder grant, backup/restore on a second device, Premium purchase + restore after reinstall (needs real Myket credentials).
* **Android Auto (new, item 9)**: `PlaybackService` is now a `MediaLibraryService` with a browse tree (Favorites/Recent/Playlists/Smart Mixes/Smart Radio) in `playback/BrowseTree.kt`. This touches the phone player's session-building code path, so before shipping: build once (`./gradlew assembleDebug` — could not be compiled in the environment this was written in, no Android SDK there), then verify on a phone that playback, queueing and lock-screen/Bluetooth controls still work exactly as before, and ideally test browsing once with [Android Auto's desktop head unit simulator](https://developer.android.com/training/cars/testing) or a real car/phone-screen mirror.
* **Smart Library Watch (new, item 7)**: off by default, one switch in Settings ("بررسی خودکار موسیقی‌های جدید"); no new permission. Sanity-check once with the switch on: add a music file, wait for the periodic/immediate check, confirm the "N آهنگ جدید پیدا شد" summary appears in Settings.
* Revoke every token that was pasted into chats or project files.

## 5. Permissions review
The app does **not** use `MANAGE_EXTERNAL_STORAGE` ("All files access"). Same-name `.lrc` files are read through a music folder the user picks once with the Storage Access Framework (no broad permission), and lyrics embedded in the audio tags need only the normal audio permission. This keeps the app eligible for Google Play as well as Myket.
