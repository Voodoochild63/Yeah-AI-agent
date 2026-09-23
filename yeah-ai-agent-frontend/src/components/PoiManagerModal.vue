<script setup lang="ts">
import { onBeforeUnmount, reactive, ref, watch } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import { parseTagInput, usePoiLibrary } from '@/composables/usePoiLibrary'
import { useTasteProfile } from '@/composables/useTasteProfile'
import type { Lang, TastePace } from '@/types/itinerary'

const props = withDefaults(
  defineProps<{ open: boolean; lang?: Lang }>(),
  { lang: 'zh' },
)
const emit = defineEmits<{ close: [] }>()

const { items, add, update, remove } = usePoiLibrary()
const { taste } = useTasteProfile()
const activeTab = ref<'taste' | 'pois'>('taste')
const isEn = () => props.lang === 'en'

const PACE_OPTIONS: { value: TastePace; zh: string; en: string }[] = [
  { value: 'relaxed', zh: '松弛 (Relaxed)', en: 'Relaxed' },
  { value: 'moderate', zh: '适中 (Moderate)', en: 'Moderate' },
  { value: 'active', zh: '充实打卡 (Active)', en: 'Active' },
]

const editingId = ref('')

const form = reactive({
  poiName: '',
  category: '',
  tags: '',
  vibes: '',
  visualStyle: '',
  personalTip: '',
  description: '',
})

/** 文本单行字段。多行的 personalTip / description 单独写，因为要用 textarea */
const TEXT_FIELDS: { key: 'poiName' | 'category' | 'tags' | 'vibes' | 'visualStyle'; label: string; placeholder: string; required: boolean }[] = [
  { key: 'poiName', label: '店名', placeholder: '地点名称', required: true },
  { key: 'category', label: '类别', placeholder: '例如：咖啡馆 / 展览 / 公园', required: false },
  { key: 'tags', label: '标签', placeholder: '街区、品类，用逗号分隔', required: false },
  { key: 'vibes', label: '氛围', placeholder: '例如：安静、复古、热闹', required: false },
  { key: 'visualStyle', label: '摄影风格', placeholder: '例如：自然光、几何构图', required: false },
]

function resetForm() {
  editingId.value = ''
  form.poiName = ''
  form.category = ''
  form.tags = ''
  form.vibes = ''
  form.visualStyle = ''
  form.personalTip = ''
  form.description = ''
}

function startEdit(id: string) {
  const target = items.value.find((item) => item.id === id)
  if (!target) return
  editingId.value = id
  form.poiName = target.poiName
  form.category = target.category
  form.tags = target.tags.join(', ')
  form.vibes = target.vibes.join(', ')
  form.visualStyle = target.visualStyle.join(', ')
  form.personalTip = target.personalTip
  form.description = target.description
}

function save() {
  if (!form.poiName.trim()) return

  const payload = {
    poiName: form.poiName.trim(),
    category: form.category.trim(),
    tags: parseTagInput(form.tags),
    vibes: parseTagInput(form.vibes),
    visualStyle: parseTagInput(form.visualStyle),
    personalTip: form.personalTip.trim(),
    description: form.description.trim(),
  }

  if (editingId.value) update(editingId.value, payload)
  else add(payload)

  resetForm()
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') emit('close')
}

watch(
  () => props.open,
  (open) => {
    document.body.style.overflow = open ? 'hidden' : ''
    if (open) {
      window.addEventListener('keydown', onKeydown)
    } else {
      window.removeEventListener('keydown', onKeydown)
      resetForm()
    }
  },
)

onBeforeUnmount(() => {
  document.body.style.overflow = ''
  window.removeEventListener('keydown', onKeydown)
})

const INPUT_CLASS =
  'focus-ring w-full rounded-lg border border-gray-200 bg-white px-3 py-2 text-sm text-gray-800 placeholder-gray-400 transition-colors focus:border-blue-400 dark:border-gray-700 dark:bg-gray-800/60 dark:text-gray-100 dark:placeholder-gray-600 dark:focus:border-blue-500'
</script>

<template>
  <Teleport to="body">
    <Transition
      enter-active-class="transition-opacity duration-300"
      enter-from-class="opacity-0"
      leave-active-class="transition-opacity duration-200"
      leave-to-class="opacity-0"
    >
      <div
        v-if="open"
        class="fixed inset-0 z-60 overflow-y-auto bg-gray-900/30 backdrop-blur-sm dark:bg-black/60"
        @click.self="emit('close')"
      >
        <div
          role="dialog"
          aria-modal="true"
          aria-label="RAG 知识库中心"
          class="animate-fade-up mx-auto my-[4vh] flex w-[min(52rem,94vw)] flex-col overflow-hidden rounded-2xl border border-gray-200/80 bg-white/95 shadow-2xl backdrop-blur-xl dark:border-gray-700/60 dark:bg-gray-900/95"
        >
          <!-- 头部 -->
          <header
            class="flex items-start justify-between gap-4 border-b border-gray-200/70 px-6 py-5 dark:border-gray-700/50"
          >
            <div>
              <h2 class="flex items-center gap-2 text-base font-semibold text-gray-900 dark:text-gray-50">
                <AppIcon name="map-pin" class="h-4 w-4 text-blue-600 dark:text-blue-400" />
                RAG 知识库中心
              </h2>
              <p class="mt-1.5 text-xs leading-relaxed text-gray-500 dark:text-gray-400">
                审美偏好与私域打卡点会随规划请求一起发给后端，参与 Serper 检索与个性化排线。数据保存在
                <code class="rounded bg-gray-100 px-1 py-0.5 text-[0.7rem] dark:bg-gray-800">localStorage</code>。
              </p>
            </div>
            <button
              type="button"
              aria-label="关闭"
              class="focus-ring flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-700 dark:hover:bg-gray-800 dark:hover:text-gray-200"
              @click="emit('close')"
            >
              <AppIcon name="x" class="h-4 w-4" />
            </button>
          </header>

          <div class="flex gap-2 border-b border-gray-200/70 px-6 pt-3 dark:border-gray-700/50">
            <button
              type="button"
              class="rounded-t-lg px-3 py-2 text-xs font-medium transition-colors"
              :class="
                activeTab === 'taste'
                  ? 'bg-amber-50 text-amber-800 dark:bg-amber-500/10 dark:text-amber-200'
                  : 'text-gray-500 hover:text-gray-800 dark:text-gray-400 dark:hover:text-gray-200'
              "
              @click="activeTab = 'taste'"
            >
              🌟 {{ isEn() ? 'My Taste Profile' : '我的审美偏好' }}
            </button>
            <button
              type="button"
              class="rounded-t-lg px-3 py-2 text-xs font-medium transition-colors"
              :class="
                activeTab === 'pois'
                  ? 'bg-blue-50 text-blue-800 dark:bg-blue-500/10 dark:text-blue-200'
                  : 'text-gray-500 hover:text-gray-800 dark:text-gray-400 dark:hover:text-gray-200'
              "
              @click="activeTab = 'pois'"
            >
              📍 {{ isEn() ? 'Custom POIs' : '私域打卡点库' }}
            </button>
          </div>

          <div v-if="activeTab === 'taste'" class="space-y-4 p-6">
            <div>
              <label for="taste-aesthetic" class="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                {{ isEn() ? 'Aesthetic' : '审美偏好' }}
              </label>
              <input
                id="taste-aesthetic"
                v-model="taste.aesthetic"
                type="text"
                placeholder="工业复古、自然光影、扫街摄影"
                :class="INPUT_CLASS"
              />
            </div>
            <div>
              <label for="taste-photo" class="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                {{ isEn() ? 'Photo style' : '摄影风格' }}
              </label>
              <input
                id="taste-photo"
                v-model="taste.photoStyle"
                type="text"
                placeholder="高对比度倒影、人文纪实"
                :class="INPUT_CLASS"
              />
            </div>
            <div>
              <p class="mb-1.5 text-xs font-medium text-gray-600 dark:text-gray-400">
                {{ isEn() ? 'Pace' : '行程节奏' }}
              </p>
              <div class="flex flex-wrap gap-2">
                <label
                  v-for="option in PACE_OPTIONS"
                  :key="option.value"
                  class="inline-flex cursor-pointer items-center gap-1.5 rounded-full border px-3 py-1.5 text-xs"
                  :class="
                    taste.pace === option.value
                      ? 'border-amber-300 bg-amber-50 text-amber-800 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-200'
                      : 'border-gray-200 text-gray-600 dark:border-gray-700 dark:text-gray-300'
                  "
                >
                  <input v-model="taste.pace" type="radio" :value="option.value" class="sr-only" />
                  {{ isEn() ? option.en : option.zh }}
                </label>
              </div>
            </div>
            <div>
              <label for="taste-notes" class="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                {{ isEn() ? 'Personal notes' : '专属避坑' }}
              </label>
              <textarea
                id="taste-notes"
                v-model="taste.customNotes"
                rows="4"
                placeholder="偏爱独立咖啡馆，排斥网红连锁"
                :class="[INPUT_CLASS, 'resize-none']"
              />
            </div>
            <p class="text-xs text-gray-400 dark:text-gray-500">
              {{ isEn() ? 'Saved locally and sent with every plan request.' : '会实时保存，并随每次规划一起发给后端。' }}
            </p>
          </div>

          <div v-else class="grid gap-0 md:grid-cols-[1fr_1.1fr]">
            <!-- 表单 -->
            <form
              class="space-y-3 border-b border-gray-200/70 p-5 md:border-r md:border-b-0 dark:border-gray-700/50"
              @submit.prevent="save"
            >
              <p class="text-xs font-semibold tracking-wider text-gray-400 uppercase dark:text-gray-500">
                {{ editingId ? '编辑打卡点' : '新增打卡点' }}
              </p>

              <div v-for="field in TEXT_FIELDS" :key="field.key">
                <label :for="`poi-${field.key}`" class="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                  {{ field.label }}
                  <span v-if="field.required" class="text-rose-500">*</span>
                </label>
                <input
                  :id="`poi-${field.key}`"
                  v-model="form[field.key]"
                  type="text"
                  :placeholder="field.placeholder"
                  :class="INPUT_CLASS"
                />
              </div>

              <div>
                <label for="poi-tip" class="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                  打卡小贴士
                </label>
                <textarea
                  id="poi-tip"
                  v-model="form.personalTip"
                  rows="2"
                  placeholder="例如：周末建议早点到，靠窗位光线更好"
                  :class="[INPUT_CLASS, 'resize-none']"
                />
              </div>

              <div>
                <label for="poi-desc" class="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                  描述
                </label>
                <textarea
                  id="poi-desc"
                  v-model="form.description"
                  rows="2"
                  placeholder="一两句介绍这个地方为什么值得去"
                  :class="[INPUT_CLASS, 'resize-none']"
                />
              </div>

              <div class="flex gap-2 pt-1">
                <button
                  type="submit"
                  :disabled="!form.poiName.trim()"
                  class="focus-ring flex-1 rounded-lg bg-gray-900 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-gray-700 disabled:cursor-not-allowed disabled:opacity-40 dark:bg-white dark:text-gray-900 dark:hover:bg-gray-200"
                >
                  {{ editingId ? '保存修改' : '添加' }}
                </button>
                <button
                  v-if="editingId"
                  type="button"
                  class="focus-ring rounded-lg border border-gray-200 px-4 py-2 text-sm font-medium text-gray-600 transition-colors hover:bg-gray-50 dark:border-gray-700 dark:text-gray-300 dark:hover:bg-gray-800"
                  @click="resetForm"
                >
                  取消
                </button>
              </div>
            </form>

            <!-- 列表 -->
            <div class="p-5">
              <p class="mb-3 text-xs font-semibold tracking-wider text-gray-400 uppercase dark:text-gray-500">
                已收录 {{ items.length }} 个
              </p>

              <p v-if="!items.length" class="text-sm text-gray-500 dark:text-gray-400">
                还没有打卡点，先在左侧添加一个吧。
              </p>

              <ul class="space-y-2.5">
                <li
                  v-for="item in items"
                  :key="item.id"
                  class="group rounded-xl border border-gray-100 bg-white/70 p-3.5 transition-colors hover:border-gray-200 dark:border-gray-700/50 dark:bg-gray-800/40 dark:hover:border-gray-600"
                >
                  <div class="flex items-start justify-between gap-2">
                    <div class="min-w-0">
                      <p class="truncate text-sm font-semibold text-gray-900 dark:text-gray-50">
                        {{ item.poiName }}
                      </p>
                      <p v-if="item.category" class="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
                        {{ item.category }}
                      </p>
                    </div>
                    <div
                      class="flex shrink-0 gap-1 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100"
                    >
                      <button
                        type="button"
                        aria-label="编辑"
                        class="focus-ring flex h-7 w-7 items-center justify-center rounded-md text-gray-400 hover:bg-gray-100 hover:text-gray-700 dark:hover:bg-gray-700 dark:hover:text-gray-200"
                        @click="startEdit(item.id)"
                      >
                        <AppIcon name="pencil" class="h-3.5 w-3.5" />
                      </button>
                      <button
                        type="button"
                        aria-label="删除"
                        class="focus-ring flex h-7 w-7 items-center justify-center rounded-md text-gray-400 hover:bg-rose-50 hover:text-rose-600 dark:hover:bg-rose-500/15 dark:hover:text-rose-400"
                        @click="remove(item.id)"
                      >
                        <AppIcon name="trash" class="h-3.5 w-3.5" />
                      </button>
                    </div>
                  </div>

                  <div v-if="item.tags.length || item.vibes.length" class="mt-2 flex flex-wrap gap-1.5">
                    <span
                      v-for="tag in item.tags"
                      :key="`t-${tag}`"
                      class="tag-chip bg-gray-100 text-gray-600 dark:bg-gray-700/60 dark:text-gray-300"
                    >
                      {{ tag }}
                    </span>
                    <span
                      v-for="vibe in item.vibes"
                      :key="`v-${vibe}`"
                      class="tag-chip bg-indigo-50 text-indigo-600 dark:bg-indigo-500/15 dark:text-indigo-300"
                    >
                      {{ vibe }}
                    </span>
                  </div>

                  <p
                    v-if="item.personalTip"
                    class="mt-2 flex items-start gap-1.5 text-xs leading-relaxed text-gray-500 dark:text-gray-400"
                  >
                    <AppIcon name="lightbulb" class="mt-0.5 h-3.5 w-3.5 shrink-0 text-amber-500" />
                    <span>{{ item.personalTip }}</span>
                  </p>
                </li>
              </ul>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>
