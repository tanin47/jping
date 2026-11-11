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
}

export interface WebsiteUrl {
  url: string;
}

export interface Site extends WebsiteUrl {
  buckets: Bucket[];
}

export interface Period {
  key: string;
  label: string;
  hours: number;
  buckets: number;
}

// A period when JPing itself was running.
export interface UpInterval {
  startedAt: Date;
  endedAt: Date;
}
