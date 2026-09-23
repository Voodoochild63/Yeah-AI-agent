<script setup lang="ts">
import { computed } from 'vue'
import type { Lang } from '@/types/itinerary'
import { localizeTransit } from '@/utils/localeText'

const props = withDefaults(
  defineProps<{ text: string; lang?: Lang }>(),
  { lang: 'zh' },
)

const label = computed(() => localizeTransit(props.text, props.lang))

/**
 * 后端 transitToNext 是自由文本（"步行 8 分钟前往 The Record Store"），
 * 这里按关键词挑一个贴切的 emoji 前缀，让时间轴一眼能扫出交通方式。
 */
const emoji = computed(() => {
  const value = props.text.toLowerCase()
  if (/(步行|walk|foot)/.test(value)) return '🚶'
  if (/(驾车|开车|自驾|drive|driving|car)/.test(value)) return '🚗'
  if (/(轻轨|light rail|tram)/.test(value)) return '🚊'
  if (/(地铁|metro|train|火车)/.test(value)) return '🚆'
  if (/(公交|bus|巴士)/.test(value)) return '🚌'
  if (/(渡轮|ferry|船)/.test(value)) return '⛴️'
  if (/(单车|骑行|bike|cycl)/.test(value)) return '🚲'
  return '➡️'
})
</script>

<template>
  <span
    class="inline-flex items-center gap-1.5 rounded-full bg-green-100 px-3 py-1 text-xs font-medium text-green-700 ring-1 ring-green-200/70 ring-inset dark:bg-green-900/40 dark:text-green-400 dark:ring-green-500/20"
  >
    <span aria-hidden="true">{{ emoji }}</span>
    <span>{{ label }}</span>
  </span>
</template>
