-- Novasa music catalog (D1). Separate from the Premium/entitlement KV + Durable Object on purpose.
CREATE TABLE music_tracks (
  id TEXT PRIMARY KEY,
  key TEXT NOT NULL,
  artist TEXT,
  title TEXT NOT NULL,
  search TEXT NOT NULL,              -- normalized "artist title", used by LIKE search
  first_folder_date TEXT NOT NULL,   -- Jalali "1405-07-19": earliest folder the song was seen in (release-date proxy)
  first_folder_iso TEXT NOT NULL,    -- same day, Gregorian "2026-10-11", sortable
  latest_modified TEXT,              -- server mtime (no timezone), tie-break within a day only
  first_discovered_at INTEGER NOT NULL,
  display_priority INTEGER NOT NULL  -- priority of the source whose spelling is displayed (lower wins)
);
CREATE INDEX idx_tracks_date ON music_tracks (first_folder_iso DESC, latest_modified DESC);

CREATE TABLE music_versions (
  url TEXT PRIMARY KEY,
  track_id TEXT NOT NULL REFERENCES music_tracks(id) ON DELETE CASCADE,
  source_id TEXT NOT NULL,
  source_priority INTEGER NOT NULL,
  file_name TEXT NOT NULL,
  quality INTEGER,
  size INTEGER,
  folder_date TEXT NOT NULL,
  modified TEXT,
  status TEXT NOT NULL DEFAULT 'unknown',   -- unknown | ok | bad
  checked_at INTEGER,
  discovered_at INTEGER NOT NULL
);
CREATE INDEX idx_versions_track ON music_versions (track_id);

-- ZIP collections ("گلچین خوانندگان")
CREATE TABLE music_archives (
  url TEXT PRIMARY KEY,
  source_id TEXT NOT NULL,
  file_name TEXT NOT NULL,
  collection TEXT NOT NULL,
  artist TEXT,
  artist_key TEXT NOT NULL,          -- '' when the singer is unknown
  size INTEGER,
  folder_date TEXT NOT NULL,
  folder_iso TEXT NOT NULL,
  discovered_at INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'unknown',   -- unknown | ok | bad (temporary) | rejected (unsafe/encrypted/bomb: hidden from the app)
  detail TEXT,                              -- machine-readable reason for bad/rejected
  checked_at INTEGER,
  entry_count INTEGER                       -- audio files found inside, once validated
);
CREATE INDEX idx_archives_artist ON music_archives (artist_key);
CREATE INDEX idx_archives_unchecked ON music_archives (checked_at, folder_iso DESC);

-- Audio files inside a validated archive (the archive itself is never unpacked on the server).
CREATE TABLE music_archive_entries (
  archive_url TEXT NOT NULL,
  path TEXT NOT NULL,
  name TEXT NOT NULL,
  size INTEGER NOT NULL,
  PRIMARY KEY (archive_url, path)
);

-- One row per (source, day) scan so scanning is incremental and resumable.
CREATE TABLE music_scan_days (
  day_key TEXT PRIMARY KEY,          -- "giso:1405-07-19"
  source_id TEXT NOT NULL,
  folder_date TEXT NOT NULL,
  status TEXT NOT NULL,              -- ok | empty | error
  detail TEXT,
  entries INTEGER NOT NULL DEFAULT 0,
  scanned_at INTEGER NOT NULL
);

-- Remotely managed configuration (sources, windows). Written only through the authenticated admin endpoint.
CREATE TABLE music_settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);