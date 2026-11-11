# --- !Ups

CREATE TABLE website_url (
  url TEXT PRIMARY KEY,
  status TEXT NOT NULL
);

CREATE TABLE response (
  url TEXT NOT NULL REFERENCES website_url (url) ON DELETE CASCADE,
  started_at TIMESTAMPTZ NOT NULL,
  finished_at TIMESTAMPTZ,
  -- NULL when no HTTP response was received (e.g. timeout or connection error).
  response_code INTEGER,
  -- The error message when the check failed without a response (e.g. timeout or connection error).
  error TEXT
);

CREATE INDEX response_url_started_at_idx ON response (url, started_at);

# --- !Downs

DROP TABLE response;
DROP TABLE website_url;
