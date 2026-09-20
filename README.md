# AI Music Companion — Offline, Private & Personalized

**یک موزیک‌پلیر آفلاین اندروید که سلیقهٔ شما را به‌مرور یاد می‌گیرد؛ همه‌چیز روی همین دستگاه، بدون حساب کاربری اجباری.**

An offline-first Android music player that learns your taste from *your own* listening and suggests music from *your own* library — locally, explainably and privately. Cloud AI is optional, off by default and never sees your files.

> Local-first · Privacy-first · Offline-first · Explainable recommendations · Secure Premium · Minimal cloud

## Screenshots
Store screenshots live in `docs/screenshots/` (add device captures before a store release).

## Key features
* **Full offline player** — Media3/ExoPlayer, background playback, notification / lock-screen / Bluetooth controls, audio-focus and headphone-unplug handling, resume where you stopped, queue (play next / add / remove / reorder / clear), shuffle, repeat, **sleep timer** (15/30/45/60 min, end of track, custom).
* **Library** — scan, folders, artists, albums, playlists, favourites, history; **Persian-aware search** (ي/ی, ك/ک, ZWNJ, digits) grouped by artist/album/genre/playlist/song; sort & filter by recently added/played, most played, title, artist, album, duration, favourite, genre, mood, energy.
* **Karaoke-style synced lyrics**: the line being sung is highlighted and scrolls into view (inline under the player, or full screen); tap a line to jump there. Lyrics are found **automatically**: the same-name `.lrc` next to the song (tolerant of case, ي/ی, numbering; UTF-8/UTF-16/Windows-1256; offsets; plain text), else lyrics **embedded in the file's tags** (MP3 ID3 `USLT`, FLAC `LYRICS`). On Android 11+ reading sidecar files needs a one-time **All files access** grant (or pick the file per song / grant a folder instead). Everything stays on the device.
* **Home for every occasion**: Workout, Driving, Happy, Calm, Focus, Night, Sad, Morning and Energetic mixes (from on-device audio + lyric mood analysis), defaulting to what fits the time of day, plus personal mixes (favourites, recently loved, rediscover, random-from-taste).
* **On-device audio analysis** — BPM, energy, mood (background, cancellable, corrupt files are skipped).
* **Backup / restore** of favourites, playlists, history, taste profile and theme — no audio files, tokens or purchases; songs are re-matched by path *or* metadata, same-name playlists are merged.

## Offline & privacy
See [PRIVACY_POLICY.md](PRIVACY_POLICY.md) for the exact data flows.
* Music files are processed **locally**. Listening history, favourites, playlists, taste profile and lyrics never leave the device.
* **No API secret is inside the APK.** Provider keys, the Myket access token and the entitlement signing key exist only as Cloudflare Worker secrets.
* Cloud AI is **opt-in**, used only when you press an AI action, and sends only what is listed in the privacy policy (a few track descriptors, or your typed AI DJ request).
* No ads, analytics or trackers. Crash reports stay on the device until *you* share them.

## Smart recommendations (explainable, no black box)
A deterministic weighted score over: favourites, completed plays (recency-decayed), replays, skips, artist/genre affinity, time-of-day energy match, BPM, rediscovery of neglected favourites and light exploration. Every suggestion can say *why* ("because you listen to this artist a lot", "not played for 45 days"…).
* **Taste profile** (local): favourite artists/genres/moods, BPM and energy range, top tracks, skip & favourite behaviour, peak hours.
* **Smart mixes** from your library: My Favourites · Recently Loved · Rediscover · Chill · Energetic · Focus · Night · Random From My Taste.
* **Smart playlists** — describe what you want; a rule-based parser turns it into a structured intent (`mood, energy, genre, artist, language, duration, exclude_recent, favorite_only, sort, limit`) and the *app* selects tracks locally.

## AI features
**AI DJ (Premium)** sends only your typed request to the gateway, which returns a *validated structured intent*. The model never sees or picks your files; if offline/over quota the local parser is used.

## Free vs Premium
| | Free | Premium |
|---|:--:|:--:|
| Player, queue, sleep timer, library, search, sort/filter, favourites, playlists, history | ✅ | ✅ |
| Basic recommendations with reasons, basic smart mixes & rediscover, basic statistics | ✅ | ✅ |
| Local audio analysis, local `.lrc` lyrics, backup/restore | ✅ | ✅ |
| Smart playlist generation, natural-language requests | – | ✅ |
| **AI DJ** (cloud intent) | – | ✅ |
| Advanced statistics & listening insights, recommendation tuning, advanced rediscover | – | ✅ |
| Higher daily AI quota (config: free 8 / premium 120) | – | ✅ |
| Licensed online lyrics (when added) | – | ✅ |

Which plan unlocks what is a single table (`premium/Entitlement.kt → FeatureGate`) — change one line to move a feature. Upgrade prompts appear only when a Free user actually uses a Premium feature; the player itself is never paywalled.

## Architecture
```
app/                      Kotlin · Jetpack Compose · Room · Media3 · WorkManager (no DI framework)
  playback/               PlaybackService (ExoPlayer, MediaSession, focus, resume, sleep timer, ListeningRecorder)
  data/ library/ search/  Room entities/DAOs/repository, sort/filter, Persian search index
  recommendation/ mix/ smartplaylist/   scorer, taste profile, mixes, intent parser/generator, AI DJ client
  premium/ billing/ cloud/ Entitlement (offline-verified ES256 token), FeatureGate, Purchase/Restore, gateway client
  lyrics/ analysis/ backup/ stats/ crash/
cloudflare-worker/        Gateway: sessions, quotas (Durable Object), Myket verify/restore, DJ intent, entitlements
```
Premium is decided by the **server**: purchase → Myket verification → signed entitlement token → verified offline in the app with an embedded *public* key. A boolean in the APK is never trusted.

## Build
Requirements: JDK 17, Android SDK 35. `./gradlew testDebugUnitTest assembleDebug`.
CI (GitHub Actions) runs unit tests, lint, debug + release (R8) builds, the Worker tests and emulator tests (migrations/DAO). Release signing uses GitHub Secrets (`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`) — the keystore is never in the repository. Versions: `-PVERSION_NAME`, `-PVERSION_CODE` (CI uses the run number).

## Configuration (Gradle properties — none are secrets)
| Property | Meaning |
|---|---|
| `CLOUD_AI_BASE_URL` | Gateway Worker URL |
| `ENTITLEMENT_PUBLIC_KEY` | Base64 X.509 public key that verifies signed entitlement tokens |
| `MYKET_IAB_PUBLIC_KEY` | Myket in-app-billing public key (empty until real Myket details exist → purchase UI shows "not configured") |
| `MYKET_PREMIUM_SKUS` | Comma-separated SKUs offered (default `premium_lifetime`; `premium_monthly`, `premium_yearly` supported by the server table) |

## Myket integration
Architecture is complete: billing abstraction → nonce → checkout → server verification → signed entitlement, plus *Restore purchase* (works after reinstall/new device with transfer limits). To go live only add real values: Worker secret `MYKET_ACCESS_TOKEN` + var `MYKET_PACKAGE_NAME`, and the app property `MYKET_IAB_PUBLIC_KEY`. No code change is needed.

## Cloudflare Worker
See [cloudflare-worker/README.md](cloudflare-worker/README.md): endpoints, security model, secrets (`GAPGPT_API_KEY`, `JWT_SIGNING_SECRET`, `MYKET_ACCESS_TOKEN`, `ENTITLEMENT_SIGNING_JWK`), configuration and tests (`npm test`).

## Roadmap
Phase 2: home-screen widget, Android Auto browse/playback, licensed online lyrics (provider abstraction exists), Google Play billing as a second `BillingGateway`, account-based restore.

## License
Proprietary — all rights reserved. See [LICENSE](LICENSE). Not open source; no permission is granted to copy, redistribute or use the code commercially without the owner's written consent.
