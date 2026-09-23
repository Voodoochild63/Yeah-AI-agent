<script setup lang="ts">
import { computed } from 'vue'
import type { ItineraryNode, Lang } from '@/types/itinerary'
import { getGoogleMapsParkingUrl } from '@/utils/maps'

const props = withDefaults(
  defineProps<{
    node: ItineraryNode
    lang?: Lang
  }>(),
  { lang: 'zh' },
)

const isEn = computed(() => props.lang === 'en')

const tip = computed(() => {
  const parking = props.node.parkingInfo
  return (
    parking?.tips?.trim() ||
    parking?.tip?.trim() ||
    props.node.parkingTips?.trim() ||
    (isEn.value ? 'Look for nearby street or lot parking' : '建议寻找周边路边或就近车位')
  )
})

const mapsHref = computed(() => {
  const parking = props.node.parkingInfo
  const q =
    parking?.parkingSearchKeyword?.trim() ||
    props.node.parkingSearchKeyword?.trim() ||
    (props.node.poiName?.trim() ? `parking near ${props.node.poiName.trim()} Sydney` : 'parking near Sydney')
  return getGoogleMapsParkingUrl(q)
})
</script>

<template>
  <div
    class="mt-3 flex flex-col justify-between gap-2.5 rounded-xl border border-blue-100/80 bg-blue-50/60 p-3 sm:flex-row sm:items-center dark:border-blue-900/30 dark:bg-blue-950/20"
  >
    <div class="flex items-start gap-2">
      <span class="mt-0.5 text-sm text-blue-500" aria-hidden="true">🅿️</span>
      <div class="text-xs text-zinc-600 dark:text-zinc-300">
        <span class="font-semibold text-blue-600 dark:text-blue-400">
          {{ isEn ? 'Parking Tip: ' : '停车提示：' }}
        </span>
        <span>{{ tip }}</span>
      </div>
    </div>

    <a
      :href="mapsHref"
      target="_blank"
      rel="noopener noreferrer"
      class="inline-flex shrink-0 cursor-pointer items-center justify-center gap-1.5 rounded-lg border border-blue-200/60 bg-white px-3 py-1.5 text-xs font-medium text-blue-600 shadow-sm transition-all hover:bg-blue-50 dark:border-blue-800/40 dark:bg-zinc-800 dark:text-blue-400 dark:hover:bg-zinc-700"
      :title="
        isEn
          ? 'Search real nearby parking spots on Google Maps'
          : '在 Google Maps 查看该打卡点周边的真实车库与车位'
      "
    >
      <span aria-hidden="true">📍</span>
      <span>{{ isEn ? 'Find Parking Nearby' : '地图查看周边车位' }}</span>
    </a>
  </div>
</template>
