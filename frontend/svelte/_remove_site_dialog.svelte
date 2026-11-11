<script lang="ts">
let dialog: HTMLDialogElement;
let url: string | null = $state(null);
let onConfirm: (() => Promise<void>) | null = null;
let removing = $state(false);
let error = $state('');

export function open(siteUrl: string, confirm: () => Promise<void>) {
  url = siteUrl;
  onConfirm = confirm;
  error = '';
  dialog.showModal();
}

async function confirmRemove() {
  if (!onConfirm) return;
  removing = true;
  error = '';
  try {
    await onConfirm();
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
  onConfirm = null;
  error = '';
}
</script>

<dialog class="modal" bind:this={dialog} onclose={reset}>
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
