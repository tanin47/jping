// 'none' means none of the checks have a result.
export type Status = 'up' | 'degraded' | 'down' | 'none';

export interface Bucket {
  start: Date;
  end: Date;
  total: number; // The checks that should have run, one per minute.
  successes: number;
  failures: number;
  noData: number;
  status: Status;
  latency: Latency | null; // null when no check got a response.
  failureCodes: FailureCount[];
}

// Response times in milliseconds of the checks that got a response.
export interface Latency {
  p50Ms: number;
  p90Ms: number;
  p99Ms: number;
  maxMs: number;
}

// responseCode is null when no response was received (e.g. timeout or connection error).
export interface FailureCount {
  responseCode: number | null;
  count: number;
}

export interface Failure {
  scheduledAt: Date;
  responseCode: number | null;
  error: string | null;
}

export interface WebsiteUrl {
  url: string;
}

export interface Site extends WebsiteUrl {
  buckets: Bucket[];
  // Both cover the whole period.
  latency: Latency | null;
  lastFailure: Failure | null;
}

export interface Period {
  key: string;
  label: string;
  hours: number;
  buckets: number;
  // Must divide a day, so buckets of an hour or longer start on the hour.
  bucketMinutes: number;
}

// The time zone that buckets are aligned to and times are shown in.
export type TimeZoneMode = 'local' | 'utc';

// A period when JPing itself was running.
export interface UpInterval {
  startedAt: Date;
  endedAt: Date;
}
