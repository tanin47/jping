<script lang="ts">
// The websites and their uptime timelines are loaded from the server.
import { untrack } from 'svelte';
import AddSiteForm from './_add_site_form.svelte';
import JpingUptimeBar from './_jping_uptime_bar.svelte';
import PendingBar from './_pending_bar.svelte';
import RemoveSiteDialog from './_remove_site_dialog.svelte';
import { IS_ADMIN, api } from './globals';
import type { Bucket, Period, Site, Status, TimeZoneMode, WebsiteUrl } from './models';
import {
  BAR_COLOR,
  DOT_COLOR,
  PERIODS,
  TEXT_COLOR,
  TIME_ZONES,
  bucketEdgesOf,
  bucketParams,
  bucketStatus,
  formatAxis,
  formatDateTime,
  formatPercent,
  formatTime,
  statusOf,
} from './uptime';

let periodKey = $state('24h');
let period = $derived(PERIODS.find((p) => p.key === periodKey)!);
let timeZone: TimeZoneMode = $state('local');

let sites: Site[] = $state([]);
let loading = $state(true);
let loadError = $state('');
// When the website uptimes were last loaded successfully.
let lastPulledAt: Date | null = $state(null);

// --- Auto refresh ---

const PULL_INTERVAL_MS = 30 * 1000;

// Goes up every PULL_INTERVAL_MS. Both the website list and the JPing bar reload when it changes.
let pullTick = $state(0);
let websitesPullOk = $state(true);
let jpingPullOk = $state(true);
let pullError = $state('');

let pullOk = $derived(websitesPullOk && jpingPullOk);

$effect(() => {
  const timer = setInterval(() => pullTick++, PULL_INTERVAL_MS);
  return () => clearInterval(timer);
});

function onJpingPulled(ok: boolean, error: string) {
  jpingPullOk = ok;
  if (!ok) pullError = error;
}

// --- Buckets ---

function toBucket(json: any): Bucket {
  return {
    start: new Date(json.start),
    end: new Date(json.end),
    total: json.total,
    successes: json.successes,
    failures: json.failures,
    noData: json.noData,
    status: bucketStatus(json.successes, json.failures, json.noData),
  };
}

// Checks without a result are left out, so missing data doesn't count as downtime.
function overallUptime(buckets: Bucket[]): number | null {
  const successes = buckets.reduce((sum, b) => sum + b.successes, 0);
  const failures = buckets.reduce((sum, b) => sum + b.failures, 0);
  const checks = successes + failures;
  return checks === 0 ? null : (successes * 100) / checks;
}

function currentStatus(buckets: Bucket[]): Status {
  for (let i = buckets.length - 1; i >= 0; i--) {
    if (buckets[i].status !== 'none') return buckets[i].status;
  }
  return 'none';
}

// Placeholder buckets with no data, for a website whose uptime hasn't been loaded yet.
function emptyBuckets(p: Period, tz: TimeZoneMode): Bucket[] {
  const edges = bucketEdgesOf(p, tz);
  return Array.from({ length: edges.length - 1 }, (_, i) => {
    const total = Math.round((edges[i + 1].getTime() - edges[i].getTime()) / 60000);
    return {
      start: edges[i],
      end: edges[i + 1],
      total,
      successes: 0,
      failures: 0,
      noData: total,
      status: 'none' as Status,
    };
  });
}

function formatTip(bucket: Bucket, p: Period, tz: TimeZoneMode): string {
  const range = `${formatTime(bucket.start, p, tz)} – ${formatTime(bucket.end, p, tz)}`;
  return `${range}: ${bucket.successes} successes, ${bucket.failures} failures, ${bucket.noData} no data, ${bucket.total} total`;
}

// --- Server calls ---

// Counts requests so a slow response for an old period or time zone can't overwrite a newer one.
let latestRequest = 0;

async function loadSites(p: Period, tz: TimeZoneMode) {
  const request = ++latestRequest;
  // Show the spinner only on the first load, not on every refresh.
  loading = lastPulledAt === null;
  loadError = '';
  try {
    const json = await api('GET', `/api/websites/uptime?${bucketParams(p, tz)}`);
    if (request !== latestRequest) return;
    lastPulledAt = new Date();
    websitesPullOk = true;
    sites = (json.websites as any[]).map((w) => ({
      url: w.url,
      buckets: (w.buckets as any[]).map(toBucket),
    }));
  } catch (e) {
    if (request !== latestRequest) return;
    loadError = (e as Error).message;
    websitesPullOk = false;
    pullError = loadError;
  } finally {
    if (request === latestRequest) loading = false;
  }
}

// Loads on mount, whenever the period or the time zone changes, and on every auto refresh.
$effect(() => {
  const p = period;
  const tz = timeZone;
  pullTick;
  // Only the period and the time zone should trigger a reload, not the state that loadSites reads.
  untrack(() => loadSites(p, tz));
});

// --- Add website ---

function onSiteAdded(website: WebsiteUrl) {
  sites.push({ ...website, buckets: emptyBuckets(period, timeZone) });
  loadSites(period, timeZone);
}

// --- Delete confirmation ---

let removeDialog: RemoveSiteDialog;

function askToRemove(site: Site) {
  removeDialog.open(site.url);
}

function onSiteRemoved(url: string) {
  sites = sites.filter((s) => s.url !== url);
}
</script>

{#snippet timeline(site: Site)}
  {@const buckets = site.buckets}
  {@const uptime = overallUptime(buckets)}
  {@const current = currentStatus(buckets)}
  <div class="card bg-base-200">
    <div class="card-body p-4 gap-3">
      <div class="flex items-center gap-3">
        <span class="status {DOT_COLOR[current]}"></span>
        <div class="min-w-0 flex-1">
          <a href={site.url} target="_blank" rel="noopener noreferrer" class="font-semibold link link-hover truncate block" data-test-id="website-url">{site.url}</a>
        </div>
        <div class="text-right">
          <div class="text-lg font-bold {TEXT_COLOR[statusOf(uptime)]}">{formatPercent(uptime)}</div>
          <div class="text-xs opacity-60">uptime</div>
        </div>
        {#if IS_ADMIN}
          <button class="btn btn-ghost btn-sm btn-square" aria-label="Remove {site.url}" title="Remove" data-test-id="remove-website-button" data-test-value={site.url} onclick={() => askToRemove(site)}>
            <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" /></svg>
          </button>
        {/if}
      </div>

      <div class="flex h-8 gap-px">
        {#each buckets as bucket}
          <!-- Sized by duration, so the latest (partial) bucket is narrower and the timeline lines up with JPing's bar. -->
          <div
            class="tooltip min-w-0"
            style="flex: {bucket.end.getTime() - bucket.start.getTime()} 1 0px;"
            data-tip={formatTip(bucket, period, timeZone)}
          >
            <div class="h-full w-full rounded-sm {BAR_COLOR[bucket.status]} hover:opacity-70"></div>
          </div>
        {/each}
        <PendingBar />
      </div>

      <div class="flex justify-between text-xs opacity-60">
        <span>{formatAxis(buckets[0].start, period, timeZone)}</span>
        <span>Now</span>
      </div>
    </div>
  </div>
{/snippet}

<div class="container mx-auto max-w-4xl p-4 sm:p-8 flex flex-col gap-6">
  <header class="flex flex-wrap items-center gap-4">
    <div class="flex flex-1 items-center gap-0">
      <img src="/assets/images/logo.png" alt="" class="h-[32px]" />
      <h1 class="text-2xl font-bold">JPing</h1>
    </div>
    <span
      class="flex items-center gap-2 text-xs"
      title={pullOk ? `Refreshes every ${PULL_INTERVAL_MS / 1000} seconds` : pullError}
      data-test-id="pull-status"
      data-test-value={pullOk ? 'ok' : 'failed'}
    >
      <span class="relative inline-flex h-2.5 w-2.5">
        {#if pullOk}
          <span class="pulse absolute inline-flex h-full w-full rounded-full bg-success"></span>
        {/if}
        <span class="relative inline-flex h-2.5 w-2.5 rounded-full {pullOk ? 'bg-success' : 'bg-error'}"></span>
      </span>
      <span class={pullOk ? 'opacity-60' : 'text-error'} data-test-id="last-pulled-at">
        {#if !pullOk}Update failed.{/if}
        {#if lastPulledAt}
          {pullOk ? 'Updated' : 'Last updated'}
          {formatDateTime(lastPulledAt, timeZone)}
        {/if}
      </span>
    </span>
    <select class="select select-sm w-auto" bind:value={timeZone} aria-label="Time zone" data-test-id="time-zone">
      {#each TIME_ZONES as tz}
        <option value={tz.key}>{tz.label}</option>
      {/each}
    </select>
    <select class="select select-sm w-auto" bind:value={periodKey} aria-label="Period">
      {#each PERIODS as p}
        <option value={p.key}>{p.label}</option>
      {/each}
    </select>
  </header>

  {#if IS_ADMIN}
    <AddSiteForm existingUrls={sites.map((s) => s.url)} onAdded={onSiteAdded} />
  {/if}

  <div class="flex flex-wrap gap-4 text-sm opacity-80">
    <span class="flex items-center gap-2"><span class="inline-block h-3 w-3 rounded-sm bg-success"></span>Up</span>
    <span class="flex items-center gap-2"><span class="inline-block h-3 w-3 rounded-sm bg-warning"></span>Partly up</span>
    <span class="flex items-center gap-2"><span class="inline-block h-3 w-3 rounded-sm bg-error"></span>Down</span>
    <span class="flex items-center gap-2"><span class="inline-block h-3 w-3 rounded-sm bg-base-300"></span>No data</span>
  </div>

  <section class="flex flex-col gap-3">
    <JpingUptimeBar {period} {timeZone} refreshKey={pullTick} onPulled={onJpingPulled} />

    <h2 class="text-sm font-semibold uppercase tracking-wide opacity-60 mt-2">URLs ({sites.length})</h2>
    {#if loading}
      <div class="flex justify-center py-8"><span class="loading loading-spinner"></span></div>
    {:else if loadError && !lastPulledAt}
      <div role="alert" class="alert alert-error">
        <span>Couldn't load the URLs: {loadError}</span>
        <button class="btn btn-sm" onclick={() => loadSites(period, timeZone)}>Retry</button>
      </div>
    {:else}
      {#each sites as site (site.url)}
        {@render timeline(site)}
      {:else}
        <div class="text-center opacity-60 py-8">No URLs yet.{#if IS_ADMIN} Add one above.{/if}</div>
      {/each}
    {/if}
  </section>

  <p class="text-sm opacity-60" data-test-id="how-it-works">
    JPing checks each URL once a minute. A check succeeds when the URL returns a 2xx, 3xx, or 4xx status code,
    and fails when it returns a 5xx status code or doesn't respond in time. Uptime is the percentage of successful checks.
  </p>
</div>

<RemoveSiteDialog bind:this={removeDialog} onRemoved={onSiteRemoved} />

<style>
  /* A ring that keeps expanding from the green dot while pulls are succeeding. */
  @keyframes pulled {
    from { transform: scale(1); opacity: 0.75; }
    to { transform: scale(2.5); opacity: 0; }
  }

  .pulse {
    animation: pulled 1.5s ease-out infinite;
  }
</style>
