import axios from 'axios'
import { computed, ref } from 'vue'
import { checkSunset, exportItineraryPdf, planItinerary, toFriendlyError } from '@/api/itinerary'
import { rememberItinerary } from '@/composables/useItineraryHistory'
import { usePoiLibrary } from '@/composables/usePoiLibrary'
import { snapshotTasteProfile } from '@/composables/useTasteProfile'
import type { ItineraryNode, ItineraryPlan, Lang, RagContext } from '@/types/itinerary'

const SUBMIT_DEBOUNCE_MS = 400

function createChatId() {
  return `chat_${Date.now()}`
}

const plan = ref<ItineraryPlan | null>(null)
const loading = ref(false)
/** 正在单点替换的 schedule 下标；为 null 表示没有卡片在微调 */
const shufflingIndex = ref<number | null>(null)
const exporting = ref(false)
const error = ref('')
const lang = ref<Lang>('zh')
const lastQuery = ref('')
const chatId = ref(createChatId())

let lastSubmitAt = 0
let currentAbortController: AbortController | null = null
let localizeToken = 0

const hasPlan = computed(() => plan.value !== null)

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

export function useItinerary() {
  async function submit(message: string) {
    const trimmed = message.trim()
    if (!trimmed) return

    const now = Date.now()
    if (now - lastSubmitAt < SUBMIT_DEBOUNCE_MS) return
    lastSubmitAt = now

    const signal = beginPlanRequest()
    const controller = currentAbortController

    // 每次全新规划都换独立会话，避免 ChatMemory 把上一次行程锁死
    chatId.value = createChatId()
    loading.value = true
    shufflingIndex.value = null
    error.value = ''
    lastQuery.value = trimmed
    // 清掉上一份结果，防止请求失败时页面还挂着旧行程
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

  /**
   * 复用当前 chatId，只把返回结果里被替换的那一站写回本地行程。
   * 其余节点保持页面上的原样，避免模型重写整条路线。
   */
  async function shuffleNode(index: number, hint = '') {
    const current = plan.value
    const node = current?.schedule?.[index]
    if (!current || !node || shufflingIndex.value !== null) return

    const signal = beginPlanRequest()
    const controller = currentAbortController

    const base =
      lang.value === 'en'
        ? `Keep every other stop exactly unchanged. Replace only stop ${index + 1} [${node.poiName}] with a different venue in the same area and of the same type.`
        : `请保持当前其他行程完全不变，仅将第 ${index + 1} 站【${node.poiName}】替换为一个同区域、同类型但不同的备选地点。`
    const message = hint.trim() ? `${base} ${hint.trim()}` : base

    shufflingIndex.value = index
    loading.value = false
    error.value = ''

    try {
      const data = await planItinerary({
        message,
        chatId: chatId.value,
        lang: lang.value,
        ragContext: buildRagContext(),
        signal,
      })
      if (controller !== currentAbortController) return
      const replacement = pickReplacement(data?.schedule, index, node.poiName, current.schedule)
      if (!replacement) {
        throw new Error(
          lang.value === 'en'
            ? 'The model did not return a different stop for this card.'
            : '模型没有返回一个不同的替换站点。',
        )
      }
      const schedule = current.schedule.slice()
      schedule[index] = replacement
      plan.value = { ...current, schedule }
    } catch (err) {
      if (isCanceled(err)) {
        console.log('用户主动取消了规划请求')
        return
      }
      if (controller !== currentAbortController) return
      console.error('[itinerary] shuffle node failed', {
        index,
        chatId: chatId.value,
        lang: lang.value,
        error: err,
      })
      error.value = toFriendlyError(err)
    } finally {
      if (controller === currentAbortController) {
        shufflingIndex.value = null
        currentAbortController = null
      }
    }
  }

  function stop() {
    currentAbortController?.abort()
  }

  function restorePlan(next: ItineraryPlan) {
    currentAbortController?.abort()
    currentAbortController = null
    loading.value = false
    shufflingIndex.value = null
    error.value = ''
    plan.value = next
  }

  async function exportPdf() {
    if (!plan.value || exporting.value) return

    exporting.value = true
    error.value = ''
    try {
      await exportItineraryPdf(plan.value, lang.value)
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
    plan.value = null
    error.value = ''
    lastQuery.value = ''
    loading.value = false
    shufflingIndex.value = null
    chatId.value = createChatId()
  }

  function pickReplacement(
    returned: ItineraryNode[] | undefined,
    index: number,
    previousName: string,
    kept: ItineraryNode[],
  ): ItineraryNode | null {
    if (!returned?.length) return null
    const previous = previousName.trim().toLowerCase()
    const occupied = new Set(
      kept
        .filter((_, i) => i !== index)
        .map((item) => item.poiName?.trim().toLowerCase())
        .filter(Boolean),
    )
    const sameIndex = returned[index]
    if (sameIndex?.poiName && sameIndex.poiName.trim().toLowerCase() !== previous) {
      return sameIndex
    }
    return (
      returned.find((item) => {
        const name = item.poiName?.trim().toLowerCase()
        return Boolean(name) && name !== previous && !occupied.has(name)
      }) ?? null
    )
  }

  /**
   * 切换语言后，停车贴士和落日说明按新的 lang 重取。
   * 标题、标签和备选建议来自上次规划，要等下一次 plan 请求才会换成目标语言。
   */
  async function setLang(next: Lang) {
    if (lang.value === next) return
    lang.value = next
    const current = plan.value
    if (!current) return

    const token = ++localizeToken
    const sunset = await checkSunset(next).catch(() => null)
    if (token !== localizeToken || !plan.value) return
    plan.value = {
      ...plan.value,
      sunsetEvaluation: sunset ?? plan.value.sunsetEvaluation,
    }
  }

  return {
    plan,
    loading,
    shufflingIndex,
    exporting,
    error,
    lang,
    lastQuery,
    hasPlan,
    submit,
    stop,
    restorePlan,
    shuffleNode,
    exportPdf,
    reset,
    setLang,
  }
}
