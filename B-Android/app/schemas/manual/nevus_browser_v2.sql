-- NevusQuetta Room schema snapshot v1
CREATE TABLE IF NOT EXISTS bookmarks (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  url TEXT NOT NULL,
  normalizedUrl TEXT NOT NULL,
  title TEXT NOT NULL,
  createdAt INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS index_bookmarks_normalizedUrl ON bookmarks(normalizedUrl);
CREATE TABLE IF NOT EXISTS history (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  url TEXT NOT NULL,
  title TEXT NOT NULL,
  visitedAt INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS index_history_visitedAt ON history(visitedAt);
CREATE INDEX IF NOT EXISTS index_history_url ON history(url);
CREATE TABLE IF NOT EXISTS tabs (
  tabId TEXT NOT NULL PRIMARY KEY,
  url TEXT NOT NULL,
  title TEXT NOT NULL,
  isPrivate INTEGER NOT NULL,
  isActive INTEGER NOT NULL,
  position INTEGER NOT NULL,
  updatedAt INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS index_tabs_position ON tabs(position);

CREATE TABLE IF NOT EXISTS downloads (
  downloadId TEXT NOT NULL PRIMARY KEY,
  systemDownloadId INTEGER,
  url TEXT NOT NULL,
  sourceOrigin TEXT,
  fileName TEXT NOT NULL,
  mimeType TEXT,
  kind TEXT NOT NULL,
  status TEXT NOT NULL,
  bytesDownloaded INTEGER NOT NULL,
  totalBytes INTEGER NOT NULL,
  supportsResume INTEGER NOT NULL,
  etag TEXT,
  lastModified TEXT,
  sha256 TEXT,
  localUri TEXT,
  errorCode TEXT,
  createdAt INTEGER NOT NULL,
  updatedAt INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS index_downloads_status ON downloads(status);
CREATE INDEX IF NOT EXISTS index_downloads_updatedAt ON downloads(updatedAt);
CREATE INDEX IF NOT EXISTS index_downloads_systemDownloadId ON downloads(systemDownloadId);
