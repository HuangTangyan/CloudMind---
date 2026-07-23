<script setup>
import { Cloud } from '@lucide/vue'

defineProps({
  compact: {
    type: Boolean,
    default: false,
  },
})
</script>

<template>
  <div class="app-brand" :class="{ compact }">
    <span class="cloud-logo" aria-hidden="true"><Cloud :size="compact ? 18 : 21" :stroke-width="2.1" /></span>
    <strong>CloudMind</strong>
  </div>
</template>
