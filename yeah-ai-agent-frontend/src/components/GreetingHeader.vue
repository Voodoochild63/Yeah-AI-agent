<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import type { Lang } from '@/types/itinerary'

const props = withDefaults(
  defineProps<{
    lang?: Lang
  }>(),
  { lang: 'zh' },
)

const GREETINGS: Record<Lang, string[]> = {
  zh: [
    '我们开始吧',
    '今天想去哪个街区寻找扫街灵感？',
    '放首 Neo-Soul，来规划今天的行程吧',
    '悉尼的阳光正好，去探店还是拍点城市光影？',
  ],
  en: [
    'Ready when you are.',
    'Which neighbourhood are we exploring today?',
    "Put on some Neo-Soul, let's map out the day.",
    'Chasing the golden hour or catching a specialty brew?',
  ],
}

const greetingIndex = ref(0)
const greeting = ref('')
const visible = ref(false)

function pool() {
  return GREETINGS[props.lang]
}

function pickGreeting(randomize: boolean) {
  const lines = pool()
  if (randomize) {
    greetingIndex.value = Math.floor(Math.random() * lines.length)
  }
  greeting.value = lines[greetingIndex.value] ?? lines[0]!
}

onMounted(() => {
  pickGreeting(true)
  requestAnimationFrame(() => {
    visible.value = true
  })
})

watch(
  () => props.lang,
  async () => {
    visible.value = false
    await nextTick()
    window.setTimeout(() => {
      pickGreeting(false)
      visible.value = true
    }, 180)
  },
)
</script>

<template>
  <div class="flex flex-col items-center px-6 text-center">
    <p
      class="mb-3 text-[0.7rem] font-medium tracking-[0.22em] text-gray-400 uppercase transition-all duration-700 dark:text-gray-500"
      :class="visible ? 'translate-y-0 opacity-100' : 'translate-y-2 opacity-0'"
    >
      Sydney Lifestyle Agent
    </p>

    <h1
      class="text-2xl leading-relaxed font-light tracking-wide text-gray-800 transition-all duration-500 ease-out sm:text-3xl dark:text-gray-100"
      :class="visible ? 'translate-y-0 opacity-100 blur-0' : 'translate-y-2 opacity-0 blur-[2px]'"
    >
      {{ greeting }}
    </h1>
  </div>
</template>
