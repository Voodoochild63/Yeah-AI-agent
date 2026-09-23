<script setup lang="ts">
import { computed } from 'vue'
import { ICONS, type IconName } from '@/components/icons'

const props = withDefaults(
  defineProps<{
    name: IconName
    /** 描边粗细，默认 1.75 */
    weight?: number
    /** 实心填充（星标评分用） */
    filled?: boolean
  }>(),
  { weight: 1.75, filled: false },
)

const inner = computed(() => ICONS[props.name] ?? '')
</script>

<template>
  <svg
    viewBox="0 0 24 24"
    fill="none"
    :stroke-width="weight"
    stroke="currentColor"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    focusable="false"
    :class="filled ? 'fill-current' : ''"
    v-html="inner"
  />
</template>
