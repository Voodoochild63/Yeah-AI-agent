<script setup lang="ts">
import { computed, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import GreetingHeader from '@/components/GreetingHeader.vue'
import HistoryDrawer from '@/components/HistoryDrawer.vue'
import LoadingStatus from '@/components/LoadingStatus.vue'
import ItineraryTimeline from '@/components/ItineraryTimeline.vue'
import PoiManagerModal from '@/components/PoiManagerModal.vue'
import PromptBar from '@/components/PromptBar.vue'
import SideDrawer from '@/components/SideDrawer.vue'
import ThemeToggle from '@/components/ThemeToggle.vue'
import { useItinerary } from '@/composables/useItinerary'
import { usePoiLibrary } from '@/composables/usePoiLibrary'
import { useTheme } from '@/composables/useTheme'
import type { ItineraryPlan, Lang } from '@/types/itinerary'

// 初始化主题监听（系统偏好 + 时间判断）
useTheme()

const {
  plan,
  loading,
  shufflingIndex,
  exporting,
  error,
  refineReason,
  lang,
  hasPlan,
  submit,
  stop,
  restorePlan,
  shuffleNode,
  exportPdf,
  reset,
  setLang,
} = useItinerary()
const { items: poiItems } = usePoiLibrary()

const drawerOpen = ref(false)
const poiModalOpen = ref(false)

/** 状态 A 时留白把输入框顶到视觉中心，状态 B 时收拢让它上移 */
const spacerHeight = computed(() => (hasPlan.value ? '0px' : 'clamp(3rem, 20vh, 11rem)'))
const showGreeting = computed(() => !hasPlan.value && !loading.value)

const QUICK_PROMPTS: Record<Lang, string[]> = {
  zh: [
    '帮我安排 Bondi 到 Bronte 的海边半日游',
    '我开车去 Chippendale 看展，顺便找个咖啡',
    '傍晚出发，想拍点城市光影和落日',
  ],
  en: [
    'Coastal half-day walk from Bondi to Bronte',
    'Driving to Chippendale for galleries and a specialty brew',
    'Late afternoon departure for golden hour and street photo spots',
  ],
}

const quickPrompts = computed(() => QUICK_PROMPTS[lang.value])
const followUpHint = computed(() =>
  lang.value === 'en'
    ? 'Say what to swap — only that card is patched. Back to start clears the session.'
    : '直接说要改哪一站，只会替换对应卡片。点「返回上一级」才销毁会话。',
)

async function onSubmit(message: string) {
  await submit(message)
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function onExportPdf() {
  drawerOpen.value = false
  void exportPdf()
}

function onManagePoi() {
  drawerOpen.value = false
  poiModalOpen.value = true
}

function onNewChat() {
  drawerOpen.value = false
  reset()
}

function onLangChange(next: Lang) {
  setLang(next)
}

function onRestore(next: ItineraryPlan) {
  restorePlan(next)
  window.scrollTo({ top: 0, behavior: 'smooth' })
}
</script>

<template>
  <div
    class="relative min-h-dvh bg-gradient-to-b from-slate-50 to-white transition-colors duration-500 dark:from-slate-900 dark:to-gray-950"
  >
    <!-- 环境柔光：给纯色渐变加一点纵深，进入时间轴后淡出让位给内容 -->
    <div
      aria-hidden="true"
      class="animate-breathe pointer-events-none fixed top-[26vh] left-1/2 z-0 h-64 w-[42rem] max-w-[92vw] -translate-x-1/2 -translate-y-1/2 rounded-full bg-gradient-to-r from-sky-200/50 via-indigo-200/40 to-rose-200/40 blur-3xl transition-opacity duration-700 dark:from-sky-500/10 dark:via-indigo-500/10 dark:to-fuchsia-500/10"
      :class="hasPlan ? 'opacity-0' : 'opacity-100'"
    />

    <!-- 顶栏。带一层向下淡出的同色遮罩，滚动时下方内容不会生硬地穿过 logo -->
    <header
      class="fixed inset-x-0 top-0 z-30 flex items-center justify-between bg-gradient-to-b from-slate-50 via-slate-50/85 to-transparent px-4 pt-3.5 pb-6 sm:px-6 dark:from-slate-900 dark:via-slate-900/85"
    >
      <button
        type="button"
        class="focus-ring flex items-center gap-2 rounded-full px-1 py-1 text-sm font-semibold text-gray-900 transition-opacity hover:opacity-70 dark:text-gray-50"
        title="返回上一级"
        @click="onNewChat"
      >
        <span
          class="flex h-7 w-7 items-center justify-center rounded-lg bg-gradient-to-br from-blue-500 to-indigo-600 text-white shadow-sm shadow-blue-500/25"
        >
          <AppIcon name="sparkle" class="h-3.5 w-3.5" />
        </span>
        <span class="hidden sm:inline">Yeah AI</span>
      </button>

      <div class="flex items-center gap-1">
        <HistoryDrawer :lang="lang" @restore="onRestore" />
        <ThemeToggle :lang="lang" />
      </div>
    </header>

    <main class="relative z-10 mx-auto flex min-h-dvh w-full max-w-3xl flex-col px-4 pt-16 pb-20 sm:px-6">
      <!-- 会随状态平滑收拢的留白，制造「输入框上移」的效果 -->
      <div
        aria-hidden="true"
        class="ease-smooth shrink-0 transition-[height] duration-700"
        :style="{ height: spacerHeight }"
      />

      <!-- 状态 A：问候语。用 max-height 折叠而不是 v-if，
           这样切到状态 B 时是平滑收起，不会整块内容瞬间跳位 -->
      <div
        class="ease-smooth overflow-hidden transition-all duration-700"
        :class="showGreeting ? 'mb-9 max-h-60 opacity-100' : 'mb-0 max-h-0 opacity-0'"
      >
        <GreetingHeader :lang="lang" />
      </div>

      <!-- 输入框：有行程后吸顶，方便随时追加修改指令。
           吸顶态要自带半透明底 + 模糊，否则时间轴会从胶囊周围穿过去。
           -mt-16/pt-16 是一对：文档流位置不变，但背景向上多铺 4rem 盖住顶栏那段，
           不然滚动时 POI 名称会从顶栏的半透明渐变里透出来 -->
      <div
        class="z-20 transition-all duration-500"
        :class="
          hasPlan
            ? 'sticky top-0 -mx-4 -mt-16 bg-slate-50/85 px-4 pt-16 pb-2 backdrop-blur-lg sm:-mx-6 sm:px-6 dark:bg-slate-900/85'
            : ''
        "
      >
        <PromptBar
          :loading="loading"
          :lang="lang"
          :has-plan="hasPlan"
          @submit="onSubmit"
          @stop="stop"
          @open-drawer="drawerOpen = true"
          @update:lang="onLangChange"
        />

        <p v-if="hasPlan" class="mt-2 px-4 text-xs text-gray-400 dark:text-gray-500">
          {{ followUpHint }}
        </p>
      </div>

      <!-- 状态 A：快捷提示 -->
      <Transition
        enter-active-class="transition-all delay-150 duration-500 ease-out"
        enter-from-class="opacity-0 translate-y-2"
        leave-active-class="transition-all duration-200"
        leave-to-class="opacity-0"
      >
        <div v-if="showGreeting" :key="lang" class="mt-5 flex flex-wrap justify-center gap-2">
          <button
            v-for="prompt in quickPrompts"
            :key="prompt"
            type="button"
            class="focus-ring rounded-full border border-gray-200/80 bg-white/60 px-3.5 py-1.5 text-xs text-gray-600 backdrop-blur transition-all hover:-translate-y-0.5 hover:border-gray-300 hover:bg-white hover:text-gray-900 hover:shadow-sm dark:border-gray-700/50 dark:bg-gray-800/40 dark:text-gray-400 dark:hover:border-gray-600 dark:hover:bg-gray-800 dark:hover:text-gray-100"
            @click="onSubmit(prompt)"
          >
            {{ prompt }}
          </button>
        </div>
      </Transition>

      <!-- 局部修改说明 -->
      <Transition
        enter-active-class="transition-all duration-300"
        enter-from-class="opacity-0 -translate-y-2"
        leave-active-class="transition-all duration-200"
        leave-to-class="opacity-0"
      >
        <div
          v-if="refineReason && plan && !loading"
          role="status"
          class="mt-6 flex items-start gap-2.5 rounded-xl border border-emerald-200 bg-emerald-50 p-3.5 text-sm text-emerald-800 dark:border-emerald-500/30 dark:bg-emerald-950/30 dark:text-emerald-300"
        >
          <AppIcon name="sparkle" class="mt-0.5 h-4 w-4 shrink-0" />
          <span class="leading-relaxed">{{ refineReason }}</span>
        </div>
      </Transition>

      <!-- 错误提示 -->
      <Transition
        enter-active-class="transition-all duration-300"
        enter-from-class="opacity-0 -translate-y-2"
        leave-active-class="transition-all duration-200"
        leave-to-class="opacity-0"
      >
        <div
          v-if="error"
          role="alert"
          class="mt-6 flex items-start gap-2.5 rounded-xl border border-amber-200 bg-amber-50 p-3.5 text-sm text-amber-800 dark:border-amber-500/30 dark:bg-amber-950/30 dark:text-amber-300"
        >
          <AppIcon name="warning" class="mt-0.5 h-4 w-4 shrink-0" />
          <span class="leading-relaxed">{{ error }}</span>
        </div>
      </Transition>

      <!-- 首次规划骨架；局部修改时保留时间轴 -->
      <div v-if="loading && !plan" class="mt-9">
        <LoadingStatus :active="loading" :lang="lang" />

        <div class="relative">
          <div
            aria-hidden="true"
            class="absolute top-2 bottom-2 left-0 border-l-2 border-gray-200 dark:border-gray-700"
          />
          <div v-for="index in 3" :key="index" class="relative">
            <span
              aria-hidden="true"
              class="absolute top-5 -left-1 h-2.5 w-2.5 rounded-full bg-gray-300 ring-4 ring-white dark:bg-gray-600 dark:ring-gray-900"
            />
            <div
              class="mb-6 ml-6 animate-pulse rounded-xl border border-gray-100 bg-white/70 p-4 dark:border-gray-700/50 dark:bg-gray-800/40"
              :style="{ animationDelay: `${(index - 1) * 0.15}s` }"
            >
              <div class="flex gap-2.5">
                <div class="h-5 w-12 rounded-md bg-gray-200 dark:bg-gray-700" />
                <div class="h-5 w-40 rounded-md bg-gray-200 dark:bg-gray-700" />
              </div>
              <div class="mt-3 flex gap-2">
                <div class="h-5 w-24 rounded-full bg-gray-100 dark:bg-gray-700/60" />
                <div class="h-5 w-16 rounded-full bg-gray-100 dark:bg-gray-700/60" />
              </div>
              <div class="mt-3.5 h-16 rounded-lg bg-gray-100 dark:bg-gray-900/40" />
            </div>
          </div>
        </div>
      </div>

      <!-- 状态 B：时间轴（局部修改时仍显示，不整页骨架） -->
      <div v-else-if="plan" class="mt-9" :class="loading ? 'pointer-events-none opacity-70' : ''">
        <p
          v-if="loading"
          class="mb-4 text-xs text-gray-400 dark:text-gray-500"
        >
          {{ lang === 'en' ? 'Patching one stop…' : '正在替换对应行程卡片…' }}
        </p>
        <ItineraryTimeline
          :plan="plan"
          :lang="lang"
          :shuffling-index="shufflingIndex"
          @shuffle="shuffleNode"
        />

        <!-- 底部导出按钮，避免每次都要开抽屉 -->
        <div class="mt-8 flex justify-center">
          <button
            type="button"
            :disabled="exporting"
            class="focus-ring inline-flex items-center gap-2 rounded-full border border-gray-200/80 bg-white/70 px-5 py-2.5 text-sm font-medium text-gray-700 backdrop-blur transition-all hover:-translate-y-0.5 hover:border-gray-300 hover:bg-white hover:shadow-md disabled:cursor-not-allowed disabled:opacity-50 dark:border-gray-700/60 dark:bg-gray-800/60 dark:text-gray-200 dark:hover:border-gray-600 dark:hover:bg-gray-800"
            @click="exportPdf"
          >
            <AppIcon v-if="exporting" name="spinner" class="h-4 w-4 animate-spin" />
            <AppIcon v-else name="file-pdf" class="h-4 w-4 text-rose-600 dark:text-rose-400" />
            {{
              exporting
                ? lang === 'en'
                  ? 'Generating PDF…'
                  : '正在生成 PDF…'
                : lang === 'en'
                  ? 'Export PDF'
                  : '导出为 PDF'
            }}
          </button>
        </div>
      </div>
    </main>

    <SideDrawer
      :open="drawerOpen"
      :can-export="hasPlan"
      :exporting="exporting"
      :poi-count="poiItems.length"
      :lang="lang"
      @close="drawerOpen = false"
      @export-pdf="onExportPdf"
      @manage-poi="onManagePoi"
      @new-chat="onNewChat"
    />

    <PoiManagerModal :open="poiModalOpen" :lang="lang" @close="poiModalOpen = false" />
  </div>
</template>
