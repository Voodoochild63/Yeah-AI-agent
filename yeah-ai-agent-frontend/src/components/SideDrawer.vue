<script setup lang="ts">
import { computed, onBeforeUnmount, watch } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import ThemeToggle from '@/components/ThemeToggle.vue'
import type { Lang } from '@/types/itinerary'

const props = defineProps<{
  open: boolean
  /** 没有行程时 PDF 导出应置灰 */
  canExport: boolean
  exporting: boolean
  poiCount: number
  lang?: Lang
}>()

const emit = defineEmits<{
  close: []
  'export-pdf': []
  'manage-poi': []
  'new-chat': []
}>()

const isEn = computed(() => props.lang === 'en')
const exportTitle = computed(() =>
  props.exporting ? (isEn.value ? 'Generating PDF…' : '正在生成…') : isEn.value ? 'Export PDF' : '导出 PDF',
)
const exportHint = computed(() => {
  if (isEn.value) {
    return props.canExport ? 'Turn this itinerary into a booklet PDF' : 'Plan a route before exporting'
  }
  return props.canExport ? '把当前行程排版成画册质感的 PDF' : '先规划一条行程再导出'
})

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') emit('close')
}

// 抽屉打开时锁滚动并接管 Esc
watch(
  () => props.open,
  (open) => {
    document.body.style.overflow = open ? 'hidden' : ''
    if (open) window.addEventListener('keydown', onKeydown)
    else window.removeEventListener('keydown', onKeydown)
  },
)

onBeforeUnmount(() => {
  document.body.style.overflow = ''
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <Teleport to="body">
    <!-- 遮罩 -->
    <Transition
      enter-active-class="transition-opacity duration-300"
      enter-from-class="opacity-0"
      leave-active-class="transition-opacity duration-200"
      leave-to-class="opacity-0"
    >
      <div
        v-if="open"
        class="fixed inset-0 z-40 bg-gray-900/20 backdrop-blur-[2px] dark:bg-black/50"
        @click="emit('close')"
      />
    </Transition>

    <!-- 毛玻璃面板 -->
    <Transition
      enter-active-class="ease-smooth transition-transform duration-300"
      enter-from-class="-translate-x-full"
      leave-active-class="transition-transform duration-200 ease-in"
      leave-to-class="-translate-x-full"
    >
      <aside
        v-if="open"
        role="dialog"
        aria-modal="true"
        aria-label="功能菜单"
        class="fixed inset-y-0 left-0 z-50 flex w-[19rem] max-w-[86vw] flex-col border-r border-gray-200 bg-white/90 backdrop-blur-md dark:border-gray-800 dark:bg-gray-900/80"
      >
        <!-- 头部 -->
        <header class="flex items-center justify-between px-5 py-5">
          <div class="flex items-center gap-2.5">
            <span
              class="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-blue-500 to-indigo-600 text-white shadow-md shadow-blue-500/25"
            >
              <AppIcon name="sparkle" class="h-[1.15rem] w-[1.15rem]" />
            </span>
            <div>
              <p class="text-sm font-semibold text-gray-900 dark:text-gray-50">Yeah AI</p>
              <p class="text-xs text-gray-500 dark:text-gray-400">悉尼本地生活助手</p>
            </div>
          </div>
          <button
            type="button"
            aria-label="关闭"
            class="focus-ring flex h-8 w-8 items-center justify-center rounded-full text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-700 dark:hover:bg-gray-800 dark:hover:text-gray-200"
            @click="emit('close')"
          >
            <AppIcon name="x" class="h-4 w-4" />
          </button>
        </header>

        <div class="mx-5 h-px bg-gray-200/70 dark:bg-gray-700/50" />

        <!-- 功能列表 -->
        <nav class="flex-1 space-y-1 overflow-y-auto p-3">
          <p
            class="px-2.5 pt-2 pb-1 text-[0.68rem] font-semibold tracking-wider text-gray-400 uppercase dark:text-gray-500"
          >
            行程
          </p>

          <!-- PDF 导出 -->
          <button
            type="button"
            :disabled="!canExport || exporting"
            class="focus-ring group flex w-full items-start gap-3 rounded-xl px-2.5 py-2.5 text-left transition-colors disabled:cursor-not-allowed disabled:opacity-45 enabled:hover:bg-gray-100 dark:enabled:hover:bg-gray-800/70"
            @click="emit('export-pdf')"
          >
            <span
              class="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-rose-50 text-rose-600 transition-colors group-enabled:group-hover:bg-rose-100 dark:bg-rose-500/10 dark:text-rose-400 dark:group-enabled:group-hover:bg-rose-500/20"
            >
              <AppIcon v-if="exporting" name="spinner" class="h-4 w-4 animate-spin" />
              <AppIcon v-else name="file-pdf" class="h-4 w-4" />
            </span>
            <span class="min-w-0 flex-1">
              <span class="block text-sm font-medium text-gray-800 dark:text-gray-100">
                {{ exportTitle }}
              </span>
              <span class="block text-xs leading-relaxed text-gray-500 dark:text-gray-400">
                {{ exportHint }}
              </span>
            </span>
          </button>

          <!-- RAG 私域打卡点 -->
          <button
            type="button"
            class="focus-ring group flex w-full items-start gap-3 rounded-xl px-2.5 py-2.5 text-left transition-colors hover:bg-gray-100 dark:hover:bg-gray-800/70"
            @click="emit('manage-poi')"
          >
            <span
              class="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-blue-50 text-blue-600 transition-colors group-hover:bg-blue-100 dark:bg-blue-500/10 dark:text-blue-400 dark:group-hover:bg-blue-500/20"
            >
              <AppIcon name="map-pin" class="h-4 w-4" />
            </span>
            <span class="min-w-0 flex-1">
              <span class="flex items-center gap-2">
                <span class="text-sm font-medium text-gray-800 dark:text-gray-100">{{
                  isEn ? 'Taste & POIs' : '审美与打卡点'
                }}</span>
                <span
                  class="tag-chip bg-gray-100 px-1.5 py-0.5 text-[0.65rem] text-gray-500 dark:bg-gray-800 dark:text-gray-400"
                >
                  {{ poiCount }}
                </span>
              </span>
              <span class="block text-xs leading-relaxed text-gray-500 dark:text-gray-400">
                {{ isEn ? 'Taste profile and private POI library' : '管理审美偏好与私域打卡点库' }}
              </span>
            </span>
          </button>

          <!-- 返回上一级：销毁多轮会话，回到起始规划 -->
          <button
            type="button"
            class="focus-ring group flex w-full items-start gap-3 rounded-xl px-2.5 py-2.5 text-left transition-colors hover:bg-gray-100 dark:hover:bg-gray-800/70"
            @click="emit('new-chat')"
          >
            <span
              class="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-emerald-50 text-emerald-600 transition-colors group-hover:bg-emerald-100 dark:bg-emerald-500/10 dark:text-emerald-400 dark:group-hover:bg-emerald-500/20"
            >
              <AppIcon name="chat" class="h-4 w-4" />
            </span>
            <span class="min-w-0 flex-1">
              <span class="block text-sm font-medium text-gray-800 dark:text-gray-100">{{
                isEn ? 'Back to start' : '返回上一级'
              }}</span>
              <span class="block text-xs leading-relaxed text-gray-500 dark:text-gray-400">
                {{
                  isEn
                    ? 'Clear this itinerary session and start a new plan'
                    : '销毁当前行程会话，回到起始规划'
                }}
              </span>
            </span>
          </button>
        </nav>

        <!-- 底部 -->
        <footer
          class="flex items-center justify-between border-t border-gray-200/70 px-5 py-4 dark:border-gray-700/50"
        >
          <span class="text-xs text-gray-400 dark:text-gray-500">
            {{ lang === 'en' ? 'Theme' : '主题' }}
          </span>
          <ThemeToggle :lang="lang ?? 'zh'" />
        </footer>
      </aside>
    </Transition>
  </Teleport>
</template>
