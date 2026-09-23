<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import type { Lang } from '@/types/itinerary'

const props = defineProps<{
  loading: boolean
  lang: Lang
}>()

const emit = defineEmits<{
  submit: [message: string]
  stop: []
  'open-drawer': []
  'update:lang': [lang: Lang]
}>()

const text = ref('')
const inputEl = ref<HTMLInputElement | null>(null)
const listening = ref(false)
const speechError = ref('')

const canSubmit = computed(() => text.value.trim().length > 0 && !props.loading)
const placeholder = computed(() => (props.lang === 'en' ? 'Ask Yeah AI...' : '问问 Yeah AI...'))
const stopLabel = computed(() => (props.lang === 'en' ? 'Stop generating' : '停止生成'))
const langLabel = computed(() => (props.lang === 'en' ? 'EN' : 'ZH'))
const langTitle = computed(() =>
  props.lang === 'en' ? 'Switch to Chinese' : '切换到英文',
)

function handleSubmit() {
  if (!canSubmit.value) return
  emit('submit', text.value)
  text.value = ''
}

function toggleLang() {
  emit('update:lang', props.lang === 'en' ? 'zh' : 'en')
  inputEl.value?.focus()
}

/* ---------- 语音输入：Web Speech API，不支持时优雅退化 ---------- */

type SpeechRecognitionLike = {
  lang: string
  interimResults: boolean
  continuous: boolean
  start: () => void
  stop: () => void
  onresult: ((event: { results: ArrayLike<ArrayLike<{ transcript: string }>> }) => void) | null
  onerror: (() => void) | null
  onend: (() => void) | null
}

function getSpeechCtor(): (new () => SpeechRecognitionLike) | null {
  const w = window as unknown as Record<string, new () => SpeechRecognitionLike>
  return w['SpeechRecognition'] ?? w['webkitSpeechRecognition'] ?? null
}

const speechSupported = computed(() => getSpeechCtor() !== null)
let recognition: SpeechRecognitionLike | null = null

function flashSpeechError(message: string) {
  speechError.value = message
  window.setTimeout(() => (speechError.value = ''), 3_000)
}

function toggleVoice() {
  if (listening.value) {
    recognition?.stop()
    return
  }

  const Ctor = getSpeechCtor()
  if (!Ctor) {
    flashSpeechError(props.lang === 'en' ? 'Voice input is not supported in this browser.' : '当前浏览器不支持语音输入，请改用键盘。')
    return
  }

  recognition = new Ctor()
  recognition.lang = props.lang === 'en' ? 'en-AU' : 'zh-CN'
  recognition.interimResults = true
  recognition.continuous = false

  recognition.onresult = (event) => {
    text.value = Array.from(event.results)
      .map((result) => result[0]?.transcript ?? '')
      .join('')
  }
  recognition.onerror = () =>
    flashSpeechError(props.lang === 'en' ? 'Didn’t catch that. Try again?' : '没听清，再试一次？')
  recognition.onend = () => {
    listening.value = false
  }

  listening.value = true
  recognition.start()
}

onBeforeUnmount(() => recognition?.stop())
</script>

<template>
  <div class="relative w-full">
    <!-- 胶囊主体。backdrop-blur 会生成独立层叠上下文，
         所以这里必须显式给 z-30，才能压住下面 z-20 的点击遮罩 -->
    <div
      class="shadow-capsule hover:shadow-capsule-hover focus-within:shadow-capsule-hover dark:shadow-capsule-dark dark:hover:shadow-capsule-dark-hover relative z-30 flex items-center gap-1 rounded-full bg-gray-100/80 px-2 py-2 ring-1 ring-black/[0.03] backdrop-blur-xl transition-all duration-300 focus-within:bg-gray-100 dark:bg-gray-800/80 dark:ring-white/[0.06] dark:focus-within:bg-gray-800"
    >
      <!-- 左侧：唤出侧边栏 -->
      <button
        type="button"
        :aria-label="lang === 'en' ? 'Open menu' : '打开侧边栏'"
        :title="lang === 'en' ? 'Open menu' : '打开侧边栏'"
        class="focus-ring flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-gray-500 transition-all hover:bg-white hover:text-gray-900 hover:shadow-sm active:scale-95 dark:text-gray-400 dark:hover:bg-gray-700 dark:hover:text-gray-100"
        @click="emit('open-drawer')"
      >
        <AppIcon name="plus" class="h-5 w-5" />
      </button>

      <!-- 中间：输入框 -->
      <input
        ref="inputEl"
        v-model="text"
        type="text"
        :placeholder="placeholder"
        enterkeyhint="send"
        :disabled="loading"
        class="min-w-0 flex-1 border-none bg-transparent px-2 text-[0.95rem] text-gray-800 placeholder-gray-400 outline-none focus:outline-none disabled:cursor-not-allowed disabled:opacity-60 dark:text-gray-100 dark:placeholder-gray-500"
        @keydown.enter.prevent="handleSubmit"
      />

      <!-- 右侧：中/英胶囊切换 -->
      <button
        type="button"
        :aria-label="langTitle"
        :title="langTitle"
        class="focus-ring flex h-8 min-w-9 cursor-pointer items-center justify-center rounded-full px-2.5 text-xs font-semibold tracking-wide text-gray-600 transition-colors hover:bg-black/5 dark:text-gray-300 dark:hover:bg-white/10"
        @click="toggleLang"
      >
        {{ langLabel }}
      </button>

      <!-- 右侧：麦克风 -->
      <button
        type="button"
        :aria-label="listening ? (lang === 'en' ? 'Stop voice input' : '停止语音输入') : lang === 'en' ? 'Voice input' : '语音输入'"
        :title="
          speechSupported
            ? listening
              ? lang === 'en'
                ? 'Stop voice input'
                : '停止语音输入'
              : lang === 'en'
                ? 'Voice input'
                : '语音输入'
            : lang === 'en'
              ? 'Voice input is not supported'
              : '当前浏览器不支持语音输入'
        "
        class="focus-ring flex h-9 w-9 shrink-0 items-center justify-center rounded-full transition-all active:scale-95"
        :class="
          listening
            ? 'bg-red-500 text-white shadow-md shadow-red-500/30'
            : 'text-gray-500 hover:bg-white hover:text-gray-900 hover:shadow-sm dark:text-gray-400 dark:hover:bg-gray-700 dark:hover:text-gray-100'
        "
        @click="toggleVoice"
      >
        <AppIcon
          name="microphone"
          class="h-[1.15rem] w-[1.15rem]"
          :class="listening ? 'animate-pulse' : ''"
        />
      </button>

      <!-- 右侧：发送。只在有内容或加载中时出现，平时保持胶囊简洁 -->
      <Transition
        enter-active-class="transition duration-200 ease-out"
        enter-from-class="opacity-0 scale-50"
        leave-active-class="transition duration-150 ease-in"
        leave-to-class="opacity-0 scale-50"
      >
        <button
          v-if="canSubmit || loading"
          type="button"
          :disabled="loading ? false : !canSubmit"
          :aria-label="loading ? stopLabel : lang === 'en' ? 'Send' : '发送'"
          :title="loading ? stopLabel : lang === 'en' ? 'Send' : '发送'"
          class="focus-ring flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-gray-900 text-white shadow-md transition-all hover:bg-gray-700 active:scale-95 disabled:opacity-70 dark:bg-white dark:text-gray-900 dark:hover:bg-gray-200"
          @click="loading ? emit('stop') : handleSubmit()"
        >
          <AppIcon v-if="loading" name="stop" class="h-4 w-4" />
          <AppIcon v-else name="arrow-up" class="h-4 w-4" :weight="2.2" />
        </button>
      </Transition>
    </div>

    <Transition
      enter-active-class="transition duration-200"
      enter-from-class="opacity-0 -translate-y-1"
      leave-active-class="transition duration-200"
      leave-to-class="opacity-0"
    >
      <p
        v-if="speechError"
        class="absolute top-full left-1/2 mt-2 -translate-x-1/2 text-xs whitespace-nowrap text-amber-600 dark:text-amber-400"
      >
        {{ speechError }}
      </p>
    </Transition>
  </div>
</template>
