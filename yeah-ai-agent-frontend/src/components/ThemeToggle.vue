<script setup lang="ts">
import { computed } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import type { IconName } from '@/components/icons'
import { useTheme, type AutoReasonKind } from '@/composables/useTheme'
import type { Lang } from '@/types/itinerary'

const props = withDefaults(
  defineProps<{
    lang?: Lang
  }>(),
  { lang: 'zh' },
)

const { mode, isDark, autoReasonKind, cycleMode } = useTheme()
const isEn = computed(() => props.lang === 'en')

const icon = computed<IconName>(() => {
  if (mode.value === 'light') return 'sun'
  if (mode.value === 'dark') return 'moon'
  return 'circle-half'
})

const label = computed(() => {
  if (mode.value === 'light') return isEn.value ? 'Light' : '亮色'
  if (mode.value === 'dark') return isEn.value ? 'Dark' : '暗色'
  return isEn.value ? 'Auto' : '自动'
})

function reasonText(kind: AutoReasonKind): string {
  if (isEn.value) {
    switch (kind) {
      case 'system-night':
        return 'system preference + night hours'
      case 'system':
        return 'following system preference'
      case 'night':
        return 'night hours (18:00 – 06:00)'
      case 'day':
        return 'daytime light theme'
      default:
        return ''
    }
  }
  switch (kind) {
    case 'system-night':
      return '系统偏好 + 夜间时段'
    case 'system':
      return '跟随系统偏好'
    case 'night':
      return '夜间时段（18:00 - 06:00）'
    case 'day':
      return '日间浅色'
    default:
      return ''
  }
}

const title = computed(() => {
  const current = isDark.value
    ? isEn.value
      ? 'dark'
      : '暗色'
    : isEn.value
      ? 'light'
      : '亮色'
  if (mode.value === 'auto') {
    return isEn.value
      ? `Auto · ${reasonText(autoReasonKind.value)} (currently ${current}). Click for Light`
      : `自动主题 · ${reasonText(autoReasonKind.value)}（当前${current}），点击切换为亮色`
  }
  return isEn.value ? `${label.value} mode, click to switch` : `${label.value}模式，点击切换`
})
</script>

<template>
  <button
    type="button"
    :title="title"
    :aria-label="title"
    class="focus-ring group inline-flex items-center gap-1.5 rounded-full border border-gray-200/80 bg-white/70 px-3 py-1.5 text-xs font-medium text-gray-600 backdrop-blur transition-all hover:border-gray-300 hover:bg-white hover:text-gray-900 dark:border-gray-700/60 dark:bg-gray-800/60 dark:text-gray-400 dark:hover:border-gray-600 dark:hover:bg-gray-800 dark:hover:text-gray-100"
    @click="cycleMode"
  >
    <AppIcon :name="icon" class="h-4 w-4 transition-transform duration-300 group-hover:rotate-12" />
    <span>{{ label }}</span>
  </button>
</template>
