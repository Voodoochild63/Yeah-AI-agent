import axios from 'axios'
import type { ItineraryPlan, ItineraryRefineResponse, Lang, RagContext, SunsetEvaluation } from '@/types/itinerary'

/** 生产构建读取 .env.production 的 VITE_API_BASE_URL；本地开发回退到 Vite 代理 /api */
const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() || '/api'

const http = axios.create({
  baseURL: apiBaseUrl,
  // 大模型编排一次行程通常要 20~60 秒，默认超时会直接把请求掐断
  timeout: 120_000,
})

/** POST /api/ai/itinerary/plan —— query + lang + ragContext */
export async function planItinerary(params: {
  message: string
  chatId: string
  lang: Lang
  ragContext?: RagContext
  signal?: AbortSignal
}): Promise<ItineraryPlan> {
  const { data } = await http.post<ItineraryPlan>(
    '/ai/itinerary/plan',
    {
      message: params.message,
      chatId: params.chatId,
      lang: params.lang,
      ragContext: params.ragContext,
    },
    {
      signal: params.signal,
      timeout: 120_000,
    },
  )
  return data
}

/** POST /api/itinerary/session/{sessionId}/refine —— REPLACE 一张卡片或 APPEND 新站 */
export async function refineItinerary(params: {
  sessionId: string
  message: string
  lang: Lang
  signal?: AbortSignal
}): Promise<ItineraryRefineResponse> {
  const { data } = await http.post<ItineraryRefineResponse>(
    `/itinerary/session/${encodeURIComponent(params.sessionId)}/refine`,
    { message: params.message, lang: params.lang },
    { signal: params.signal, timeout: 120_000 },
  )
  return data
}

/** POST /api/itinerary/session —— 历史回放时重新写入 sessionCache */
export async function hydrateItinerarySession(plan: ItineraryPlan): Promise<ItineraryPlan> {
  const { data } = await http.post<ItineraryPlan>('/itinerary/session', plan)
  return data
}

/** DELETE /api/itinerary/session/{sessionId} —— 返回上一级时销毁会话 */
export async function destroyItinerarySession(sessionId: string): Promise<void> {
  await http.delete(`/itinerary/session/${encodeURIComponent(sessionId)}`)
}

/** GET /api/ai/weather/sunset-check */
export async function checkSunset(lang: Lang): Promise<SunsetEvaluation> {
  const { data } = await http.get<SunsetEvaluation>('/ai/weather/sunset-check', { params: { lang } })
  return data
}

/**
 * POST /api/ai/itinerary/export-pdf
 * 把页面上的行程 JSON 原样回传，后端不会重新调用大模型。
 */
export async function exportItineraryPdf(plan: ItineraryPlan, lang: Lang): Promise<void> {
  const response = await http.post<Blob>('/ai/itinerary/export-pdf', plan, {
    params: { lang },
    responseType: 'blob',
  })

  const disposition = response.headers['content-disposition'] as string | undefined
  const filename =
    parseFilename(disposition) ?? (lang === 'en' ? 'sydney-itinerary.pdf' : '悉尼行程单.pdf')

  const url = URL.createObjectURL(response.data)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 1_000)
}

function parseFilename(disposition?: string): string | null {
  if (!disposition) return null

  const utf8 = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  if (utf8?.[1]) {
    try {
      return decodeURIComponent(utf8[1].trim())
    } catch {
      /* 编码异常时退回 ASCII 名 */
    }
  }

  const ascii = /filename="?([^";]+)"?/i.exec(disposition)
  return ascii?.[1]?.trim() ?? null
}

/** 把 axios 错误转成能直接展示给用户的文案 */
export function toFriendlyError(error: unknown): string {
  if (axios.isAxiosError(error)) {
    if (error.code === 'ECONNABORTED') {
      return '请求超时了。大模型编排一次行程通常需要 20~60 秒，请稍后重试。'
    }
    if (!error.response) {
      return apiBaseUrl.startsWith('http')
        ? '连不上后端服务。请稍后重试。'
        : '连不上后端服务。请确认 yeah-ai-agent 已在 http://localhost:8123 启动。'
    }
    const data = error.response.data
    const serverMessage =
      data && typeof data === 'object' && 'message' in data && typeof data.message === 'string'
        ? data.message.trim()
        : ''
    if (serverMessage) return serverMessage
    return `后端返回 ${error.response.status}。${error.response.statusText || '请查看服务端日志。'}`
  }
  return error instanceof Error ? error.message : '发生了未知错误。'
}
