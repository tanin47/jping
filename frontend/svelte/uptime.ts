// Helpers shared by the website timelines and the JPing uptime bar.
import type { Period, Status, TimeZoneMode } from './models';

// Shorter periods get fewer, wider buckets, so switching periods visibly changes the timeline.
// Every bucket covers a round duration: 1 hour, 4 hours, 12 hours, and 1 day.
export const PERIODS: Period[] = [
  { key: '24h', label: 'Last 24 hours', hours: 24, buckets: 24, bucketMinutes: 60 },
  { key: '7d', label: 'Last 7 days', hours: 24 * 7, buckets: 42, bucketMinutes: 4 * 60 },
  { key: '30d', label: 'Last 30 days', hours: 24 * 30, buckets: 60, bucketMinutes: 12 * 60 },
  { key: '90d', label: 'Last 90 days', hours: 24 * 90, buckets: 90, bucketMinutes: 24 * 60 },
];

export const LOCAL_TIME_ZONE = Intl.DateTimeFormat().resolvedOptions().timeZone;

export const TIME_ZONES: { key: TimeZoneMode; label: string }[] = [
  { key: 'local', label: `Local time (${LOCAL_TIME_ZONE})` },
  { key: 'utc', label: 'UTC' },
];

const MINUTE_MS = 60 * 1000;

// The start of the bucket that contains t. Buckets start at midnight plus a multiple of bucketMinutes
// on the wall clock of the time zone, which matches the server.
function bucketStartOf(t: Date, bucketMinutes: number, tz: TimeZoneMode): Date {
  if (tz === 'utc') {
    const minute = t.getUTCHours() * 60 + t.getUTCMinutes();
    return new Date(
      Date.UTC(t.getUTCFullYear(), t.getUTCMonth(), t.getUTCDate(), 0, minute - (minute % bucketMinutes)),
    );
  }
  const minute = t.getHours() * 60 + t.getMinutes();
  return new Date(t.getFullYear(), t.getMonth(), t.getDate(), 0, minute - (minute % bucketMinutes));
}

// Checks from the last couple of minutes may not have finished yet, so the timeline stops 2 minutes back,
// rounded down to a whole minute. Every bucket is aligned to the time zone except the latest one, which ends there.
export function bucketEdgesOf(p: Period, tz: TimeZoneMode): Date[] {
  const end = new Date(Math.floor((Date.now() - 2 * MINUTE_MS) / MINUTE_MS) * MINUTE_MS);
  const starts = [bucketStartOf(new Date(end.getTime() - MINUTE_MS), p.bucketMinutes, tz)];
  while (starts.length < p.buckets) {
    starts.unshift(bucketStartOf(new Date(starts[0].getTime() - MINUTE_MS), p.bucketMinutes, tz));
  }
  return [...starts, end];
}

export function rangeOf(p: Period, tz: TimeZoneMode): { start: Date; end: Date } {
  const edges = bucketEdgesOf(p, tz);
  return { start: edges[0], end: edges[edges.length - 1] };
}

export function rangeParams(p: Period, tz: TimeZoneMode): string {
  const { start, end } = rangeOf(p, tz);
  return new URLSearchParams({ start: start.toISOString(), end: end.toISOString() }).toString();
}

export function bucketParams(p: Period, tz: TimeZoneMode): string {
  const timezone = tz === 'utc' ? 'UTC' : LOCAL_TIME_ZONE;
  return `${rangeParams(p, tz)}&${new URLSearchParams({ bucketMinutes: String(p.bucketMinutes), timezone })}`;
}

export function statusOf(uptime: number | null): Status {
  if (uptime === null) return 'none';
  if (uptime >= 99.9) return 'up';
  if (uptime >= 95) return 'degraded';
  return 'down';
}

export function bucketStatus(successes: number, failures: number, noData: number): Status {
  const total = successes + failures + noData;
  if (total === 0 || noData === total) return 'none';
  if (successes === total) return 'up';
  if (successes > 0) return 'degraded';
  return 'down';
}

export function formatPercent(value: number | null): string {
  if (value === null) return 'No data';
  return value >= 99.995 ? '100%' : `${value.toFixed(2)}%`;
}

function timeZoneOf(tz: TimeZoneMode): string | undefined {
  return tz === 'utc' ? 'UTC' : undefined;
}

export function formatTime(date: Date, p: Period, tz: TimeZoneMode): string {
  const timeZone = timeZoneOf(tz);
  return p.hours <= 24
    ? date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', timeZone })
    : date.toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', timeZone });
}

export function formatAxis(date: Date, p: Period, tz: TimeZoneMode): string {
  const timeZone = timeZoneOf(tz);
  return p.hours <= 24
    ? date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', timeZone })
    : date.toLocaleDateString([], { month: 'short', day: 'numeric', timeZone });
}

export function formatDateTime(date: Date, tz: TimeZoneMode): string {
  return date.toLocaleString([], {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    timeZone: timeZoneOf(tz),
  });
}

export function formatDuration(ms: number): string {
  const minutes = Math.round(ms / 60000);
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ${minutes % 60}m`;
  return `${Math.floor(hours / 24)}d ${hours % 24}h`;
}

export const BAR_COLOR: Record<Status, string> = {
  up: 'bg-success',
  degraded: 'bg-warning',
  down: 'bg-error',
  none: 'bg-base-300',
};

export const TEXT_COLOR: Record<Status, string> = {
  up: 'text-success',
  degraded: 'text-warning',
  down: 'text-error',
  none: 'opacity-60',
};

export const DOT_COLOR: Record<Status, string> = {
  up: 'status-success',
  degraded: 'status-warning',
  down: 'status-error',
  none: 'status-neutral',
};
