<script lang="ts">
// The website list is loaded from and saved to the server.
// The uptime timelines are still mock data generated in the browser.
import { onMount } from 'svelte';
import AddSiteForm from './_add_site_form.svelte';
import RemoveSiteDialog from './_remove_site_dialog.svelte';
import { api } from './globals';
import type { Bucket, Period, Site, Status, WebsiteUrl } from './models';

const PERIODS: Period[] = [
  { key: '24h', label: 'Last 24 hours', hours: 24, buckets: 48 },
  { key: '7d', label: 'Last 7 days', hours: 24 * 7, buckets: 84 },
  { key: '30d', label: 'Last 30 days', hours: 24 * 30, buckets: 90 },
  { key: '90d', label: 'Last 90 days', hours: 24 * 90, buckets: 90 },
];

let periodKey = $state('7d');
let period = $derived(PERIODS.find((p) => p.key === periodKey)!);

let sites: Site[] = $state([]);
let loading = $state(true);
let loadError = $state('');

const jping: Site = { url: 'JPing', status: 'active', flakiness: 0.4 };

// --- Mock data generation (seeded so a site's history stays stable) ---

function hash(text: string): number {
  let h = 2166136261;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
}

function random(seed: number): () => number {
  return () => {
    seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function statusOf(uptime: number): Status {
  if (uptime >= 99.9) return 'up';
  if (uptime >= 95) return 'degraded';
  return 'down';
}

function generateBuckets(site: Site, p: Period): Bucket[] {
  const rand = random(hash(site.url + p.key));
  const now = Date.now();
  const bucketMs = (p.hours * 3600 * 1000) / p.buckets;
  const buckets: Bucket[] = [];

  for (let i = 0; i < p.buckets; i++) {
    const roll = rand() * 100;
    let uptime = 100;
    if (roll < site.flakiness) {
      uptime = 40 + rand() * 50; // outage
    } else if (roll < site.flakiness * 3) {
      uptime = 95 + rand() * 4.8; // a few failed checks
    }

    const start = new Date(now - (p.buckets - i) * bucketMs);
    const end = new Date(start.getTime() + bucketMs);
    buckets.push({ start, end, uptime, status: statusOf(uptime) });
  }
  return buckets;
}

function overallUptime(buckets: Bucket[]): number {
  return buckets.reduce((sum, b) => sum + b.uptime, 0) / buckets.length;
}

// --- Formatting ---

function formatPercent(value: number): string {
  return value >= 99.995 ? '100%' : `${value.toFixed(2)}%`;
}

function formatTime(date: Date, p: Period): string {
  return p.hours <= 24
    ? date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    : date.toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

function formatAxis(date: Date, p: Period): string {
  return p.hours <= 24
    ? date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    : date.toLocaleDateString([], { month: 'short', day: 'numeric' });
}

const BAR_COLOR: Record<Status, string> = {
  up: 'bg-success',
  degraded: 'bg-warning',
  down: 'bg-error',
};

const TEXT_COLOR: Record<Status, string> = {
  up: 'text-success',
  degraded: 'text-warning',
  down: 'text-error',
};

// --- Server calls ---

function toSite(website: WebsiteUrl): Site {
  return { ...website, flakiness: random(hash(website.url))() * 3 };
}

async function loadSites() {
  loading = true;
  loadError = '';
  try {
    const json = await api('GET', '/api/websites');
    sites = (json.websites as WebsiteUrl[]).map(toSite);
  } catch (e) {
    loadError = (e as Error).message;
  } finally {
    loading = false;
  }
}

onMount(loadSites);

// --- Add website ---

function onSiteAdded(website: WebsiteUrl) {
  sites.push(toSite(website));
}

// --- Delete confirmation ---

let removeDialog: RemoveSiteDialog;

function askToRemove(site: Site) {
  removeDialog.open(site.url, async () => {
    await api('DELETE', '/api/websites', { url: site.url });
    sites = sites.filter((s) => s.url !== site.url);
  });
}
</script>

{#snippet timeline(site: Site, isSelf: boolean)}
  {@const buckets = generateBuckets(site, period)}
  {@const uptime = overallUptime(buckets)}
  {@const current = buckets[buckets.length - 1].status}
  <div class="card bg-base-200 {isSelf ? 'border border-primary/40' : ''}">
    <div class="card-body p-4 gap-3">
      <div class="flex items-center gap-3">
        <span class="status {current === 'up' ? 'status-success' : current === 'degraded' ? 'status-warning' : 'status-error'}"></span>
        <div class="min-w-0 flex-1">
          {#if isSelf}
            <div class="font-semibold">JPing <span class="badge badge-primary badge-sm ml-1">self</span></div>
            <div class="text-xs opacity-60">Uptime of this monitoring server</div>
          {:else}
            <a href={site.url} target="_blank" rel="noopener noreferrer" class="font-semibold link link-hover truncate block" data-test-id="website-url">{site.url}</a>
          {/if}
        </div>
        <div class="text-right">
          <div class="text-lg font-bold {TEXT_COLOR[statusOf(uptime)]}">{formatPercent(uptime)}</div>
          <div class="text-xs opacity-60">uptime</div>
        </div>
        {#if !isSelf}
          <button class="btn btn-ghost btn-sm btn-square" aria-label="Remove {site.url}" title="Remove" data-test-id="remove-website-button" data-test-value={site.url} onclick={() => askToRemove(site)}>
            <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" /></svg>
          </button>
        {/if}
      </div>

      <div class="flex h-8 gap-px">
        {#each buckets as bucket}
          <div
            class="tooltip flex-1 min-w-0"
            data-tip="{formatTime(bucket.start, period)} – {formatTime(bucket.end, period)}: {formatPercent(bucket.uptime)}"
          >
            <div class="h-full w-full rounded-sm {BAR_COLOR[bucket.status]} hover:opacity-70"></div>
          </div>
        {/each}
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
  </div>

  <section class="flex flex-col gap-3">
    {@render timeline(jping, true)}

    <h2 class="text-sm font-semibold uppercase tracking-wide opacity-60 mt-2">Websites ({sites.length})</h2>
    {#if loading}
      <div class="flex justify-center py-8"><span class="loading loading-spinner"></span></div>
    {:else if loadError}
      <div role="alert" class="alert alert-error">
        <span>Couldn't load the websites: {loadError}</span>
        <button class="btn btn-sm" onclick={loadSites}>Retry</button>
      </div>
    {:else}
      {#each sites as site (site.url)}
        {@render timeline(site, false)}
      {:else}
        <div class="text-center opacity-60 py-8">No websites yet. Add one above.</div>
      {/each}
    {/if}
  </section>
</div>

<RemoveSiteDialog bind:this={removeDialog} />
