# Privacy Policy Draft — AI Music Companion

Last updated: September 19, 2026.

This draft must be reviewed for the final company identity, support address, jurisdiction and enabled features before publication.

## Local data

The app reads audio metadata and files selected by Android's media permission to build a local music library. Playback history, favourites, playlists, preference profile, local `.lrc` lyrics and audio-analysis results are stored on the device by default. Audio files are not uploaded by the app.

## Optional local backup

When the user exports a backup, the user selects the destination using Android's file picker. A backup contains favourites, playlist names and song references, plus taste preferences. It does not copy audio files or listening history.

## Optional cloud features

Cloud AI similarity is optional and off by default. It requires an explicit settings toggle. Only the approved metadata needed for the feature, such as title, artist, album, genre, local mood label or BPM, is sent through the project's Cloudflare Worker to the configured AI provider. Local lyric text and audio files are not sent. The backend enforces daily quotas and keeps provider secrets outside the app. The current beta uses an anonymous installation session; a production release should replace this with a documented account/authentication system.

## Purchases

Myket purchases are not active until merchant setup is complete. When enabled, verification is server-to-server. The Myket developer access token remains server-side.

## Your choices

Users can decline media and notification permissions, avoid cloud features, and remove local app data through Android settings. Backup files remain controlled by the user at their selected destination.
