-- Fills jping_uptime with 90 days of fake intervals for local development. It replaces any existing rows.
-- Run with: psql postgres://jping_dev_user:dev@localhost:5432/jping_dev -f setup/fake_jping_uptime.sql

BEGIN;

DELETE FROM jping_uptime;

DO $$
DECLARE
  cursor_at TIMESTAMPTZ := now() - interval '90 days';
  up_until TIMESTAMPTZ;
BEGIN
  -- The same seed gives the same history every time.
  PERFORM setseed(0.42);

  WHILE cursor_at < now() LOOP
    -- Up for 6 hours to 5 days.
    up_until := LEAST(now(), cursor_at + interval '6 hours' + random() * interval '114 hours');
    INSERT INTO jping_uptime (started_at, ended_at) VALUES (cursor_at, up_until);

    -- Then down: usually a short restart of 1 to 15 minutes, sometimes an outage of up to 6 hours.
    IF random() < 0.85 THEN
      cursor_at := up_until + interval '1 minute' + random() * interval '14 minutes';
    ELSE
      cursor_at := up_until + interval '30 minutes' + random() * interval '330 minutes';
    END IF;
  END LOOP;
END $$;

COMMIT;

SELECT count(*) AS intervals, min(started_at) AS first_started_at, max(ended_at) AS last_ended_at FROM jping_uptime;
