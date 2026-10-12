<script setup lang="ts">
import { computed } from 'vue'
import type { ItineraryNode, Lang } from '@/types/itinerary'

const props = withDefaults(
  defineProps<{
    node: ItineraryNode
    lang?: Lang
    shuffling?: boolean
  }>(),
  { lang: 'zh', shuffling: false },
)

const emit = defineEmits<{
  shuffle: [hint: string]
}>()

const isEn = computed(() => props.lang === 'en')
const status = computed(() => props.node.openStatus)
const arrival = computed(() => props.node.estimatedArrival || props.node.arrivalTime || '')
const hoursText = computed(() => props.node.openingHoursText?.trim() || '')

const outdoor = computed(() => {
  const blob = `${props.node.poiName ?? ''} ${props.node.category ?? ''} ${props.node.categoryEn ?? ''} ${props.node.categoryZh ?? ''}`
  return /\b(park|lookout|viewpoint|beach|garden|reserve|trail|headland)\b|公园|观景|海滩|步道|海滨/i.test(blob)
})

const allDay = computed(() => {
  const text = `${hoursText.value} ${props.node.statusWarning ?? ''} ${props.node.closesAt ?? ''}`
  return (
    props.node.closesAt === '24:00' ||
    /24\s*hour|全天开放|常年开放|open year-round/i.test(text) ||
    (outdoor.value && (!status.value || status.value === 'UNKNOWN' || status.value === 'OPEN') && !hoursText.value)
  )
})

const openLabel = computed(() => {
  if (allDay.value) {
    return isEn.value ? 'Open 24 hours' : '24小时开放'
  }
  if (hoursText.value) {
    return isEn.value ? `Open · ${hoursText.value}` : `营业中 · ${hoursText.value}`
  }
  return isEn.value ? `Open · arrive ${arrival.value}` : `营业中 · 预计 ${arrival.value} 抵达`
})

const showUnknown = computed(
  () => status.value === 'UNKNOWN' && !allDay.value && !hoursText.value && !outdoor.value,
)

const closedHint = computed(() =>
  isEn.value
    ? 'This stop will be closed on arrival; replace with a venue that is open then.'
    : '该店预计到达时已打烊，请换成当时仍营业的同类型地点。',
)

function onReplace() {
  if (props.shuffling) return
  emit('shuffle', closedHint.value)
}
</script>

<template>
  <div v-if="status === 'OPEN' || allDay" class="mt-2.5">
    <span
      class="inline-flex items-center gap-1 rounded-full bg-emerald-50 px-2.5 py-0.5 text-xs font-medium text-emerald-800 ring-1 ring-emerald-200/80 dark:bg-emerald-500/15 dark:text-emerald-200 dark:ring-emerald-400/30"
      :title="node.statusWarning || hoursText"
    >
      🟢 {{ openLabel }}
    </span>
  </div>

  <div v-else-if="status === 'CLOSING_SOON'" class="mt-2.5">
    <span
      class="inline-flex items-center gap-1 rounded-full bg-amber-50 px-2.5 py-0.5 text-xs font-medium text-amber-800 ring-1 ring-amber-200/80 dark:bg-amber-500/15 dark:text-amber-200 dark:ring-amber-400/30"
      :title="node.statusWarning"
    >
      🟡 {{ isEn ? 'Closing soon · go now' : '即将打烊 · 建议抓紧时间' }}
    </span>
  </div>

  <div
    v-else-if="status === 'CLOSED'"
    class="mt-2.5 flex flex-wrap items-center gap-2 rounded-lg bg-red-50 px-2.5 py-1.5 ring-1 ring-red-200/80 dark:bg-red-500/10 dark:ring-red-400/30"
  >
    <p class="min-w-0 flex-1 text-xs font-medium leading-relaxed text-red-800 dark:text-red-200">
      🔴
      {{
        isEn
          ? `Closed-door risk · arrive ${arrival}, but the venue is already closed`
          : `闭店风险 · 预计 ${arrival} 抵达，但该店已打烊`
      }}
    </p>
    <button
      type="button"
      class="focus-ring inline-flex shrink-0 cursor-pointer items-center gap-1 rounded-full bg-red-600 px-2.5 py-1 text-[0.7rem] font-medium text-white hover:bg-red-500 disabled:cursor-wait disabled:opacity-60 dark:bg-red-400 dark:text-red-950 dark:hover:bg-red-300"
      :disabled="shuffling"
      :title="isEn ? 'Replace only this stop' : '只替换这一站'"
      @click="onReplace"
    >
      <span aria-hidden="true" :class="shuffling ? 'inline-block animate-spin' : ''">🔄</span>
      {{ isEn ? 'Shuffle' : '换一家' }}
    </button>
  </div>

  <div v-else-if="hoursText" class="mt-2.5">
    <span
      class="inline-flex items-center rounded-full bg-emerald-50 px-2.5 py-0.5 text-xs font-medium text-emerald-800 ring-1 ring-emerald-200/80 dark:bg-emerald-500/15 dark:text-emerald-200 dark:ring-emerald-400/30"
    >
      🟢 {{ hoursText }}
    </span>
  </div>

  <div v-else-if="showUnknown" class="mt-2.5">
    <span
      class="inline-flex items-center rounded-full bg-gray-100 px-2.5 py-0.5 text-xs font-medium text-gray-500 ring-1 ring-gray-200/80 dark:bg-gray-800 dark:text-gray-400 dark:ring-gray-700"
      :title="node.statusWarning"
    >
      {{ isEn ? 'Opening hours unknown' : '营业时间未知' }}
    </span>
  </div>
</template>
