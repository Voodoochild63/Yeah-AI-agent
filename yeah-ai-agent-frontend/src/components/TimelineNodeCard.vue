<script setup lang="ts">
import { computed } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import OpeningHoursStatus from '@/components/OpeningHoursStatus.vue'
import ParkingBlock from '@/components/ParkingBlock.vue'
import type { ItineraryNode, Lang } from '@/types/itinerary'
import { localizeDuration, pickLocalized } from '@/utils/localeText'
import { getGoogleMapsUrl } from '@/utils/maps'
import { isAlcoholStop as detectAlcohol } from '@/utils/transitMode'

const props = withDefaults(
  defineProps<{
    node: ItineraryNode
    lang?: Lang
    shuffling?: boolean
    showParking?: boolean | null
  }>(),
  { lang: 'zh', shuffling: false },
)

const emit = defineEmits<{
  shuffle: [hint: string]
}>()

const isEn = computed(() => props.lang === 'en')
const parkingVisible = computed(() => {
  if (!props.node.poiName?.trim()) return false
  if (detectAlcohol(props.node)) return false
  if (props.showParking === true) return true
  if (props.showParking === false) return false
  return props.node.parkingInfo?.hasParkingRecommendation !== false
})
const isAlcoholStop = computed(() => detectAlcohol(props.node))
const alcoholWarning = computed(() =>
  props.node.safetyWarning?.trim()
    || (isAlcoholStop.value
      ? isEn.value
        ? '🍷 Alcohol involved — use walking, public transport or Uber. Never drink and drive.'
        : '🍷 涉及饮酒，请使用公共交通或网约车出行，切勿酒驾'
      : ''),
)
const poiMapsUrl = computed(() => getGoogleMapsUrl(props.node.poiName))
const canOpenPoiMap = computed(() => Boolean(props.node.poiName?.trim()) && poiMapsUrl.value !== '#')
const untitledPoi = computed(() => (isEn.value ? 'Untitled place' : '未命名地点'))
const stayLabel = computed(() => localizeDuration(props.node.stayDuration, props.lang ?? 'zh'))
const categoryLabel = computed(() =>
  pickLocalized(props.lang ?? 'zh', props.node.categoryZh, props.node.categoryEn, props.node.category),
)
const mapsTitle = computed(() =>
  isEn.value ? `Open ${props.node.poiName} in Google Maps` : `在 Google Maps 打开 ${props.node.poiName}`,
)
const websiteHref = computed(() => {
  const url = props.node.websiteUrl?.trim()
  return url && /^https?:\/\//i.test(url) ? url : ''
})
const shuffleLabel = computed(() => (isEn.value ? 'Shuffle' : '换一个'))
const shufflePool = computed(() => props.node.candidates ?? props.node.sunsetCandidates ?? [])
const canShuffle = computed(() => shufflePool.value.length >= 2)
const currentCandidate = computed(() => {
  const name = props.node.poiName?.trim().toLowerCase()
  return shufflePool.value.find((item) => item.name?.trim().toLowerCase() === name)
})
const ratingLabel = computed(() => {
  const rating = currentCandidate.value?.rating
  if (rating == null) return ''
  const count = currentCandidate.value?.ratingCount
  return count ? `${rating.toFixed(1)} (${count})` : rating.toFixed(1)
})
const shuffleTitle = computed(() => {
  if (!canShuffle.value) {
    return isEn.value ? 'No other venues were prefetched for this stop' : '这一站还没有预置备选'
  }
  return isEn.value ? 'Switch to the next prefetched venue' : '切换到下一个预置地点，无需等待'
})

function onShuffle() {
  if (!canShuffle.value) return
  emit('shuffle', '')
}

function getRedNoteLink(node: ItineraryNode) {
  const generated = node.searchKeyword?.trim() ?? ''
  const name = node.poiName?.trim()
  const q = /[\u4e00-\u9fff]/.test(generated) ? generated : name ? `${name} 悉尼 攻略` : '悉尼 攻略'
  return `https://www.xiaohongshu.com/search_result?keyword=${encodeURIComponent(q)}`
}

function getRedditLink(node: ItineraryNode) {
  const q = node.redditKeyword?.trim() || node.poiName?.trim() || 'Sydney'
  return `https://www.reddit.com/r/sydney/search/?q=${encodeURIComponent(q)}&restrict_sr=1`
}
</script>

<template>
  <article
    class="relative mb-6 rounded-xl border border-gray-100 bg-white/80 p-4 shadow-sm backdrop-blur transition-all duration-300 hover:-translate-y-0.5 hover:border-gray-200 hover:shadow-md dark:border-gray-700/50 dark:bg-gray-800/50 dark:shadow-none dark:hover:border-gray-600/70 dark:hover:bg-gray-800/70"
    :class="shuffling ? 'ring-2 ring-emerald-400/80 dark:ring-emerald-400/50' : ''"
  >
    <!-- Header：到达时间 + 店名 + 单点替换 -->
    <header class="flex items-start justify-between gap-3">
      <div class="flex min-w-0 flex-1 flex-wrap items-baseline gap-x-2.5 gap-y-1">
      <span
        v-if="node.arrivalTime"
        class="rounded-md bg-blue-50 px-2 py-0.5 font-mono text-sm font-semibold tabular-nums text-blue-700 dark:bg-blue-500/15 dark:text-blue-300"
      >
        {{ node.estimatedArrival || node.arrivalTime }}
      </span>
      <h3 class="text-base leading-snug font-semibold text-gray-900 dark:text-gray-50">
        <a
          v-if="canOpenPoiMap"
          :href="poiMapsUrl"
          target="_blank"
          rel="noopener noreferrer"
          class="inline-flex items-center gap-1.5 rounded-sm underline-offset-4 transition-colors hover:text-blue-700 hover:underline dark:hover:text-blue-300"
          :title="mapsTitle"
        >
          {{ node.poiName }}
          <AppIcon name="map-pin" class="h-3.5 w-3.5 shrink-0 text-blue-500 dark:text-blue-400" />
        </a>
        <span v-else>{{ node.poiName || untitledPoi }}</span>
      </h3>
      </div>

      <div class="relative shrink-0">
        <button
          type="button"
          :title="shuffleTitle"
          :disabled="!canShuffle"
          class="focus-ring inline-flex cursor-pointer items-center gap-1 rounded-full border border-gray-200/80 bg-white/70 px-2 py-1 text-[0.7rem] font-medium text-gray-500 transition-colors hover:border-gray-300 hover:bg-black/5 hover:text-gray-800 disabled:cursor-not-allowed disabled:opacity-40 dark:border-gray-600/70 dark:bg-gray-900/40 dark:text-gray-400 dark:hover:bg-white/10 dark:hover:text-gray-100"
          @click="onShuffle"
        >
          <span aria-hidden="true">↻</span>
          {{ shuffleLabel }}
        </button>
      </div>
    </header>

    <OpeningHoursStatus
      :node="node"
      :lang="lang"
      :shuffling="shuffling"
      @shuffle="emit('shuffle', $event)"
    />

    <!-- 徽章：类别 + 建议停留 -->
    <div class="mt-2.5 flex flex-wrap items-center gap-2">
      <span v-if="categoryLabel" class="tag-chip bg-gray-100 text-gray-600 dark:bg-gray-700/60 dark:text-gray-300">
        <AppIcon name="tag" class="h-3 w-3" />
        {{ categoryLabel }}
      </span>
      <span
        v-if="ratingLabel"
        class="tag-chip bg-amber-50 text-amber-800 dark:bg-amber-500/15 dark:text-amber-200"
      >
        ★ {{ ratingLabel }}
      </span>
      <span
        v-if="node.stayDuration"
        class="tag-chip bg-violet-50 text-violet-700 dark:bg-violet-500/15 dark:text-violet-300"
      >
        <AppIcon name="clock" class="h-3 w-3" />
        {{ stayLabel }}
      </span>
    </div>

    <p
      v-if="isAlcoholStop"
      class="mt-3 rounded-lg border border-rose-200/80 bg-rose-50/80 px-3 py-2 text-xs font-medium leading-relaxed text-rose-800 dark:border-rose-500/30 dark:bg-rose-500/10 dark:text-rose-200"
    >
      {{ alcoholWarning || (isEn ? '🍷 Alcohol involved — walk, public transport or Uber. Never drink and drive.' : '🍷 涉及饮酒，请使用公共交通或网约车出行，切勿酒驾') }}
    </p>

    <!-- 停车推荐 -->
    <ParkingBlock v-if="parkingVisible && !isAlcoholStop" :node="node" :lang="lang" />

    <div class="mt-3 flex flex-wrap items-center gap-2 border-t border-black/5 pt-2.5 dark:border-white/5">
      <span class="select-none text-xs font-medium text-zinc-400 dark:text-zinc-500">
        {{ isEn ? 'Explore vibes:' : '深入探店:' }}
      </span>
      <a
        v-if="websiteHref"
        :href="websiteHref"
        target="_blank"
        rel="noopener noreferrer"
        class="inline-flex cursor-pointer items-center gap-1 rounded-full bg-sky-50 px-2.5 py-0.5 text-xs font-medium text-sky-700 transition-colors hover:bg-sky-100 dark:bg-sky-950/30 dark:text-sky-300 dark:hover:bg-sky-900/40"
        :title="isEn ? 'Open the official website' : '打开官方网站'"
      >
        <span class="text-[11px]" aria-hidden="true">🌐</span>
        <span>{{ isEn ? 'Website' : '官网' }}</span>
      </a>
      <a
        :href="getRedNoteLink(node)"
        target="_blank"
        rel="noopener noreferrer"
        class="inline-flex cursor-pointer items-center gap-1 rounded-full bg-red-50 px-2.5 py-0.5 text-xs font-medium text-red-600 transition-colors hover:bg-red-100 dark:bg-red-950/30 dark:text-red-400 dark:hover:bg-red-900/40"
        :title="isEn ? 'Search RedNote for photo angles & reviews' : '在小红书查看机位与真实避坑'"
      >
        <span class="text-[11px]" aria-hidden="true">📕</span>
        <span>{{ isEn ? 'RedNote' : '小红书' }}</span>
      </a>
      <a
        :href="getRedditLink(node)"
        target="_blank"
        rel="noopener noreferrer"
        class="inline-flex cursor-pointer items-center gap-1 rounded-full bg-orange-50 px-2.5 py-0.5 text-xs font-medium text-orange-600 transition-colors hover:bg-orange-100 dark:bg-orange-950/30 dark:text-orange-400 dark:hover:bg-orange-900/40"
        :title="isEn ? 'Search r/sydney for local discussions' : '在 Reddit 查看本地居民原声吐槽'"
      >
        <span class="text-[11px]" aria-hidden="true">💬</span>
        <span>Reddit</span>
      </a>
    </div>
  </article>
</template>
