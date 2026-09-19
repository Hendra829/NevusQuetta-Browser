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
