import { ref } from 'vue'
import type { PoiItem } from '@/types/itinerary'

const STORAGE_KEY = 'yeah-ai-poi-library'

/**
 * 私域打卡点本地库。
 *
 * 后端 PoiRagService 目前没有 CRUD 接口，所以这里用 localStorage 承接用户自己录入的数据。
 * 不做任何预置样例，避免页面出现写死的默认行程/地点。
 */
const items = ref<PoiItem[]>(load())

function load(): PoiItem[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    return Array.isArray(parsed) ? (parsed as PoiItem[]) : []
  } catch {
    return []
  }
}

function persist() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(items.value))
  } catch {
    /* 超出配额或隐私模式，忽略 */
  }
}

export function usePoiLibrary() {
  function add(item: Omit<PoiItem, 'id'>) {
    items.value = [{ ...item, id: `poi_${Date.now().toString(36)}` }, ...items.value]
    persist()
  }

  function update(id: string, patch: Partial<Omit<PoiItem, 'id'>>) {
    items.value = items.value.map((item) => (item.id === id ? { ...item, ...patch } : item))
    persist()
  }

  function remove(id: string) {
    items.value = items.value.filter((item) => item.id !== id)
    persist()
  }

  return { items, add, update, remove }
}

/** 把逗号 / 顿号 / 空格分隔的输入切成标签数组 */
export function parseTagInput(raw: string): string[] {
  return raw
    .split(/[,，、\s]+/)
    .map((token) => token.trim())
    .filter(Boolean)
}
