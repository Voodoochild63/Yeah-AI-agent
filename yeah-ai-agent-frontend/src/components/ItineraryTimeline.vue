<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import SunsetCard from '@/components/SunsetCard.vue'
import TimelineNodeCard from '@/components/TimelineNodeCard.vue'
import TransitBadge from '@/components/TransitBadge.vue'
import TransitModeSelector from '@/components/TransitModeSelector.vue'
import type { ItineraryPlan, Lang, TravelMode } from '@/types/itinerary'
import { applyTravelMode, parkingVisible, drivingForbiddenOn, isAlcoholStop } from '@/utils/transitMode'
import { getGoogleMapsDirectionsUrl } from '@/utils/maps'
import { formatItineraryPlainText } from '@/utils/itineraryText'
import { pickLocalized, pickLocalizedList } from '@/utils/localeText'
import { bindActiveVenues, bindSunset } from '@/utils/bindActiveVenues'

const props = withDefaults(
  defineProps<{
    plan: ItineraryPlan
    lang?: Lang
    shufflingIndex?: number | null
  }>(),
  { lang: 'zh', shufflingIndex: null },
)

const emit = defineEmits<{
  shuffle: [index: number, hint: string]
}>()

const schedule = computed(() => props.plan.schedule ?? [])
const suggestions = computed(() =>
  pickLocalizedList(
    props.lang ?? 'zh',
    props.plan.backupSuggestionsZh,
    props.plan.backupSuggestionsEn,
    props.plan.backupSuggestions,
  ).map((item) => bindActiveVenues(item, schedule.value)),
)
const isEn = computed(() => props.lang === 'en')

/** 主 POI 卡片与通勤标签共用，保证左边缘对齐 */
const TIMELINE_INSET = 'ml-6'

const fallbackTitle = computed(() => (isEn.value ? 'Your Sydney itinerary' : '你的悉尼行程'))
const displayTitle = computed(() =>
  bindActiveVenues(
    pickLocalized(props.lang ?? 'zh', props.plan.titleZh, props.plan.titleEn, props.plan.title) ||
      fallbackTitle.value,
    schedule.value,
  ),
)
const emptySchedule = computed(() =>
  isEn.value
    ? 'No timeline stops this time. Try rephrasing your request?'
    : '这次没有解析出时间轴节点，换个说法再试试？',
)
function isTerminalTransit(text: string) {
  return /^(行程结束|本日结束|终点|end of itinerary|end of the day|the end)$/i.test(text.trim())
}

function hasConnector(node: (typeof schedule.value)[number]) {
  if (node.transitOptions) return true
  return Boolean(node.transitToNext && !isTerminalTransit(node.transitToNext))
}

function onSelectMode(index: number, mode: TravelMode) {
  const node = schedule.value[index]
  const next = schedule.value[index + 1]
  if (mode === 'DRIVING' && (drivingForbiddenOn(node) || isAlcoholStop(next))) {
    return
  }
  applyTravelMode(schedule.value, index, mode, props.lang ?? 'zh')
}
const backupHeading = computed(() =>
  isEn.value ? 'Backup ideas & travel notes' : '灵感备选与出行须知',
)
const routeUrl = computed(() =>
  getGoogleMapsDirectionsUrl(schedule.value.map((node) => node.poiName)),
)
const routeLabel = computed(() => (isEn.value ? 'Open Route in Google Maps' : '在地图中查看完整路线'))
const detailedDescription = computed(() =>
  bindActiveVenues(
    pickLocalized(
      props.lang ?? 'zh',
      props.plan.detailedDescriptionZh,
      props.plan.detailedDescriptionEn,
      props.plan.detailedDescription || props.plan.overview,
    ),
    schedule.value,
  ),
)
const displaySummary = computed(() =>
  bindActiveVenues(
    pickLocalized(props.lang ?? 'zh', props.plan.summaryZh, props.plan.summaryEn, props.plan.summary),
    schedule.value,
  ),
)
const displaySunset = computed(() => bindSunset(props.plan.sunsetEvaluation, schedule.value))
const copyLabel = computed(() => (isEn.value ? '📋 Copy' : '📋 复制行程'))
const toast = ref('')
let toastTimer: number | undefined

function showToast(message: string) {
  toast.value = message
  if (toastTimer !== undefined) window.clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => {
    toast.value = ''
    toastTimer = undefined
  }, 2_000)
}

async function copyItinerary() {
  const text = formatItineraryPlainText(props.plan, props.lang)
  try {
    await navigator.clipboard.writeText(text)
  } catch {
    try {
      const area = document.createElement('textarea')
      area.value = text
      area.setAttribute('readonly', '')
      area.style.position = 'fixed'
      area.style.left = '-9999px'
      document.body.appendChild(area)
      area.select()
      const ok = document.execCommand('copy')
      area.remove()
      if (!ok) throw new Error('copy failed')
    } catch {
      showToast(isEn.value ? 'Couldn’t copy. Try again.' : '复制失败，请再试一次。')
      return
    }
  }
  showToast(isEn.value ? 'Itinerary copied' : '行程已复制到剪贴板')
}

onBeforeUnmount(() => {
  if (toastTimer !== undefined) window.clearTimeout(toastTimer)
})
</script>

<template>
  <div class="animate-fade-up">
    <!-- 标题与摘要 -->
    <header class="mb-7">
      <div class="flex flex-wrap items-start justify-between gap-3">
        <h2 class="min-w-0 flex-1 text-xl leading-snug font-semibold text-gray-900 sm:text-2xl dark:text-gray-50">
          {{ displayTitle }}
        </h2>
        <div class="flex shrink-0 flex-wrap items-center justify-end gap-2">
          <button
            type="button"
            class="focus-ring inline-flex items-center gap-1 rounded-full border border-gray-200/80 bg-white/70 px-3 py-1.5 text-xs font-medium text-gray-600 shadow-sm backdrop-blur transition-all hover:-translate-y-0.5 hover:border-gray-300 hover:bg-white hover:text-gray-900 dark:border-gray-700/70 dark:bg-gray-800/60 dark:text-gray-300 dark:hover:border-gray-600 dark:hover:bg-gray-800 dark:hover:text-gray-50"
            @click="copyItinerary"
          >
            {{ copyLabel }}
          </button>
          <a
            v-if="routeUrl"
            :href="routeUrl"
            target="_blank"
            rel="noopener noreferrer"
            class="focus-ring inline-flex items-center gap-1.5 rounded-full border border-blue-200/80 bg-blue-50/80 px-3.5 py-1.5 text-xs font-medium text-blue-700 shadow-sm transition-all hover:-translate-y-0.5 hover:bg-blue-100 dark:border-blue-500/30 dark:bg-blue-500/10 dark:text-blue-300 dark:hover:bg-blue-500/20"
          >
            <AppIcon name="map-pin" class="h-3.5 w-3.5" />
            {{ routeLabel }}
          </a>
        </div>
      </div>
      <div
        v-if="displaySummary || detailedDescription"
        class="mt-3 rounded-xl border border-gray-100 bg-white/70 p-4 backdrop-blur dark:border-gray-700/50 dark:bg-gray-800/40"
      >
        <p v-if="displaySummary" class="text-sm leading-relaxed text-gray-600 dark:text-gray-300">
          {{ displaySummary }}
        </p>
        <p
          v-if="detailedDescription"
          class="text-[13px] leading-relaxed whitespace-pre-wrap text-zinc-600 dark:text-zinc-300"
          :class="displaySummary ? 'mt-3 border-t border-black/5 pt-3 dark:border-white/5' : ''"
        >
          {{ detailedDescription }}
        </p>
      </div>
    </header>

    <Teleport to="body">
      <Transition
        enter-active-class="transition duration-200 ease-out"
        enter-from-class="translate-y-2 opacity-0"
        leave-active-class="transition duration-200 ease-in"
        leave-to-class="translate-y-1 opacity-0"
      >
        <p
          v-if="toast"
          role="status"
          class="fixed bottom-6 left-1/2 z-50 -translate-x-1/2 rounded-full border border-gray-200/80 bg-gray-900/90 px-4 py-2 text-xs font-medium text-white shadow-lg backdrop-blur dark:border-white/10 dark:bg-white/90 dark:text-gray-900"
        >
          {{ toast }}
        </p>
      </Transition>
    </Teleport>

    <!-- 时间轴 -->
    <section v-if="schedule.length" class="relative">
      <!-- 主干引导线 -->
      <div
        aria-hidden="true"
        class="absolute top-2 bottom-2 left-0 border-l-2 border-gray-200 dark:border-gray-700"
      />

      <div v-for="(node, index) in schedule" :key="node.nodeId || index" class="relative">
        <!-- 发光圆点：10px 宽，居中压在 2px 的引导线上 -->
        <span
          aria-hidden="true"
          class="absolute top-5 -left-1 h-2.5 w-2.5 rounded-full bg-blue-500 ring-4 ring-white dark:ring-gray-900"
        />
        <span
          aria-hidden="true"
          class="absolute top-5 -left-1 h-2.5 w-2.5 animate-ping rounded-full bg-blue-500/40"
          :style="{ animationDelay: `${index * 0.4}s`, animationDuration: '3s' }"
        />

        <TimelineNodeCard
          :node="node"
          :lang="lang"
          :shuffling="shufflingIndex === index"
          :show-parking="parkingVisible(schedule, index)"
          :class="TIMELINE_INSET"
          @shuffle="emit('shuffle', index, $event)"
        />

        <!-- 通勤衔接：与白色主卡片同一左边距，左边缘对齐 -->
        <div
          v-if="hasConnector(node)"
          class="relative z-10 -mt-3 mb-3 flex"
          :class="TIMELINE_INSET"
        >
          <TransitModeSelector
            v-if="node.transitOptions"
            :selected-mode="node.selectedMode"
            :transit-options="node.transitOptions"
            :lang="lang"
            :driving-forbidden="Boolean(drivingForbiddenOn(node) || isAlcoholStop(schedule[index + 1]))"
            @select="onSelectMode(index, $event)"
          />
          <TransitBadge v-else :text="node.transitToNext" :lang="lang" />
        </div>
      </div>

      <!-- 终点 -->
      <div class="relative flex items-center gap-2 pb-2" :class="TIMELINE_INSET">
        <span
          aria-hidden="true"
          class="absolute -left-7 h-2.5 w-2.5 rounded-full border-2 border-gray-300 bg-white ring-4 ring-white dark:border-gray-600 dark:bg-gray-900 dark:ring-gray-900"
        />
        <span class="text-xs text-gray-400 dark:text-gray-500">{{ lang === 'en' ? 'End of Itinerary' : '行程结束' }}</span>
      </div>
    </section>

    <p v-else class="text-sm text-gray-500 dark:text-gray-400">
      {{ emptySchedule }}
    </p>

    <!-- 落日高亮卡 -->
    <SunsetCard v-if="displaySunset" :sunset="displaySunset" :lang="lang" class="mt-6" />

    <!-- 备选建议 -->
    <section
      v-if="suggestions.length"
      class="mt-6 rounded-xl border border-gray-100 bg-white/70 p-4 backdrop-blur dark:border-gray-700/50 dark:bg-gray-800/40"
    >
      <h3
        class="flex items-center gap-1.5 text-xs font-semibold tracking-wider text-gray-500 uppercase dark:text-gray-400"
      >
        <AppIcon name="sparkle" class="h-3.5 w-3.5" />
        {{ backupHeading }}
      </h3>
      <ul class="mt-3 space-y-2.5">
        <li
          v-for="(suggestion, index) in suggestions"
          :key="index"
          class="flex items-start gap-2.5 text-sm leading-relaxed text-gray-600 dark:text-gray-300"
        >
          <span
            aria-hidden="true"
            class="mt-[0.45rem] h-1.5 w-1.5 shrink-0 rounded-full bg-gray-300 dark:bg-gray-600"
          />
          <span>{{ suggestion }}</span>
        </li>
      </ul>
    </section>
  </div>
</template>
