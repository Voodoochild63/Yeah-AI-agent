import { ref } from 'vue'
import type { ItineraryPlan, Lang } from '@/types/itinerary'

const STORAGE_KEY = 'yeah_ai_itinerary_history'
const MAX_ENTRIES = 6

export interface ItineraryHistoryEntry {
  id: string | number
  title: string
  timestamp: number
  planData: ItineraryPlan
}

const entries = ref<ItineraryHistoryEntry[]>(readEntries())

function readEntries(): ItineraryHistoryEntry[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw) as ItineraryHistoryEntry[]
    if (!Array.isArray(parsed)) return []
    return parsed
      .filter((item) => item && item.planData && typeof item.timestamp === 'number')
      .slice(0, MAX_ENTRIES)
  } catch {
    return []
  }
}

function persist(next: ItineraryHistoryEntry[]) {
  entries.value = next
  localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
}

export function rememberItinerary(plan: ItineraryPlan, chatId?: string) {
  const id = chatId || Date.now()
  const entry: ItineraryHistoryEntry = {
    id,
    title: plan.title?.trim() || '未命名行程',
    timestamp: Date.now(),
    planData: JSON.parse(JSON.stringify(plan)) as ItineraryPlan,
  }
  const next = [entry, ...entries.value.filter((item) => item.id !== id)].slice(0, MAX_ENTRIES)
  persist(next)
}

export function useItineraryHistory() {
  function remove(id: string | number) {
    persist(entries.value.filter((item) => item.id !== id))
  }

  function clear() {
    persist([])
  }

  return { entries, remove, clear }
}

export function formatRelativeTime(timestamp: number, lang: Lang, now = Date.now()): string {
  const delta = Math.max(0, now - timestamp)
  const minute = 60_000
  const hour = 60 * minute
  const day = 24 * hour
  const en = lang === 'en'

  if (delta < minute) return en ? 'just now' : '刚刚'
  if (delta < hour) {
    const n = Math.floor(delta / minute)
    return en ? `${n} min ago` : `${n}分钟前`
  }
  if (delta < day) {
    const n = Math.floor(delta / hour)
    return en ? `${n} hr ago` : `${n}小时前`
  }
  if (delta < 2 * day) return en ? 'yesterday' : '昨天'
  const n = Math.floor(delta / day)
  return en ? `${n} days ago` : `${n}天前`
}
