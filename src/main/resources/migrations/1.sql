# --- !Ups

CREATE TABLE website_url (
  url TEXT PRIMARY KEY,
  status TEXT NOT NULL
);

CREATE TABLE response (
  url TEXT NOT NULL REFERENCES website_url (url) ON DELETE CASCADE,
  -- The point in time this check represents. started_at may drift from it when the server is busy.
  scheduled_at TIMESTAMPTZ NOT NULL,
  started_at TIMESTAMPTZ NOT NULL,
  finished_at TIMESTAMPTZ,
  -- NULL when no HTTP response was received (e.g. timeout or connection error).
  response_code INTEGER,
  -- The error message when the check failed without a response (e.g. timeout or connection error).
  error TEXT
);

CREATE INDEX response_url_scheduled_at_idx ON response (url, scheduled_at);

-- The periods when JPing itself was running.
CREATE TABLE jping_uptime (
  started_at TIMESTAMPTZ NOT NULL,
  -- Moved forward while JPing keeps running, so a gap until the next started_at means JPing was down.
  ended_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX jping_uptime_started_at_idx ON jping_uptime (started_at);

# --- !Downs

DROP TABLE jping_uptime;
DROP TABLE response;
DROP TABLE website_url;
