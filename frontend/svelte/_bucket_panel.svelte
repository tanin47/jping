<script lang="ts">
// The failed checks and the minutes without data of one bucket, loaded when the bucket is clicked.
import { untrack } from 'svelte';
import { api } from './globals';
import type { Bucket, Issue, Period, TimeZoneMode } from './models';
import { formatResponseCode, formatTime, groupIssues } from './uptime';

interface Props {
  url: string;
  bucket: Bucket;
  period: Period;
  timeZone: TimeZoneMode;
  // The panel reloads whenever this changes.
  refreshKey: number;
  onClose: () => void;
}

let { url, bucket, period, timeZone, refreshKey, onClose }: Props = $props();

let issues = $state<Issue[] | null>(null);
let loadError = $state('');

// Counts requests so a slow response for an old range can't overwrite a newer one.
let latestRequest = 0;

async function load(start: Date, end: Date) {
  const request = ++latestRequest;
  loadError = '';
  try {
    const params = new URLSearchParams({ url, start: start.toISOString(), end: end.toISOString() });
    const json = await api('GET', `/api/websites/issues?${params}`);
    if (request !== latestRequest) return;
    const failures: Issue[] = (json.failures as any[]).map((f) => ({
      kind: 'failure',
      minute: new Date(f.scheduledAt),
      responseCode: f.responseCode,
      error: f.error,
    }));
    const noData: Issue[] = (json.noData as string[]).map((m) => ({
      kind: 'noData',
      minute: new Date(m),
      responseCode: null,
      error: null,
    }));
    issues = [...failures, ...noData].sort((a, b) => a.minute.getTime() - b.minute.getTime());
  } catch (e) {
    if (request !== latestRequest) return;
    loadError = (e as Error).message;
  }
}

// The bucket's end moves forward on refresh while it's the latest bucket.
let startMs = $derived(bucket.start.getTime());
let endMs = $derived(bucket.end.getTime());

$effect(() => {
  const start = new Date(startMs);
  const end = new Date(endMs);
  refreshKey;
  untrack(() => load(start, end));
});

let groups = $derived(issues ? groupIssues(issues) : []);
let failureCount = $derived(issues?.filter((i) => i.kind === 'failure').length ?? bucket.failures);
let noDataCount = $derived(issues?.filter((i) => i.kind === 'noData').length ?? bucket.noData);

function formatGroupTime(first: Date, last: Date): string {
  const start = formatTime(first, period, timeZone);
  return first.getTime() === last.getTime() ? start : `${start} – ${formatTime(last, period, timeZone)}`;
}

function plural(count: number, word: string): string {
  return `${count} ${word}${count === 1 ? '' : 's'}`;
}
</script>

<div class="flex flex-col gap-2 rounded-box bg-base-100 p-3 text-sm" data-test-id="bucket-panel">
  <div class="flex items-center gap-2">
    <div class="min-w-0 flex-1">
      <span class="font-semibold">{formatTime(bucket.start, period, timeZone)} – {formatTime(bucket.end, period, timeZone)}</span>
      <span class="opacity-60" data-test-id="bucket-panel-summary">
        · {plural(failureCount, 'failed check')} · {plural(noDataCount, 'minute')} with no data
      </span>
    </div>
    <button class="btn btn-ghost btn-xs btn-square" aria-label="Close" data-test-id="close-bucket-panel" onclick={onClose}>
      <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" /></svg>
    </button>
  </div>

  {#if loadError}
    <div role="alert" class="alert alert-error alert-soft py-2">
      <span>Couldn't load the details: {loadError}</span>
      <button class="btn btn-sm" onclick={() => load(bucket.start, bucket.end)}>Retry</button>
    </div>
  {:else if issues === null}
    <div class="flex justify-center py-2"><span class="loading loading-spinner loading-sm"></span></div>
  {:else if groups.length === 0}
    <div class="opacity-60">Every check succeeded.</div>
  {:else}
    <ul class="flex flex-col divide-y divide-base-300">
      {#each groups as group}
        <li class="flex flex-wrap items-baseline gap-x-3 py-1 tabular-nums" data-test-id="issue-group">
          <span class="shrink-0">{formatGroupTime(group.first, group.last)}</span>
          {#if group.kind === 'noData'}
            <span class="shrink-0 opacity-60">No data{group.count > 1 ? ` ×${group.count}` : ''}</span>
          {:else}
            <span class="shrink-0 font-medium text-error">
              {formatResponseCode(group.responseCode)}{group.count > 1 ? ` ×${group.count}` : ''}
            </span>
            {#if group.error}
              <span class="min-w-0 flex-1 truncate opacity-60" title={group.error}>{group.error}</span>
            {/if}
          {/if}
        </li>
      {/each}
    </ul>
  {/if}
</div>
