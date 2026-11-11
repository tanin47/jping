<script lang="ts">
// The websites and their uptime timelines are loaded from the server.
import { untrack } from 'svelte';
import AddSiteForm from './_add_site_form.svelte';
import JpingUptimeBar from './_jping_uptime_bar.svelte';
import PendingBar from './_pending_bar.svelte';
import RemoveSiteDialog from './_remove_site_dialog.svelte';
import { api } from './globals';
import type { Bucket, Period, Site, Status, WebsiteUrl } from './models';
import {
  BAR_COLOR,
  BUCKET_COUNT,
  DOT_COLOR,
  PERIODS,
  TEXT_COLOR,
  formatAxis,
  formatPercent,
  formatTime,
  rangeOf,
  rangeParams,
  statusOf,
} from './uptime';

let periodKey = $state('7d');
let period = $derived(PERIODS.find((p) => p.key === periodKey)!);

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
    checks: json.checks,
    successfulChecks: json.successfulChecks,
    uptime: json.uptime,
    status: statusOf(json.uptime),
  };
}

// Weighted by the number of checks, so a quiet bucket doesn't count as much as a busy one.
function overallUptime(buckets: Bucket[]): number | null {
  const checks = buckets.reduce((sum, b) => sum + b.checks, 0);
  const successful = buckets.reduce((sum, b) => sum + b.successfulChecks, 0);
  return checks === 0 ? null : (successful * 100) / checks;
}

function currentStatus(buckets: Bucket[]): Status {
  for (let i = buckets.length - 1; i >= 0; i--) {
    if (buckets[i].status !== 'none') return buckets[i].status;
  }
  return 'none';
}

// Placeholder buckets with no data, for a website whose uptime hasn't been loaded yet.
function emptyBuckets(p: Period): Bucket[] {
  const { start, end } = rangeOf(p);
  const bucketMs = (end.getTime() - start.getTime()) / BUCKET_COUNT;
  return Array.from({ length: BUCKET_COUNT }, (_, i) => ({
    start: new Date(start.getTime() + i * bucketMs),
    end: new Date(start.getTime() + (i + 1) * bucketMs),
    checks: 0,
    successfulChecks: 0,
    uptime: null,
    status: 'none' as Status,
  }));
}

function formatTip(bucket: Bucket, p: Period): string {
  const range = `${formatTime(bucket.start, p)} – ${formatTime(bucket.end, p)}`;
  if (bucket.uptime === null) return `${range}: No data`;
  const failed = bucket.checks - bucket.successfulChecks;
  return `${range}: ${formatPercent(bucket.uptime)} (${failed} of ${bucket.checks} checks failed)`;
}

// --- Server calls ---

// Counts requests so a slow response for an old period can't overwrite a newer one.
let latestRequest = 0;

async function loadSites(p: Period) {
  const request = ++latestRequest;
  // Show the spinner only on the first load, not on every refresh.
  loading = lastPulledAt === null;
  loadError = '';
  try {
    const json = await api('GET', `/api/websites/uptime?${rangeParams(p)}`);
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

// Loads on mount, whenever the period changes, and on every auto refresh.
$effect(() => {
  const p = period;
  pullTick;
  // Only the period should trigger a reload, not the state that loadSites reads.
  untrack(() => loadSites(p));
});

// --- Add website ---

function onSiteAdded(website: WebsiteUrl) {
  sites.push({ ...website, buckets: emptyBuckets(period) });
  loadSites(period);
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
        <button class="btn btn-ghost btn-sm btn-square" aria-label="Remove {site.url}" title="Remove" data-test-id="remove-website-button" data-test-value={site.url} onclick={() => askToRemove(site)}>
          <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" /></svg>
        </button>
      </div>

      <div class="flex h-8 gap-px">
        {#each buckets as bucket}
          <div
            class="tooltip flex-1 min-w-0"
            data-tip={formatTip(bucket, period)}
          >
            <div class="h-full w-full rounded-sm {BAR_COLOR[bucket.status]} hover:opacity-70"></div>
          </div>
        {/each}
        <PendingBar />
      </div>

      <div class="flex justify-between text-xs opacity-60">
        <span>{formatAxis(buckets[0].start, period)}</span>
        <span>Now</span>
      </div>
    </div>
  </div>
{/snippet}

<div class="container mx-auto max-w-4xl p-4 sm:p-8 flex flex-col gap-6">
  <header class="flex flex-wrap items-center gap-4">
    <img src="/assets/images/logo.png" alt="" class="h-[32px]" />
    <h1 class="text-2xl font-bold flex-1">JPing</h1>
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
          {lastPulledAt.toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' })}
        {/if}
      </span>
    </span>
    <select class="select select-sm w-auto" bind:value={periodKey} aria-label="Period">
      {#each PERIODS as p}
        <option value={p.key}>{p.label}</option>
      {/each}
    </select>
  </header>

  <AddSiteForm existingUrls={sites.map((s) => s.url)} onAdded={onSiteAdded} />

  <div class="flex flex-wrap gap-4 text-sm opacity-80">
    <span class="flex items-center gap-2"><span class="inline-block h-3 w-3 rounded-sm bg-success"></span>Up</span>
    <span class="flex items-center gap-2"><span class="inline-block h-3 w-3 rounded-sm bg-warning"></span>Some failed checks</span>
    <span class="flex items-center gap-2"><span class="inline-block h-3 w-3 rounded-sm bg-error"></span>Down</span>
    <span class="flex items-center gap-2"><span class="inline-block h-3 w-3 rounded-sm bg-base-300"></span>No data</span>
  </div>

  <section class="flex flex-col gap-3">
    <JpingUptimeBar {period} refreshKey={pullTick} onPulled={onJpingPulled} />

    <h2 class="text-sm font-semibold uppercase tracking-wide opacity-60 mt-2">URLs ({sites.length})</h2>
    {#if loading}
      <div class="flex justify-center py-8"><span class="loading loading-spinner"></span></div>
    {:else if loadError && !lastPulledAt}
      <div role="alert" class="alert alert-error">
        <span>Couldn't load the URLs: {loadError}</span>
        <button class="btn btn-sm" onclick={() => loadSites(period)}>Retry</button>
      </div>
    {:else}
      {#each sites as site (site.url)}
        {@render timeline(site)}
      {:else}
        <div class="text-center opacity-60 py-8">No URLs yet. Add one above.</div>
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
