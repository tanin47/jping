<script lang="ts">
import { api } from './globals';
import type { WebsiteUrl } from './models';

interface Props {
  // Used to catch duplicates before calling the server.
  existingUrls: string[];
  // Called after the website has been saved on the server.
  onAdded: (website: WebsiteUrl) => void;
}

let { existingUrls, onAdded }: Props = $props();

let newUrl = $state('');
let error = $state('');
let adding = $state(false);

function validate(url: string): string | null {
  if (!url.startsWith('http://') && !url.startsWith('https://')) {
    return 'The URL must start with http:// or https://';
  }
  try {
    new URL(url);
  } catch {
    return 'This is not a valid URL.';
  }
  if (existingUrls.includes(url)) {
    return 'This website is already being monitored.';
  }
  return null;
}

async function submit(event: SubmitEvent) {
  event.preventDefault();
  const url = newUrl.trim();

  const validationError = validate(url);
  if (validationError) {
    error = validationError;
    return;
  }

  adding = true;
  try {
    const json = await api('POST', '/api/websites', { url });
    onAdded(json.website as WebsiteUrl);
    newUrl = '';
    error = '';
  } catch (e) {
    error = (e as Error).message;
  } finally {
    adding = false;
  }
}
</script>

<form class="flex flex-col gap-1" onsubmit={submit} novalidate>
  <div class="join w-full">
    <input
      type="url"
      class="input join-item flex-1 {error ? 'input-error' : ''}"
      placeholder="https://your-website.com"
      bind:value={newUrl}
      oninput={() => (error = '')}
      aria-label="Website URL"
      data-test-id="new-url-input"
    />
    <button type="submit" class="btn btn-primary join-item" disabled={adding} data-test-id="add-website-button">
      {#if adding}<span class="loading loading-spinner loading-sm"></span>{/if}
      Add website
    </button>
  </div>
  {#if error}
    <p class="text-error text-sm" data-test-id="add-website-error">{error}</p>
  {/if}
</form>
