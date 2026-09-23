import { computed, onScopeDispose, ref } from 'vue'

export type ThemeMode = 'auto' | 'light' | 'dark'

const STORAGE_KEY = 'yeah-ai-theme'
const DARK_HOUR_START = 18
const DARK_HOUR_END = 6

/** 模块级单例：任何组件 import 拿到的都是同一份状态 */
const mode = ref<ThemeMode>(readStoredMode())
const systemPrefersDark = ref(false)
const clockPrefersDark = ref(isNightNow())

let initialized = false

function readStoredMode(): ThemeMode {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    return stored === 'light' || stored === 'dark' ? stored : 'auto'
  } catch {
    return 'auto'
  }
}

function isNightNow(): boolean {
  const hour = new Date().getHours()
  return hour >= DARK_HOUR_START || hour < DARK_HOUR_END
}

/** auto 模式下，系统偏好和时间判断任意一个说「暗」就走暗色 */
const isDark = computed(() => {
  if (mode.value === 'dark') return true
  if (mode.value === 'light') return false
  return systemPrefersDark.value || clockPrefersDark.value
})

/** 给 UI 展示「为什么现在是暗色」 */
export type AutoReasonKind = 'system-night' | 'system' | 'night' | 'day' | ''

const autoReasonKind = computed<AutoReasonKind>(() => {
  if (mode.value !== 'auto') return ''
  if (systemPrefersDark.value && clockPrefersDark.value) return 'system-night'
  if (systemPrefersDark.value) return 'system'
  if (clockPrefersDark.value) return 'night'
  return 'day'
})

const autoReason = computed(() => {
  switch (autoReasonKind.value) {
    case 'system-night':
      return '系统偏好 + 夜间时段'
    case 'system':
      return '跟随系统偏好'
    case 'night':
      return `夜间时段（${DARK_HOUR_START}:00 - ${DARK_HOUR_END}:00）`
    case 'day':
      return '日间浅色'
    default:
      return ''
  }
})

function apply() {
  const root = document.documentElement
  root.classList.toggle('dark', isDark.value)
  // 让原生控件（滚动条、表单）跟着一起换配色
  root.style.colorScheme = isDark.value ? 'dark' : 'light'
}

function setMode(next: ThemeMode) {
  mode.value = next
  try {
    if (next === 'auto') localStorage.removeItem(STORAGE_KEY)
    else localStorage.setItem(STORAGE_KEY, next)
  } catch {
    /* 隐私模式下 localStorage 可能不可写，忽略即可 */
  }
  apply()
}

/** auto → light → dark → auto 循环 */
function cycleMode() {
  setMode(mode.value === 'auto' ? 'light' : mode.value === 'light' ? 'dark' : 'auto')
}

export function useTheme() {
  if (!initialized) {
    initialized = true

    const media = window.matchMedia('(prefers-color-scheme: dark)')
    systemPrefersDark.value = media.matches

    const onMediaChange = (event: MediaQueryListEvent) => {
      systemPrefersDark.value = event.matches
      apply()
    }
    media.addEventListener('change', onMediaChange)

    // 每分钟复查一次，跨过 18:00 / 06:00 时会自动切换
    const timer = window.setInterval(() => {
      const night = isNightNow()
      if (night !== clockPrefersDark.value) {
        clockPrefersDark.value = night
        apply()
      }
    }, 60_000)

    onScopeDispose(() => {
      media.removeEventListener('change', onMediaChange)
      window.clearInterval(timer)
      initialized = false
    })

    apply()
  }

  return { mode, isDark, autoReason, autoReasonKind, setMode, cycleMode }
}
