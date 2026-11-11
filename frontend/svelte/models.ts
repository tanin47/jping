export type Status = 'up' | 'degraded' | 'down';

export interface Bucket {
  start: Date;
  end: Date;
  uptime: number; // 0..100
  status: Status;
}

export interface Site {
  url: string;
  status: string;
  flakiness: number; // Mock only: 0 = rock solid, higher = more incidents
}

export interface WebsiteUrl {
  url: string;
  status: string;
}

export interface Period {
  key: string;
  label: string;
  hours: number;
  buckets: number;
}
