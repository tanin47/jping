<script lang="ts"></script>

<div class="tooltip h-full w-1 shrink-0" data-tip="Last 2 minutes: waiting for data" data-test-id="pending-bar">
  <div class="pending h-full w-full rounded-sm bg-base-content"></div>
</div>

<style>
  @keyframes pending {
    0%, 100% { opacity: 0.8; }
    50% { opacity: 0.1; }
  }

  .pending {
    animation: pending 3.5s ease-in-out infinite;
  }
</style>
