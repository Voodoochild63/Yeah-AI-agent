<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import { formatRelativeTime, useItineraryHistory } from '@/composables/useItineraryHistory'
import type { ItineraryHistoryEntry } from '@/composables/useItineraryHistory'
import type { ItineraryPlan, Lang } from '@/types/itinerary'
import { pickLocalized } from '@/utils/localeText'

const props = withDefaults(
  defineProps<{
    lang?: Lang
  }>(),
  { lang: 'zh' },
)

const emit = defineEmits<{
  restore: [plan: ItineraryPlan]
}>()

const open = ref(false)
const { entries, remove, clear } = useItineraryHistory()
const isEn = computed(() => props.lang === 'en')
const count = computed(() => entries.value.length)

const title = computed(() => (isEn.value ? 'History' : '历史行程'))
const emptyLabel = computed(() => (isEn.value ? 'No saved itineraries yet.' : '还没有保存的行程。'))
const clearLabel = computed(() => (isEn.value ? 'Clear all' : '清空历史'))
const buttonLabel = computed(() => (isEn.value ? 'History' : '历史行程'))

function toggle() {
  open.value = !open.value
}

function close() {
  open.value = false
}

function openEntry(entry: ItineraryHistoryEntry) {
  emit('restore', entry.planData)
  close()
}

function entryTitle(entry: ItineraryHistoryEntry) {
  return (
    pickLocalized(props.lang, entry.planData?.titleZh, entry.planData?.titleEn, entry.title) ||
    (isEn.value ? 'Untitled itinerary' : '未命名行程')
  )
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') close()
}

window.addEventListener('keydown', onKeydown)
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div class="relative">
    <button
      type="button"
      class="focus-ring relative flex h-9 w-9 items-center justify-center rounded-full text-gray-500 transition-all hover:-translate-y-0.5 hover:bg-black/5 hover:text-gray-900 dark:text-gray-400 dark:hover:bg-white/10 dark:hover:text-gray-100"
      :aria-label="buttonLabel"
      :title="buttonLabel"
      :aria-expanded="open"
      @click="toggle"
    >
      <AppIcon name="clock" class="h-[1.15rem] w-[1.15rem]" />
      <span
        v-if="count"
        class="absolute -top-0.5 -right-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-blue-600 px-1 text-[0.6rem] font-semibold text-white shadow-sm dark:bg-blue-400 dark:text-gray-950"
      >
        {{ count }}
      </span>
    </button>

    <Teleport to="body">
      <Transition
        enter-active-class="transition duration-200"
        enter-from-class="opacity-0"
        leave-active-class="transition duration-200"
        leave-to-class="opacity-0"
      >
        <button
          v-if="open"
          type="button"
          class="fixed inset-0 z-40 bg-gray-950/25 backdrop-blur-[2px] dark:bg-black/45"
          :aria-label="isEn ? 'Close history' : '关闭历史'"
          @click="close"
        />
      </Transition>

      <Transition
        enter-active-class="transition duration-300 ease-out"
        enter-from-class="translate-x-6 opacity-0"
        leave-active-class="transition duration-200 ease-in"
        leave-to-class="translate-x-6 opacity-0"
      >
        <aside
          v-if="open"
          class="fixed top-3 right-3 bottom-3 z-50 flex w-[min(22rem,calc(100vw-1.5rem))] flex-col overflow-hidden rounded-3xl border border-white/70 bg-white/85 shadow-2xl shadow-gray-900/10 backdrop-blur-xl dark:border-gray-700/70 dark:bg-gray-950/90 dark:shadow-black/40"
          role="dialog"
          :aria-label="title"
        >
          <header class="flex items-center justify-between gap-3 px-4 pt-4 pb-3">
            <h2 class="text-sm font-semibold text-gray-900 dark:text-gray-50">{{ title }}</h2>
            <div class="flex items-center gap-1">
              <button
                v-if="count"
                type="button"
                class="focus-ring rounded-full px-2.5 py-1 text-xs text-gray-500 transition-colors hover:bg-black/5 hover:text-gray-800 dark:text-gray-400 dark:hover:bg-white/10 dark:hover:text-gray-100"
                @click="clear"
              >
                {{ clearLabel }}
              </button>
              <button
                type="button"
                class="focus-ring flex h-8 w-8 items-center justify-center rounded-full text-gray-500 hover:bg-black/5 dark:text-gray-400 dark:hover:bg-white/10"
                :aria-label="isEn ? 'Close' : '关闭'"
                @click="close"
              >
                <AppIcon name="x" class="h-4 w-4" />
              </button>
            </div>
          </header>

          <p v-if="!count" class="px-4 pb-6 text-sm leading-relaxed text-gray-500 dark:text-gray-400">
            {{ emptyLabel }}
          </p>

          <ul v-else class="min-h-0 flex-1 space-y-2 overflow-y-auto px-3 pb-4">
            <li v-for="entry in entries" :key="entry.id">
              <div
                class="group flex items-start gap-2 rounded-2xl border border-gray-100 bg-white/70 p-3 transition-colors hover:border-gray-200 dark:border-gray-800 dark:bg-gray-900/50 dark:hover:border-gray-700"
              >
                <button
                  type="button"
                  class="focus-ring min-w-0 flex-1 rounded-xl text-left"
                  @click="openEntry(entry)"
                >
                  <span class="block truncate text-sm font-medium text-gray-900 dark:text-gray-50">
                    {{ entryTitle(entry) }}
                  </span>
                  <span class="mt-1 block text-xs text-gray-400 dark:text-gray-500">
                    {{ formatRelativeTime(entry.timestamp, lang) }}
                  </span>
                </button>
                <button
                  type="button"
                  class="focus-ring mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-gray-400 opacity-70 transition-all hover:bg-red-50 hover:text-red-600 group-hover:opacity-100 dark:hover:bg-red-500/10 dark:hover:text-red-300"
                  :aria-label="isEn ? 'Delete' : '删除'"
                  :title="isEn ? 'Delete' : '删除'"
                  @click="remove(entry.id)"
                >
                  <AppIcon name="trash" class="h-3.5 w-3.5" />
                </button>
              </div>
            </li>
          </ul>
        </aside>
      </Transition>
    </Teleport>
  </div>
</template>
