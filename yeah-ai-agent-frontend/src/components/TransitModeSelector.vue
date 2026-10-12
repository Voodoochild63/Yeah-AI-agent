<script setup lang="ts">
import { computed } from 'vue'
import type { Lang, TransitOptions, TravelMode } from '@/types/itinerary'
import { formatModeLabel, optionFor } from '@/utils/transitMode'

const props = withDefaults(
  defineProps<{
    selectedMode?: TravelMode
    transitOptions: TransitOptions
    lang?: Lang
    drivingForbidden?: boolean
  }>(),
  { lang: 'zh', selectedMode: 'WALKING', drivingForbidden: false },
)

const emit = defineEmits<{
  select: [mode: TravelMode]
}>()

const modes: { mode: TravelMode; icon: string; nameZh: string; nameEn: string }[] = [
  { mode: 'WALKING', icon: '🚶', nameZh: '步行', nameEn: 'Walk' },
  { mode: 'DRIVING', icon: '🚗', nameZh: '驾车', nameEn: 'Drive' },
  { mode: 'TRANSIT', icon: '🚌', nameZh: '公交', nameEn: 'Transit' },
]

const drivingOff = computed(
  () => props.drivingForbidden || props.transitOptions?.drivingAllowed === false,
)
const visibleModes = computed(() =>
  drivingOff.value ? modes.filter((item) => item.mode !== 'DRIVING') : modes,
)
const hint = computed(() =>
  drivingOff.value
    ? props.lang === 'en'
      ? 'Walk / public transport (Bus/Light Rail) / Uber'
      : '步行 / 公共交通（Bus/Light Rail） / Uber 网约车'
    : '',
)

function onSelect(mode: TravelMode) {
  if (mode === 'DRIVING' && drivingOff.value) return
  emit('select', mode)
}
const active = computed(() => {
  const selected = props.selectedMode ?? 'WALKING'
  if (selected === 'DRIVING' && drivingOff.value) return 'TRANSIT'
  return selected
})
const activeOption = computed(() => optionFor(props.transitOptions, active.value))
const summary = computed(() => formatModeLabel(active.value, activeOption.value, props.lang ?? 'zh'))

function tone(mode: TravelMode) {
  const on = active.value === mode
  if (mode === 'WALKING') {
    return on
      ? 'bg-emerald-100 text-emerald-800 ring-emerald-300/80 dark:bg-emerald-500/20 dark:text-emerald-200 dark:ring-emerald-400/30'
      : 'text-gray-500 hover:bg-black/5 dark:text-gray-400 dark:hover:bg-white/10'
  }
  if (mode === 'DRIVING') {
    return on
      ? 'bg-sky-100 text-sky-800 ring-sky-300/80 dark:bg-sky-500/20 dark:text-sky-200 dark:ring-sky-400/30'
      : 'text-gray-500 hover:bg-black/5 dark:text-gray-400 dark:hover:bg-white/10'
  }
  return on
    ? 'bg-amber-100 text-amber-800 ring-amber-300/80 dark:bg-amber-500/20 dark:text-amber-200 dark:ring-amber-400/30'
    : 'text-gray-500 hover:bg-black/5 dark:text-gray-400 dark:hover:bg-white/10'
}
</script>

<template>
  <div
    class="inline-flex max-w-full flex-wrap items-center gap-2 rounded-full border border-gray-200/80 bg-white/80 py-1 pr-3 pl-1 shadow-sm backdrop-blur dark:border-gray-700/60 dark:bg-gray-800/70"
    role="group"
    :aria-label="lang === 'en' ? 'Travel mode' : '出行方式'"
  >
    <div class="flex items-center rounded-full bg-gray-100/80 p-0.5 dark:bg-gray-900/50">
      <button
        v-for="item in visibleModes"
        :key="item.mode"
        type="button"
        class="focus-ring inline-flex h-7 w-7 items-center justify-center rounded-full text-sm ring-1 ring-transparent transition-colors"
        :class="tone(item.mode)"
        :aria-pressed="active === item.mode"
        :title="lang === 'en' ? item.nameEn : item.nameZh"
        @click="onSelect(item.mode)"
      >
        <span aria-hidden="true">{{ item.icon }}</span>
        <span class="sr-only">{{ lang === 'en' ? item.nameEn : item.nameZh }}</span>
      </button>
    </div>
    <span class="text-xs font-medium text-gray-700 dark:text-gray-200">
      {{ summary }}
    </span>
    <span v-if="hint" class="text-[11px] font-medium text-rose-700 dark:text-rose-300">
      {{ hint }}
    </span>
  </div>
</template>
