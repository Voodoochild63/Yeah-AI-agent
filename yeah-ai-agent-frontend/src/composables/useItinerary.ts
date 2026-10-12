import axios from 'axios'
import { computed, ref } from 'vue'
import {
  destroyItinerarySession,
  exportItineraryPdf,
  hydrateItinerarySession,
  planItinerary,
  refineItinerary,
  toFriendlyError,
} from '@/api/itinerary'
import { rememberItinerary } from '@/composables/useItineraryHistory'
import { usePoiLibrary } from '@/composables/usePoiLibrary'
import { snapshotTasteProfile } from '@/composables/useTasteProfile'
import type { ItineraryPlan, Lang, RagContext } from '@/types/itinerary'
import { applySpotCandidate, canShuffleLocally, nextCandidateIndex, refreshLegsAround } from '@/utils/shuffleCandidate'
import { withLiveNarrative } from '@/utils/bindActiveVenues'

const SUBMIT_DEBOUNCE_MS = 400
const PATCH_HIGHLIGHT_MS = 1_800

function createChatId() {
  return `chat_${Date.now()}`
}

const plan = ref<ItineraryPlan | null>(null)
const loading = ref(false)
/** 正在微调（替换或追加）的 schedule 下标；为 null 表示没有卡片在高亮 */
const shufflingIndex = ref<number | null>(null)
const exporting = ref(false)
const error = ref('')
const refineReason = ref('')
const lang = ref<Lang>('zh')
const lastQuery = ref('')
const chatId = ref(createChatId())

let lastSubmitAt = 0
let currentAbortController: AbortController | null = null
let highlightTimer: number | undefined

const hasPlan = computed(() => plan.value !== null)
const hasSession = computed(() => Boolean(plan.value?.sessionId))

function isCanceled(err: unknown): boolean {
  return axios.isCancel(err) || (err instanceof Error && err.name === 'CanceledError')
}

/** 新的规划请求开始前，先掐掉上一次还在飞的请求。 */
function beginPlanRequest(): AbortSignal {
  currentAbortController?.abort()
  currentAbortController = new AbortController()
  return currentAbortController.signal
}

function buildRagContext(): RagContext {
  const { items } = usePoiLibrary()
  return {
    tasteProfile: snapshotTasteProfile(),
    customPois: items.value.map((item) => ({ ...item })),
  }
}

function highlightPatched(index: number | null | undefined) {
  if (highlightTimer !== undefined) {
    window.clearTimeout(highlightTimer)
    highlightTimer = undefined
  }
  if (index == null || index < 0) {
    shufflingIndex.value = null
    return
  }
  shufflingIndex.value = index
  highlightTimer = window.setTimeout(() => {
    shufflingIndex.value = null
    highlightTimer = undefined
  }, PATCH_HIGHLIGHT_MS)
}

async function destroyCurrentSession() {
  const sessionId = plan.value?.sessionId
  if (!sessionId) return
  try {
    await destroyItinerarySession(sessionId)
  } catch (err) {
    console.warn('[itinerary] DELETE /api/itinerary/session failed', err)
  }
}

export function useItinerary() {
  async function submit(message: string) {
    const trimmed = message.trim()
    if (!trimmed) return

    const now = Date.now()
    if (now - lastSubmitAt < SUBMIT_DEBOUNCE_MS) return
    lastSubmitAt = now

    const signal = beginPlanRequest()
    const controller = currentAbortController
    lastQuery.value = trimmed
    error.value = ''

    if (plan.value?.sessionId) {
      await refineExisting(trimmed, signal, controller)
      return
    }

    await planFromScratch(trimmed, signal, controller)
  }

  async function planFromScratch(trimmed: string, signal: AbortSignal, controller: AbortController | null) {
    chatId.value = createChatId()
    loading.value = true
    shufflingIndex.value = null
    refineReason.value = ''
    plan.value = null

    try {
      const data = await planItinerary({
        message: trimmed,
        chatId: chatId.value,
        lang: lang.value,
        ragContext: buildRagContext(),
        signal,
      })
      if (controller !== currentAbortController) return
      if (!data) {
        throw new Error('后端没有返回行程数据。')
      }
      plan.value = data
      rememberItinerary(data, chatId.value)
    } catch (err) {
      if (isCanceled(err)) {
        console.log('用户主动取消了规划请求')
        return
      }
      if (controller !== currentAbortController) return
      console.error('[itinerary] POST /api/ai/itinerary/plan failed', {
        message: trimmed,
        chatId: chatId.value,
        lang: lang.value,
        error: err,
      })
      error.value = toFriendlyError(err)
      plan.value = null
    } finally {
      if (controller === currentAbortController) {
        loading.value = false
        currentAbortController = null
      }
    }
  }

  async function refineExisting(trimmed: string, signal: AbortSignal, controller: AbortController | null) {
    const sessionId = plan.value?.sessionId
    if (!sessionId) return

    loading.value = true
    refineReason.value = ''

    try {
      const data = await refineItinerary({
        sessionId,
        message: trimmed,
        lang: lang.value,
        signal,
      })
      if (controller !== currentAbortController) return
      if (!data?.plan) {
        throw new Error('后端没有返回修改后的行程。')
      }
      plan.value = data.plan
      refineReason.value = data.reason?.trim() || ''
      highlightPatched(data.targetIndex)
      rememberItinerary(data.plan, chatId.value)
    } catch (err) {
      if (isCanceled(err)) {
        console.log('用户主动取消了局部修改请求')
        return
      }
      if (controller !== currentAbortController) return
      console.error('[itinerary] POST /api/itinerary/session/refine failed', {
        message: trimmed,
        sessionId,
        lang: lang.value,
        error: err,
      })
      error.value = toFriendlyError(err)
    } finally {
      if (controller === currentAbortController) {
        loading.value = false
        currentAbortController = null
      }
    }
  }

  /**
   * 预置 candidates 时在内存里轮播，不打网。没有备选时静默返回。
   */
  function shuffleNode(index: number, _hint = '') {
    const current = plan.value
    const node = current?.schedule?.[index]
    if (!current || !node || !canShuffleLocally(node)) return

    const pool = node.candidates ?? node.sunsetCandidates
    const candidate = pool?.[nextCandidateIndex(node)]
    if (!candidate) return

    applySpotCandidate(node, candidate, lang.value)
    refreshLegsAround(current.schedule, index, lang.value)
    plan.value = { ...current, schedule: current.schedule.slice() }
  }

  function stop() {
    currentAbortController?.abort()
  }

  async function restorePlan(next: ItineraryPlan) {
    currentAbortController?.abort()
    currentAbortController = null
    loading.value = false
    shufflingIndex.value = null
    error.value = ''
    refineReason.value = ''
    plan.value = next
    try {
      const hydrated = await hydrateItinerarySession(next)
      if (hydrated) {
        plan.value = hydrated
      }
    } catch (err) {
      console.warn('[itinerary] POST /api/itinerary/session hydrate failed', err)
    }
  }

  async function exportPdf() {
    if (!plan.value || exporting.value) return

    exporting.value = true
    error.value = ''
    try {
      await exportItineraryPdf(withLiveNarrative(plan.value), lang.value)
    } catch (e) {
      console.error('[itinerary] POST /api/ai/itinerary/export-pdf failed', e)
      error.value = toFriendlyError(e)
    } finally {
      exporting.value = false
    }
  }

  function reset() {
    currentAbortController?.abort()
    currentAbortController = null
    void destroyCurrentSession()
    plan.value = null
    error.value = ''
    refineReason.value = ''
    lastQuery.value = ''
    loading.value = false
    shufflingIndex.value = null
    chatId.value = createChatId()
  }

  /**
   * 切换语言后不再重拉落日接口，避免把规划后绑定的就近机位覆盖成市中心默认点。
   */
  async function setLang(next: Lang) {
    if (lang.value === next) return
    lang.value = next
  }

  return {
    plan,
    loading,
    shufflingIndex,
    exporting,
    error,
    refineReason,
    lang,
    lastQuery,
    hasPlan,
    hasSession,
    submit,
    stop,
    restorePlan,
    shuffleNode,
    exportPdf,
    reset,
    setLang,
  }
}
