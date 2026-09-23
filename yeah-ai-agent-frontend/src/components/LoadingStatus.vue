<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import type { Lang } from '@/types/itinerary'

const props = withDefaults(
  defineProps<{
    active: boolean
    lang?: Lang
  }>(),
  { lang: 'zh' },
)

const PHRASES: Record<Lang, string[]> = {
  zh: [
    '正在拆解您的行程与氛围需求...',
    '正在匹配周边高分独立咖啡馆与特色街区...',
    '正在筛选适合街头构图与光影的打卡机位...',
    '正在计算行车动线、停车建议与落日时机...',
  ],
  en: [
    'Analyzing your vibe and route requirements...',
    'Discovering local specialty coffee & curated spots...',
    'Scouting visual angles for street photography...',
    'Calculating routes, parking spots, and sunset timing...',
  ],
}

const index = ref(0)
const visible = ref(true)
let timer: number | undefined
let fadeTimer: number | undefined

const phrase = computed(() => {
  const lines = PHRASES[props.lang]
  return lines[index.value % lines.length] ?? lines[0]!
})

function clearTimers() {
  if (timer !== undefined) window.clearInterval(timer)
  if (fadeTimer !== undefined) window.clearTimeout(fadeTimer)
  timer = undefined
  fadeTimer = undefined
}

function start() {
  clearTimers()
  index.value = 0
  visible.value = true
  const lines = PHRASES[props.lang]
  timer = window.setInterval(() => {
    visible.value = false
    fadeTimer = window.setTimeout(() => {
      if (!props.active) return
      index.value = (index.value + 1) % lines.length
      visible.value = true
    }, 280)
  }, 3_500)
}

watch(
  () => [props.active, props.lang] as const,
  ([active]) => {
    if (active) start()
    else {
      clearTimers()
      visible.value = true
      index.value = 0
    }
  },
  { immediate: true },
)

onBeforeUnmount(clearTimers)
</script>

<template>
  <p class="mb-5 flex min-h-6 items-center gap-2 text-sm text-gray-500 dark:text-gray-400">
    <AppIcon name="spinner" class="h-4 w-4 shrink-0 animate-spin" />
    <span
      class="truncate transition-opacity duration-300"
      :class="visible ? 'opacity-100' : 'opacity-0'"
    >
      {{ phrase }}
    </span>
  </p>
</template>
