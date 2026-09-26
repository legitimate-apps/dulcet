package com.legitimateapps.dulcet.core

/**
 * The released schema-6 DDL, exactly as `databases/6.db` records it, so a test can build a
 * version-6 database on every target and drive the real `6.sqm` migration over it. Generated
 * from the snapshot; if a released snapshot ever changed this would be the place it showed.
 */
internal val RELEASED_SCHEMA_6_STATEMENTS: List<String> = listOf(
    """CREATE TABLE queue_state (
  server_id TEXT NOT NULL PRIMARY KEY,
  current_position INTEGER,
  repeat_mode TEXT NOT NULL CHECK (repeat_mode IN ('off', 'all', 'one')),
  shuffle_enabled INTEGER NOT NULL CHECK (shuffle_enabled IN (0, 1)),
  CHECK (current_position IS NULL OR current_position >= 0)
)""",
    """CREATE TABLE active_queue (
  singleton_id INTEGER NOT NULL PRIMARY KEY CHECK (singleton_id = 1),
  server_id TEXT NOT NULL,
  FOREIGN KEY (server_id) REFERENCES queue_state(server_id) ON DELETE CASCADE
)""",
    """CREATE TABLE queue_entry (
  server_id TEXT NOT NULL,
  queue_entry_id TEXT NOT NULL,
  raw_id TEXT NOT NULL,
  source_context_kind TEXT NOT NULL CHECK (
    source_context_kind IN ('library', 'album', 'playlist', 'search', 'artist')
  ),
  source_context_raw_id TEXT,
  source_context_display_name TEXT NOT NULL,
  added_by TEXT NOT NULL CHECK (
    added_by IN ('play_now', 'play_next', 'add_to_queue', 'autoplay')
  ),
  original_position INTEGER NOT NULL CHECK (original_position >= 0),
  playback_position INTEGER NOT NULL CHECK (playback_position >= 0),
  PRIMARY KEY (server_id, queue_entry_id),
  UNIQUE (server_id, original_position),
  UNIQUE (server_id, playback_position),
  FOREIGN KEY (server_id) REFERENCES queue_state(server_id) ON DELETE CASCADE
)""",
    """CREATE TABLE scrobble_outbox (
  server_id TEXT NOT NULL,
  raw_id TEXT NOT NULL,
  session_start_wall_clock INTEGER NOT NULL,
  created_at_wall_clock INTEGER NOT NULL,
  attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
  PRIMARY KEY (server_id, raw_id, session_start_wall_clock)
)""",
    """CREATE TABLE music_folder (
  server_id TEXT NOT NULL,
  raw_id TEXT NOT NULL,
  name TEXT NOT NULL,
  content_key TEXT NOT NULL,
  valid_from_generation INTEGER NOT NULL CHECK (valid_from_generation > 0),
  valid_to_generation INTEGER CHECK (
    valid_to_generation IS NULL OR valid_to_generation > valid_from_generation
  ),
  PRIMARY KEY (server_id, raw_id, valid_from_generation)
)""",
    """CREATE TABLE artist (
  server_id TEXT NOT NULL,
  raw_id TEXT NOT NULL,
  name TEXT NOT NULL,
  media_source_id TEXT,
  content_key TEXT NOT NULL,
  valid_from_generation INTEGER NOT NULL CHECK (valid_from_generation > 0),
  valid_to_generation INTEGER CHECK (
    valid_to_generation IS NULL OR valid_to_generation > valid_from_generation
  ),
  normalized_name TEXT,
  PRIMARY KEY (server_id, raw_id, valid_from_generation)
)""",
    """CREATE TABLE album (
  server_id TEXT NOT NULL,
  raw_id TEXT NOT NULL,
  title TEXT NOT NULL,
  artist_name TEXT,
  artist_raw_id TEXT,
  year INTEGER,
  duration_milliseconds INTEGER NOT NULL CHECK (duration_milliseconds >= 0),
  media_source_id TEXT,
  artwork_key TEXT,
  content_key TEXT NOT NULL,
  valid_from_generation INTEGER NOT NULL CHECK (valid_from_generation > 0),
  valid_to_generation INTEGER CHECK (
    valid_to_generation IS NULL OR valid_to_generation > valid_from_generation
  ),
  normalized_title TEXT,
  PRIMARY KEY (server_id, raw_id, valid_from_generation)
)""",
    """CREATE TABLE track (
  server_id TEXT NOT NULL,
  raw_id TEXT NOT NULL,
  album_raw_id TEXT,
  title TEXT NOT NULL,
  artist_name TEXT,
  artist_raw_id TEXT,
  album_title TEXT,
  disc_number INTEGER,
  track_number INTEGER,
  duration_milliseconds INTEGER NOT NULL CHECK (duration_milliseconds >= 0),
  source_container TEXT,
  media_source_id TEXT,
  artwork_key TEXT,
  content_key TEXT NOT NULL,
  valid_from_generation INTEGER NOT NULL CHECK (valid_from_generation > 0),
  valid_to_generation INTEGER CHECK (
    valid_to_generation IS NULL OR valid_to_generation > valid_from_generation
  ),
  normalized_title TEXT,
  normalized_album_title TEXT,
  PRIMARY KEY (server_id, raw_id, valid_from_generation)
)""",
    """CREATE TABLE credit (
  server_id TEXT NOT NULL,
  seen_key TEXT NOT NULL,
  owner_kind TEXT NOT NULL CHECK (owner_kind IN ('album', 'track')),
  owner_raw_id TEXT NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('artist', 'album_artist')),
  ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
  name TEXT NOT NULL,
  artist_raw_id TEXT,
  content_key TEXT NOT NULL,
  valid_from_generation INTEGER NOT NULL CHECK (valid_from_generation > 0),
  valid_to_generation INTEGER CHECK (
    valid_to_generation IS NULL OR valid_to_generation > valid_from_generation
  ),
  normalized_name TEXT,
  PRIMARY KEY (server_id, seen_key, valid_from_generation)
)""",
    """CREATE TABLE playlist (
  server_id TEXT NOT NULL,
  raw_id TEXT NOT NULL,
  name TEXT NOT NULL,
  content_key TEXT NOT NULL,
  valid_from_generation INTEGER NOT NULL CHECK (valid_from_generation > 0),
  valid_to_generation INTEGER CHECK (
    valid_to_generation IS NULL OR valid_to_generation > valid_from_generation
  ),
  PRIMARY KEY (server_id, raw_id, valid_from_generation)
)""",
    """CREATE TABLE playlist_entry (
  server_id TEXT NOT NULL,
  seen_key TEXT NOT NULL,
  playlist_raw_id TEXT NOT NULL,
  entry_position INTEGER NOT NULL CHECK (entry_position >= 0),
  track_raw_id TEXT NOT NULL,
  content_key TEXT NOT NULL,
  valid_from_generation INTEGER NOT NULL CHECK (valid_from_generation > 0),
  valid_to_generation INTEGER CHECK (
    valid_to_generation IS NULL OR valid_to_generation > valid_from_generation
  ),
  PRIMARY KEY (server_id, seen_key, valid_from_generation)
)""",
    """CREATE TABLE library_starred (
  server_id TEXT NOT NULL,
  item_kind TEXT NOT NULL CHECK (item_kind IN ('artist', 'album', 'track')),
  raw_id TEXT NOT NULL,
  seen_key TEXT NOT NULL,
  valid_from_generation INTEGER NOT NULL CHECK (valid_from_generation > 0),
  valid_to_generation INTEGER CHECK (
    valid_to_generation IS NULL OR valid_to_generation > valid_from_generation
  ),
  PRIMARY KEY (server_id, item_kind, raw_id, valid_from_generation)
)""",
    """CREATE TABLE genre (
  server_id TEXT NOT NULL,
  name TEXT NOT NULL,
  song_count INTEGER NOT NULL CHECK (song_count >= 0),
  album_count INTEGER NOT NULL CHECK (album_count >= 0),
  content_key TEXT NOT NULL,
  valid_from_generation INTEGER NOT NULL CHECK (valid_from_generation > 0),
  valid_to_generation INTEGER CHECK (
    valid_to_generation IS NULL OR valid_to_generation > valid_from_generation
  ),
  PRIMARY KEY (server_id, name, valid_from_generation)
)""",
    """CREATE TABLE sync_checkpoint (
  server_id TEXT NOT NULL PRIMARY KEY,
  generation INTEGER NOT NULL CHECK (generation > 0),
  stage TEXT NOT NULL CHECK (
    stage IN ('folders', 'artists', 'albums', 'tracks', 'playlists', 'starred', 'genres')
  ),
  cursor INTEGER NOT NULL CHECK (cursor >= 0),
  attempt INTEGER NOT NULL CHECK (attempt BETWEEN 0 AND 3),
  witness_ids TEXT NOT NULL,
  witness_page_count INTEGER NOT NULL CHECK (witness_page_count >= 0),
  unverified INTEGER NOT NULL CHECK (unverified IN (0, 1))
)""",
    """CREATE TABLE sync_seen (
  server_id TEXT NOT NULL,
  generation INTEGER NOT NULL CHECK (generation > 0),
  stage TEXT NOT NULL,
  raw_id TEXT NOT NULL,
  PRIMARY KEY (server_id, generation, stage, raw_id)
)""",
    """CREATE TABLE sync_generation (
  generation INTEGER NOT NULL PRIMARY KEY CHECK (generation > 0),
  server_id TEXT NOT NULL,
  stability TEXT NOT NULL CHECK (stability IN ('verified', 'unverified'))
)""",
    """CREATE TABLE deletion_reconciliation (
  server_id TEXT NOT NULL,
  generation INTEGER NOT NULL CHECK (generation > 0),
  raw_id TEXT NOT NULL,
  downloaded_reference_count INTEGER NOT NULL CHECK (downloaded_reference_count >= 0),
  queue_reference_count INTEGER NOT NULL CHECK (queue_reference_count >= 0),
  PRIMARY KEY (server_id, generation, raw_id)
)""",
    """CREATE TABLE search_index_meta (
  singleton_id INTEGER NOT NULL PRIMARY KEY CHECK (singleton_id = 1),
  normalization_version INTEGER NOT NULL
)""",
    """CREATE TABLE download_policy_state (
  server_id TEXT NOT NULL,
  raw_id TEXT NOT NULL,
  transcode_profile TEXT NOT NULL,
  expected_container TEXT NOT NULL CHECK (
    expected_container IN ('mp3', 'mp4', 'wav', 'flac', 'ogg', 'adts_aac')
  ),
  source_duration_milliseconds INTEGER CHECK (
    source_duration_milliseconds IS NULL OR source_duration_milliseconds >= 0
  ),
  source_size_bytes INTEGER CHECK (source_size_bytes IS NULL OR source_size_bytes >= 0),
  enqueue_sequence INTEGER NOT NULL CHECK (enqueue_sequence >= 0),
  auth_generation INTEGER NOT NULL CHECK (auth_generation >= 0),
  retry_attempt INTEGER NOT NULL DEFAULT 0 CHECK (retry_attempt >= 0),
  retry_not_before_wall_clock INTEGER,
  updated_at_wall_clock INTEGER NOT NULL,
  PRIMARY KEY (server_id, raw_id, transcode_profile),
  FOREIGN KEY (server_id, raw_id, transcode_profile)
    REFERENCES download(server_id, raw_id, transcode_profile) ON DELETE CASCADE
)""",
    """CREATE TABLE resume_position (
  server_id TEXT NOT NULL,
  raw_id TEXT NOT NULL,
  position_milliseconds INTEGER NOT NULL CHECK (position_milliseconds >= 0),
  PRIMARY KEY (server_id, raw_id)
)""",
    """CREATE TABLE mutation_outbox (
  server_id TEXT NOT NULL,
  target_id TEXT NOT NULL,
  field TEXT NOT NULL,
  value TEXT NOT NULL,
  local_sequence INTEGER NOT NULL CHECK (local_sequence >= 0),
  wall_clock INTEGER NOT NULL,
  PRIMARY KEY (server_id, target_id, field),
  UNIQUE (server_id, local_sequence)
)""",
    """CREATE TABLE download (
  server_id TEXT NOT NULL,
  raw_id TEXT NOT NULL,
  transcode_profile TEXT NOT NULL,
  download_id TEXT NOT NULL,
  state TEXT NOT NULL CHECK (
    state IN ('queued', 'downloading', 'interrupted', 'complete', 'stale')
  ),
  file_relative_path TEXT NOT NULL,
  expected_byte_length INTEGER,
  file_size_bytes INTEGER NOT NULL DEFAULT 0 CHECK (file_size_bytes >= 0),
  platform_resume_data BLOB,
  resume_data_created_at_wall_clock INTEGER,
  PRIMARY KEY (server_id, raw_id, transcode_profile),
  UNIQUE (download_id),
  UNIQUE (file_relative_path),
  CHECK (expected_byte_length IS NULL OR expected_byte_length >= 0)
)""",
    """CREATE TABLE schema_meta (
  singleton_id INTEGER NOT NULL PRIMARY KEY CHECK (singleton_id = 1),
  schema_version INTEGER NOT NULL CHECK (schema_version > 0),
  cache_format_version INTEGER NOT NULL CHECK (cache_format_version > 0),
  committed_generation INTEGER NOT NULL CHECK (committed_generation >= 0)
)""",
    """CREATE TABLE cache_binding (
  server_id TEXT NOT NULL PRIMARY KEY,
  normalized_base_url TEXT NOT NULL,
  username TEXT NOT NULL,
  created_at_wall INTEGER NOT NULL
)""",
    """CREATE TABLE cache_meta (
  singleton_id INTEGER NOT NULL PRIMARY KEY CHECK (singleton_id = 1),
  last_issued INTEGER NOT NULL CHECK (last_issued >= 0),
  normalization_version INTEGER NOT NULL CHECK (normalization_version >= 0)
)""",
    """CREATE TABLE cache_epoch (
  server_id TEXT NOT NULL PRIMARY KEY,
  last_scan TEXT,
  folder_ids TEXT NOT NULL,
  scanning INTEGER NOT NULL CHECK (scanning IN (0, 1)),
  read_at_wall INTEGER NOT NULL
)""",
    """CREATE TABLE cache_artist (
  server_id TEXT NOT NULL,
  raw_id TEXT NOT NULL,
  name TEXT NOT NULL,
  normalized_name TEXT,
  album_count INTEGER,
  artwork_key TEXT,
  starred INTEGER CHECK (starred IS NULL OR starred IN (0, 1)),
  starred_at TEXT,
  user_rating INTEGER,
  fetched_at_wall INTEGER,
  fetched_epoch TEXT,
  issue_seq INTEGER NOT NULL CHECK (issue_seq >= 0),
  last_access_wall INTEGER NOT NULL,
  gone INTEGER NOT NULL DEFAULT 0 CHECK (gone IN (0, 1)),
  PRIMARY KEY (server_id, raw_id)
)""",
    """CREATE TABLE cache_album (
  server_id TEXT NOT NULL,
  raw_id TEXT NOT NULL,
  title TEXT NOT NULL,
  normalized_title TEXT,
  artist_name TEXT,
  artist_raw_id TEXT,
  year INTEGER,
  genre TEXT,
  duration_milliseconds INTEGER CHECK (duration_milliseconds IS NULL OR duration_milliseconds >= 0),
  song_count INTEGER CHECK (song_count IS NULL OR song_count >= 0),
  artwork_key TEXT,
  starred INTEGER CHECK (starred IS NULL OR starred IN (0, 1)),
  starred_at TEXT,
  user_rating INTEGER,
  play_count INTEGER,
  played TEXT,
  detail_complete INTEGER NOT NULL DEFAULT 0 CHECK (detail_complete IN (0, 1)),
  detail_issue_seq INTEGER NOT NULL DEFAULT 0 CHECK (detail_issue_seq >= 0),
  detail_fetched_epoch TEXT,
  fetched_at_wall INTEGER,
  fetched_epoch TEXT,
  issue_seq INTEGER NOT NULL CHECK (issue_seq >= 0),
  last_access_wall INTEGER NOT NULL,
  gone INTEGER NOT NULL DEFAULT 0 CHECK (gone IN (0, 1)),
  PRIMARY KEY (server_id, raw_id)
)""",
    """CREATE TABLE cache_track (
  server_id TEXT NOT NULL,
  raw_id TEXT NOT NULL,
  album_raw_id TEXT,
  album_ordinal INTEGER CHECK (album_ordinal IS NULL OR album_ordinal >= 0),
  title TEXT,
  normalized_title TEXT,
  album_title TEXT,
  normalized_album_title TEXT,
  artist_name TEXT,
  artist_raw_id TEXT,
  disc_number INTEGER,
  track_number INTEGER,
  duration_milliseconds INTEGER CHECK (duration_milliseconds IS NULL OR duration_milliseconds >= 0),
  source_container TEXT,
  artwork_key TEXT,
  starred INTEGER CHECK (starred IS NULL OR starred IN (0, 1)),
  starred_at TEXT,
  user_rating INTEGER,
  play_count INTEGER,
  played TEXT,
  fetched_at_wall INTEGER,
  fetched_epoch TEXT,
  issue_seq INTEGER NOT NULL CHECK (issue_seq >= 0),
  last_access_wall INTEGER NOT NULL,
  gone INTEGER NOT NULL DEFAULT 0 CHECK (gone IN (0, 1)),
  metadata_missing INTEGER NOT NULL DEFAULT 0 CHECK (metadata_missing IN (0, 1)),
  PRIMARY KEY (server_id, raw_id),
  CHECK (metadata_missing = 1 OR title IS NOT NULL)
)""",
    """CREATE TABLE cache_playlist (
  server_id TEXT NOT NULL,
  raw_id TEXT NOT NULL,
  name TEXT NOT NULL,
  song_count INTEGER CHECK (song_count IS NULL OR song_count >= 0),
  duration_milliseconds INTEGER CHECK (duration_milliseconds IS NULL OR duration_milliseconds >= 0),
  owner TEXT,
  artwork_key TEXT,
  detail_complete INTEGER NOT NULL DEFAULT 0 CHECK (detail_complete IN (0, 1)),
  detail_issue_seq INTEGER NOT NULL DEFAULT 0 CHECK (detail_issue_seq >= 0),
  fetched_at_wall INTEGER,
  fetched_epoch TEXT,
  issue_seq INTEGER NOT NULL CHECK (issue_seq >= 0),
  last_access_wall INTEGER NOT NULL,
  gone INTEGER NOT NULL DEFAULT 0 CHECK (gone IN (0, 1)),
  PRIMARY KEY (server_id, raw_id)
)""",
    """CREATE TABLE cache_credit (
  server_id TEXT NOT NULL,
  owner_kind TEXT NOT NULL CHECK (owner_kind IN ('album', 'track')),
  owner_raw_id TEXT NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('artist', 'album_artist')),
  ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
  name TEXT NOT NULL,
  artist_raw_id TEXT,
  normalized_name TEXT,
  PRIMARY KEY (server_id, owner_kind, owner_raw_id, role, ordinal)
)""",
    """CREATE TABLE cache_list (
  server_id TEXT NOT NULL,
  list_key TEXT NOT NULL,
  window_epoch TEXT,
  folder_ids TEXT,
  first_loaded_offset INTEGER NOT NULL CHECK (first_loaded_offset >= 0),
  end_loaded_offset INTEGER NOT NULL CHECK (end_loaded_offset >= first_loaded_offset),
  total INTEGER CHECK (total IS NULL OR total >= 0),
  coverage TEXT NOT NULL CHECK (
    coverage IN ('complete', 'open', 'unverified_scanning', 'unverified_no_epoch', 'unverified_changing')
  ),
  fetched_at_wall INTEGER,
  last_access_wall INTEGER NOT NULL,
  issue_seq INTEGER NOT NULL CHECK (issue_seq >= 0),
  PRIMARY KEY (server_id, list_key)
)""",
    """CREATE TABLE cache_list_member (
  server_id TEXT NOT NULL,
  list_key TEXT NOT NULL,
  position INTEGER NOT NULL CHECK (position >= 0),
  item_kind TEXT NOT NULL CHECK (item_kind IN ('artist', 'album', 'track', 'playlist', 'genre')),
  raw_id TEXT NOT NULL,
  PRIMARY KEY (server_id, list_key, position),
  FOREIGN KEY (server_id, list_key) REFERENCES cache_list(server_id, list_key) ON DELETE CASCADE
)""",
    """CREATE TABLE cache_pin (
  server_id TEXT NOT NULL,
  item_kind TEXT NOT NULL CHECK (item_kind IN ('artist', 'album', 'track', 'playlist')),
  raw_id TEXT NOT NULL,
  reason TEXT NOT NULL CHECK (reason IN ('download', 'queue', 'playing')),
  PRIMARY KEY (server_id, item_kind, raw_id, reason)
)""",
    """CREATE INDEX music_folder_generation ON music_folder(server_id, valid_from_generation, valid_to_generation)""",
    """CREATE INDEX artist_generation ON artist(server_id, valid_from_generation, valid_to_generation)""",
    """CREATE INDEX album_generation ON album(server_id, valid_from_generation, valid_to_generation)""",
    """CREATE INDEX album_title_generation ON album(server_id, title, valid_from_generation, valid_to_generation)""",
    """CREATE INDEX track_generation ON track(server_id, valid_from_generation, valid_to_generation)""",
    """CREATE INDEX track_title_generation ON track(server_id, title, valid_from_generation, valid_to_generation)""",
    """CREATE INDEX credit_generation ON credit(server_id, valid_from_generation, valid_to_generation)""",
    """CREATE INDEX playlist_generation ON playlist(server_id, valid_from_generation, valid_to_generation)""",
    """CREATE INDEX playlist_entry_generation ON playlist_entry(server_id, valid_from_generation, valid_to_generation)""",
    """CREATE INDEX library_starred_generation ON library_starred(server_id, valid_from_generation, valid_to_generation)""",
    """CREATE INDEX genre_generation ON genre(server_id, valid_from_generation, valid_to_generation)""",
    """CREATE INDEX artist_normalized_name_generation ON artist(server_id, normalized_name, valid_from_generation, valid_to_generation)""",
    """CREATE INDEX album_normalized_title_generation ON album(server_id, normalized_title, valid_from_generation, valid_to_generation)""",
    """CREATE INDEX track_normalized_title_generation ON track(server_id, normalized_title, valid_from_generation, valid_to_generation)""",
    """CREATE INDEX track_normalized_album_title_generation ON track(server_id, normalized_album_title, valid_from_generation, valid_to_generation)""",
    """CREATE INDEX credit_normalized_name_generation ON credit(server_id, normalized_name, valid_from_generation, valid_to_generation)""",
    """CREATE INDEX download_policy_schedule
ON download_policy_state(server_id, enqueue_sequence)""",
    """CREATE INDEX cache_track_album ON cache_track(server_id, album_raw_id)""",
    """CREATE INDEX cache_list_member_item ON cache_list_member(server_id, item_kind, raw_id)""",
    """CREATE INDEX cache_list_access ON cache_list(server_id, last_access_wall)""",
    """CREATE INDEX cache_album_access ON cache_album(server_id, last_access_wall)""",
    """CREATE INDEX cache_track_access ON cache_track(server_id, last_access_wall)""",
    """CREATE INDEX cache_artist_access ON cache_artist(server_id, last_access_wall)""",
    """CREATE INDEX cache_artist_normalized_name ON cache_artist(server_id, normalized_name)""",
    """CREATE INDEX cache_album_normalized_title ON cache_album(server_id, normalized_title)""",
    """CREATE INDEX cache_track_normalized_title ON cache_track(server_id, normalized_title)""",
    """CREATE INDEX cache_credit_normalized_name ON cache_credit(server_id, normalized_name)""",
    """CREATE INDEX cache_album_detail_access ON cache_album(server_id, detail_complete, last_access_wall)""",
)
