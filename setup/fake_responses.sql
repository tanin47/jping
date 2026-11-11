-- Fills response with one fake check per minute over the last 90 days for every URL in website_url, for local development.
-- It replaces any existing rows.
-- Run with: psql postgres://jping_dev_user:dev@localhost:5432/jping_dev -f setup/fake_responses.sql

BEGIN;

DELETE FROM response;

SELECT setseed(0.42);

-- Each hour of each URL has a small chance of an outage that lasts the first `outage_minutes` of that hour.
CREATE TEMP TABLE fake_outage ON COMMIT DROP AS
SELECT url, hour, floor(5 + random() * 55)::int AS outage_minutes
FROM website_url, generate_series(date_trunc('hour', now() - interval '90 days'), now(), interval '1 hour') AS hour
WHERE random() < 0.004;

INSERT INTO response (url, started_at, finished_at, response_code, error)
SELECT
  url,
  started_at,
  CASE WHEN outcome = 'timeout' THEN started_at + interval '10 seconds' ELSE started_at + latency END,
  CASE outcome WHEN 'ok' THEN 200 WHEN 'server_error' THEN 503 END,
  CASE WHEN outcome = 'timeout' THEN 'Timed out after 10 seconds' END
FROM (
  SELECT
    w.url,
    m AS started_at,
    interval '100 milliseconds' + random() * interval '900 milliseconds' AS latency,
    CASE
      WHEN o.url IS NOT NULL THEN (CASE WHEN random() < 0.5 THEN 'server_error' ELSE 'timeout' END)
      WHEN random() < 0.003 THEN (CASE WHEN random() < 0.5 THEN 'server_error' ELSE 'timeout' END)
      ELSE 'ok'
    END AS outcome
  FROM website_url w
  CROSS JOIN generate_series(date_trunc('minute', now() - interval '90 days'), now() - interval '1 minute', interval '1 minute') AS m
  LEFT JOIN fake_outage o
    ON o.url = w.url
    AND o.hour = date_trunc('hour', m)
    AND extract(minute FROM m) < o.outage_minutes
) AS checks;

COMMIT;

SELECT
  url,
  count(*) AS checks,
  round(100.0 * count(*) FILTER (WHERE response_code BETWEEN 200 AND 499) / count(*), 2) AS uptime_percent
FROM response
GROUP BY url
ORDER BY url;
