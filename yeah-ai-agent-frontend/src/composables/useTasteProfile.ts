import { reactive, watch } from 'vue'
import type { TasteProfile } from '@/types/itinerary'

const STORAGE_KEY = 'yeah_ai_taste_profile'

const DEFAULT_PROFILE: TasteProfile = {
  aesthetic: '',
  photoStyle: '',
  pace: 'relaxed',
  customNotes: '',
}

function load(): TasteProfile {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return { ...DEFAULT_PROFILE }
    const parsed = JSON.parse(raw) as Partial<TasteProfile>
    return {
      aesthetic: typeof parsed.aesthetic === 'string' ? parsed.aesthetic : '',
      photoStyle: typeof parsed.photoStyle === 'string' ? parsed.photoStyle : '',
      pace: parsed.pace === 'moderate' || parsed.pace === 'active' ? parsed.pace : 'relaxed',
      customNotes: typeof parsed.customNotes === 'string' ? parsed.customNotes : '',
    }
  } catch {
    return { ...DEFAULT_PROFILE }
  }
}

const taste = reactive<TasteProfile>(load())

watch(
  taste,
  (value) => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(value))
    } catch {
      /* 配额或隐私模式 */
    }
  },
  { deep: true },
)

export function useTasteProfile() {
  return { taste }
}

export function snapshotTasteProfile(): TasteProfile {
  return {
    aesthetic: taste.aesthetic,
    photoStyle: taste.photoStyle,
    pace: taste.pace,
    customNotes: taste.customNotes,
  }
}
