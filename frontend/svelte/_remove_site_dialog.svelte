<script lang="ts">
import { api } from './globals';

interface Props {
  // Called after the website has been deleted on the server.
  onRemoved: (url: string) => void;
}

let { onRemoved }: Props = $props();

let dialog: HTMLDialogElement;
let url: string | null = $state(null);
let removing = $state(false);
let error = $state('');

export function open(siteUrl: string) {
  url = siteUrl;
  error = '';
  dialog.showModal();
}

async function confirmRemove() {
  if (!url) return;
  const removedUrl = url;
  removing = true;
  error = '';
  try {
    await api('DELETE', '/api/websites', { url: removedUrl });
    onRemoved(removedUrl);
    dialog.close();
  } catch (e) {
    // Keep the dialog open so the user can see what went wrong and retry.
    error = (e as Error).message;
  } finally {
    removing = false;
  }
}

function reset() {
  url = null;
  error = '';
}
</script>

<dialog class="modal modal-bottom sm:modal-middle" bind:this={dialog} onclose={reset}>
  <div class="modal-box">
    <h3 class="text-lg font-bold">Remove website?</h3>
    <p class="py-4">
      JPing will stop monitoring <span class="font-semibold break-all" data-test-id="remove-dialog-url">{url}</span>
      and its uptime history will be deleted.
    </p>
    {#if error}
      <p class="text-error text-sm">{error}</p>
    {/if}
    <div class="modal-action">
      <form method="dialog">
        <button class="btn" disabled={removing} data-test-id="remove-dialog-cancel">Cancel</button>
      </form>
      <button class="btn btn-error" onclick={confirmRemove} disabled={removing} data-test-id="remove-dialog-confirm">
        {#if removing}<span class="loading loading-spinner loading-sm"></span>{/if}
        Remove
      </button>
    </div>
  </div>
  <form method="dialog" class="modal-backdrop">
    <button aria-label="Close" disabled={removing}>close</button>
  </form>
</dialog>
