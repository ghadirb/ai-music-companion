# AI Music Companion

Offline, private and personalized Android music player. The app plays music stored on the device and learns from local listening behaviour to build useful recommendations without uploading audio files.

## What works today

- Local MediaStore library: songs, artists, albums, folders, favourites and manual playlists.
- Media3 playback with background service, notification and lock-screen controls, resume behaviour, shuffle, repeat and sleep timer.
- Full player with cover art, queue playback, similar tracks and synchronized local `.lrc` lyrics. The active lyric line is highlighted and can be tapped to seek.
- Local search across song title, artist, album and genre.
- Smart recommendations based on favourites, completions, replays, skips, artist/genre affinity and rediscovery.
- On-device audio analysis for energy and approximate BPM. This powers Focus, Night, Driving, Workout and Happy/Dance mixes. A local LRC file can additionally label a track as happy or sad.
- Light/dark theme, Persian RTL interface, contextual media and notification permissions.
- Local JSON backup/restore of favourites, playlists and taste profile. Audio files and listening history are never copied into a backup.

## Privacy-first design

The default product is offline-first. Music files, local LRC lyrics, listening history and taste profile stay on-device. Audio analysis runs locally with Android media APIs. The APK does not contain API keys or merchant access tokens.

Online AI similarity is available as an opt-in beta. It remains off by default and sends only a short metadata document for the current song and a small local candidate set; it never uploads audio files or local LRC text. See [PRIVACY_POLICY.md](PRIVACY_POLICY.md) for the current product privacy draft.

## Smart recommendations

This release uses explainable, deterministic local signals rather than claiming to use a trained deep-learning model. Signals include favourite state, completion, replay, skip, artist/genre affinity, energy, BPM and recency. Unsupported or damaged media is skipped without blocking the rest of the library.

## Free and Premium architecture

Core playback, library management, local recommendations, local lyrics and backup are free. The repository also contains a secure commercial foundation:

- Myket non-consumable `premium_lifetime` billing-client abstraction.
- Cloudflare Worker endpoints for server-side Myket verification and durable entitlement storage.
- Server-enforced online-AI quota configuration: 8 free and 120 premium embedding requests per UTC day.

The online-AI beta uses an anonymous installation session and is limited to 8 requests per UTC day. A production authenticated account/JWT issuer, Myket public key, SKU and Myket server access token are still required before enabling paid features. Do not present a recurring subscription until a provider/store that supports subscriptions is selected.

## Cloudflare Worker

`cloudflare-worker/` is an optional backend boundary. It keeps provider and merchant secrets outside the APK. The opt-in AI beta receives a short-lived anonymous session JWT; entitlement and Myket endpoints require the same server-issued JWT model. Do not embed a static token in the app.

## Licensed online lyrics

Local `.lrc` files are the only active lyrics source. The code has an abstraction for a licensed provider, but deliberately does not scrape search engines or lyric websites. A commercial release should enable online lyrics only after obtaining a provider licence, documenting the data flow, adding explicit user consent and keeping the provider key server-side.

## Build

Requirements: JDK 17, Android SDK 34 and Android Studio or Gradle with network access to Google/Maven repositories.

```bash
./gradlew assembleDebug
```

The app targets Android 14 (`targetSdk 34`) and supports Android 8+ (`minSdk 26`). For Myket configuration, supply properties locally rather than committing them:

```properties
MYKET_IAB_PUBLIC_KEY=...
MYKET_PREMIUM_SKU=premium_lifetime
```

## Current limitations and next release work

- AI similarity is beta-only, opt-in and quota-limited. Replace anonymous sessions with real authentication before a broad commercial rollout.
- Online lyrics are intentionally disabled until a licensed source is selected.
- Queue editing (play next/remove/reorder), Android Auto, widget, equalizer and advanced statistics are future phases.
- BPM/mood are lightweight local heuristics, not a trained classifier.
- Verify every release with device testing and CI across supported Android versions.

## Commercial release checklist

1. Complete Myket panel setup and server verification secrets.
2. Add production authentication before enabling any cloud-AI endpoint.
3. Run CI, unit tests and device QA; sign the release outside source control.
4. Review [PRIVACY_POLICY.md](PRIVACY_POLICY.md) and [TERMS.md](TERMS.md) with legal counsel and add support/contact details.

## License

No open-source licence has been granted yet. All rights are reserved pending a separate owner decision.
