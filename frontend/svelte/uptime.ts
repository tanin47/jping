// Helpers shared by the website timelines and the JPing uptime bar.
import type { Period, Status } from './models';

// The server always splits a period into this many buckets.
export const BUCKET_COUNT = 90;

export const PERIODS: Period[] = [
  { key: '24h', label: 'Last 24 hours', hours: 24 },
  { key: '7d', label: 'Last 7 days', hours: 24 * 7 },
  { key: '30d', label: 'Last 30 days', hours: 24 * 30 },
  { key: '90d', label: 'Last 90 days', hours: 24 * 90 },
];

const MINUTE_MS = 60 * 1000;

// Checks from the last couple of minutes may not have finished yet, so the range stops 2 minutes back,
// rounded down to a whole minute.
export function rangeOf(p: Period): { start: Date; end: Date } {
  const end = Math.floor((Date.now() - 2 * MINUTE_MS) / MINUTE_MS) * MINUTE_MS;
  return { start: new Date(end - p.hours * 3600 * 1000), end: new Date(end) };
}

export function rangeParams(p: Period): string {
  const { start, end } = rangeOf(p);
  return new URLSearchParams({ start: start.toISOString(), end: end.toISOString() }).toString();
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

// Matches the server: every edge is at the start of a minute.
export function bucketEdges(start: Date, end: Date): Date[] {
  const totalMinutes = Math.round((end.getTime() - start.getTime()) / MINUTE_MS);
  return Array.from(
    { length: BUCKET_COUNT + 1 },
    (_, i) => new Date(start.getTime() + Math.floor((i * totalMinutes) / BUCKET_COUNT) * MINUTE_MS),
  );
}

export function formatPercent(value: number | null): string {
  if (value === null) return 'No data';
  return value >= 99.995 ? '100%' : `${value.toFixed(2)}%`;
}

export function formatTime(date: Date, p: Period): string {
  return p.hours <= 24
    ? date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    : date.toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

export function formatAxis(date: Date, p: Period): string {
  return p.hours <= 24
    ? date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    : date.toLocaleDateString([], { month: 'short', day: 'numeric' });
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
