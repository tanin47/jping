<script lang="ts">
// The uptime of JPing itself, drawn as one continuous bar from its exact up intervals.
import { untrack } from 'svelte';
import { api } from './globals';
import PendingBar from './_pending_bar.svelte';
import type { Period, TimeZoneMode, UpInterval } from './models';
import {
  BAR_COLOR,
  DOT_COLOR,
  TEXT_COLOR,
  formatAxis,
  formatDuration,
  formatPercent,
  formatTime,
  rangeOf,
  rangeParams,
  statusOf,
  tooltipAlign,
} from './uptime';

interface Props {
  period: Period;
  timeZone: TimeZoneMode;
  // The bar reloads whenever this changes.
  refreshKey: number;
  // Called after each load with whether it succeeded.
  onPulled?: (ok: boolean, error: string) => void;
}

let { period, timeZone, refreshKey, onPulled }: Props = $props();

type SegmentKind = 'up' | 'down' | 'none';

interface Segment {
  kind: SegmentKind;
  start: Date;
  end: Date;
}

let range = $state(rangeOf(period, timeZone));
let intervals: UpInterval[] = $state([]);
let loading = $state(true);
let loadError = $state('');
let loaded = $state(false);

// Counts requests so a slow response for an old period can't overwrite a newer one.
let latestRequest = 0;

async function load(p: Period, tz: TimeZoneMode) {
  const request = ++latestRequest;
  loadError = '';
  try {
    const json = await api('GET', `/api/jping/uptime?${rangeParams(p, tz)}`);
    if (request !== latestRequest) return;
    range = { start: new Date(json.start), end: new Date(json.end) };
    intervals = (json.intervals as any[]).map((i) => ({
      startedAt: new Date(i.startedAt),
      endedAt: new Date(i.endedAt),
    }));
    loaded = true;
    onPulled?.(true, '');
  } catch (e) {
    if (request !== latestRequest) return;
    loadError = (e as Error).message;
    onPulled?.(false, loadError);
  } finally {
    if (request === latestRequest) loading = false;
  }
}

// Loads on mount, whenever the period or the time zone changes, and on every refresh.
$effect(() => {
  const p = period;
  const tz = timeZone;
  refreshKey;
  untrack(() => load(p, tz));
});

// Splits the range into up and down segments. Gaps between intervals are downtime,
// and the part of the range that's still in the future has no data.
function segmentsOf(start: Date, end: Date, ups: UpInterval[]): Segment[] {
  const now = new Date(Math.min(Date.now(), end.getTime()));
  const segments: Segment[] = [];
  let cursor = start;

  for (const up of ups) {
    const upStart = new Date(Math.max(up.startedAt.getTime(), cursor.getTime()));
    const upEnd = new Date(Math.min(up.endedAt.getTime(), now.getTime()));
    if (upEnd <= upStart) continue;
    if (upStart > cursor) segments.push({ kind: 'down', start: cursor, end: upStart });
    segments.push({ kind: 'up', start: upStart, end: upEnd });
    cursor = upEnd;
  }
  if (now > cursor) segments.push({ kind: 'down', start: cursor, end: now });
  if (end > now) segments.push({ kind: 'none', start: now, end });
  return segments;
}

let segments = $derived(segmentsOf(range.start, range.end, intervals));

let uptime = $derived.by(() => {
  const elapsed = segments.filter((s) => s.kind !== 'none');
  const total = elapsed.reduce((sum, s) => sum + (s.end.getTime() - s.start.getTime()), 0);
  const up = elapsed.filter((s) => s.kind === 'up').reduce((sum, s) => sum + (s.end.getTime() - s.start.getTime()), 0);
  return total === 0 ? null : (up * 100) / total;
});

// This page was served by JPing, so it's up right now.
const current = 'up';

const LABEL: Record<SegmentKind, string> = { up: 'Up', down: 'Down', none: 'No data' };

function alignOf(segment: Segment): string {
  const total = range.end.getTime() - range.start.getTime();
  const middle = (segment.start.getTime() + segment.end.getTime()) / 2 - range.start.getTime();
  return tooltipAlign(total === 0 ? 0.5 : middle / total);
}

function tipOf(segment: Segment): string {
  const span = `${formatTime(segment.start, period, timeZone)} – ${formatTime(segment.end, period, timeZone)}`;
  if (segment.kind === 'none') return `${span}: No data`;
  return `${span}: ${LABEL[segment.kind]} for ${formatDuration(segment.end.getTime() - segment.start.getTime())}`;
}
</script>

<div class="card bg-base-200 border border-primary/40" data-test-id="jping-uptime">
  <div class="card-body p-4 gap-3">
    <div class="flex items-center gap-3">
      <span class="status {DOT_COLOR[current]}"></span>
      <div class="min-w-0 flex-1">
        <div class="font-semibold truncate">This monitoring system</div>
      </div>
      <div class="text-right">
        <div class="text-lg font-bold {TEXT_COLOR[statusOf(uptime)]}">{formatPercent(uptime)}</div>
        <div class="text-xs opacity-60">uptime</div>
      </div>
    </div>

    {#if loadError && !loaded}
      <div role="alert" class="alert alert-error">
        <span>Couldn't load JPing's uptime: {loadError}</span>
        <button class="btn btn-sm" onclick={() => load(period, timeZone)}>Retry</button>
      </div>
    {:else if loading}
      <div class="h-8 rounded-sm bg-base-300 animate-pulse"></div>
    {:else}
      <div class="flex h-8 gap-px">
        <div class="flex flex-1 min-w-0 rounded-sm bg-base-300">
          {#each segments as segment}
            <div class="relative h-full min-w-px" style="flex: {segment.end.getTime() - segment.start.getTime()} 1 0px;">
              <div class="tooltip block h-full w-full {alignOf(segment)}" data-tip={tipOf(segment)}>
                <div class="h-full w-full {BAR_COLOR[segment.kind]} hover:opacity-70"></div>
              </div>
              {#if segment.kind === 'down'}
                <!-- A sibling of the bar's tooltip so hovering the caret doesn't open both tooltips. -->
                <div
                  class="tooltip absolute bottom-full {alignOf(segment)} left-1/2 -translate-x-1/2 px-1 pb-0.5 cursor-default hover:opacity-70"
                  data-tip={tipOf(segment)}
                  data-test-id="jping-uptime-down-caret"
                >
                  <div class="h-0 w-0 border-x-[5px] border-t-[6px] border-x-transparent border-t-error"></div>
                </div>
              {/if}
            </div>
          {/each}
        </div>
        <PendingBar />
      </div>
    {/if}

    <div class="flex justify-between text-xs opacity-60">
      <span>{formatAxis(range.start, period, timeZone)}</span>
      <span>Now</span>
    </div>
  </div>
</div>
