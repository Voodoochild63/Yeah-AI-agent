<script setup lang="ts">
import { computed } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import type { Lang, SunsetEvaluation } from '@/types/itinerary'

const props = withDefaults(
  defineProps<{ sunset: SunsetEvaluation; lang?: Lang }>(),
  { lang: 'zh' },
)

const isEn = computed(() => props.lang === 'en')

const good = computed(() => props.sunset.recommended)

/** 推荐走暖橙，不推荐走冷石板蓝，让用户一眼分辨 */
const shell = computed(() =>
  good.value
    ? 'border-orange-200 bg-orange-50 dark:border-orange-500/50 dark:bg-orange-950/20'
    : 'border-slate-200 bg-slate-50 dark:border-slate-600/50 dark:bg-slate-900/40',
)

const badge = computed(() =>
  good.value
    ? 'bg-orange-100 text-orange-700 dark:bg-orange-500/20 dark:text-orange-300'
    : 'bg-slate-200 text-slate-700 dark:bg-slate-700/60 dark:text-slate-300',
)

const heading = computed(() =>
  good.value ? 'text-orange-900 dark:text-orange-200' : 'text-slate-800 dark:text-slate-200',
)

const body = computed(() =>
  good.value ? 'text-orange-800/90 dark:text-orange-200/80' : 'text-slate-600 dark:text-slate-400',
)

const muted = computed(() =>
  good.value ? 'text-orange-700/70 dark:text-orange-300/60' : 'text-slate-500 dark:text-slate-500',
)

const tipShell = computed(() =>
  good.value ? 'bg-orange-100/60 dark:bg-orange-500/10' : 'bg-slate-200/50 dark:bg-slate-800/50',
)
</script>

<template>
  <section class="rounded-xl border p-4 backdrop-blur transition-colors" :class="shell">
    <header class="flex items-start gap-3">
      <span class="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg" :class="badge">
        <AppIcon :name="good ? 'sun-horizon' : 'cloud'" class="h-5 w-5" />
      </span>

      <div class="min-w-0 flex-1">
        <h3 class="text-sm leading-snug font-semibold" :class="heading">
          {{
            good
              ? isEn
                ? 'Golden hour pick'
                : '黄金落日推荐'
              : isEn
                ? 'Sunset hunting not recommended today'
                : '今日不建议专程追日落'
          }}
          <template v-if="good && sunset.recommendedSpot"> · {{ sunset.recommendedSpot }}</template>
        </h3>

        <div class="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs" :class="muted">
          <span v-if="sunset.sunsetTime" class="inline-flex items-center gap-1">
            <AppIcon name="sun-horizon" class="h-3.5 w-3.5" />
            {{ isEn ? 'Sunset' : '日落' }} {{ sunset.sunsetTime }}
          </span>
          <span v-if="sunset.goldenHourWindow" class="inline-flex items-center gap-1 font-medium">
            <AppIcon name="clock" class="h-3.5 w-3.5" />
            {{ isEn ? 'Golden Hour' : '黄金窗口' }} {{ sunset.goldenHourWindow }}
          </span>
          <!-- 降级兜底时后端会把这两个字段置为 null，用 typeof 判断而不是真值判断，0% 也要显示 -->
          <span v-if="typeof sunset.cloudCover === 'number'" class="inline-flex items-center gap-1">
            <AppIcon name="cloud" class="h-3.5 w-3.5" />
            {{ isEn ? 'Cloud Cover' : '云量' }} {{ sunset.cloudCover }}%
          </span>
          <span v-if="typeof sunset.rainProb === 'number'" class="inline-flex items-center gap-1">
            <AppIcon name="drop" class="h-3.5 w-3.5" />
            {{ isEn ? 'Precipitation' : '降水' }} {{ sunset.rainProb }}%
          </span>
        </div>
      </div>
    </header>

    <p v-if="sunset.recommendationReason" class="mt-3 text-sm leading-relaxed" :class="body">
      {{ sunset.recommendationReason }}
    </p>

    <div v-if="sunset.photoTip" class="mt-3 flex items-start gap-2 rounded-lg px-3 py-2.5" :class="tipShell">
      <AppIcon name="camera" class="mt-0.5 h-4 w-4 shrink-0" :class="muted" />
      <p class="text-xs leading-relaxed" :class="body">{{ sunset.photoTip }}</p>
    </div>
  </section>
</template>
