// 'none' means there were no checks in the bucket, so there's no data to show.
export type Status = 'up' | 'degraded' | 'down' | 'none';

export interface Bucket {
  start: Date;
  end: Date;
  checks: number;
  successfulChecks: number;
  uptime: number | null; // 0..100, or null when there were no checks
  status: Status;
}

export interface WebsiteUrl {
  url: string;
  status: string;
}

export interface Site extends WebsiteUrl {
  buckets: Bucket[];
}

export interface Period {
  key: string;
  label: string;
  hours: number;
}

// A period when JPing itself was running.
export interface UpInterval {
  startedAt: Date;
  endedAt: Date;
}
