import type { ItineraryNode, ItineraryPlan, SpotCandidateDto, SunsetEvaluation } from '@/types/itinerary'

const MIN_ALIAS_LEN = 3
const TOKEN = '\u0000'

/** 当前卡片正在展示的商户，综述插值以它为准。 */
export function currentSpot(node: ItineraryNode | undefined): SpotCandidateDto | undefined {
  if (!node) return undefined
  const name = node.poiName?.trim()
  const matched = node.candidates?.find((item) => item.name?.trim().toLowerCase() === name?.toLowerCase())
  if (matched) return matched
  if (!name) return undefined
  return {
    name,
    categoryTag: node.category,
    latitude: node.latitude,
    longitude: node.longitude,
    openingHoursText: node.openingHoursText,
  }
}

/**
 * 把规划时写死的店名，替换成各节点当前激活的地点。
 * 原文案当模板保留，换点只读 schedule，0ms、不打网。
 */
export function bindActiveVenues(text: string | undefined | null, schedule: ItineraryNode[] | undefined): string {
  if (!text) return ''
  const nodes = schedule ?? []
  if (!nodes.length) return text

  const currents = uniqueNames(nodes.map((node) => node.poiName).filter(Boolean) as string[])
  const tokens: string[] = []
  let result = protectNames(text, currents, tokens)

  for (const pair of collectReplacements(nodes)) {
    result = replaceIgnoringCase(result, pair.from, stash(pair.to, tokens))
  }

  return restoreTokens(result, tokens)
}

export function bindSunset(
  sunset: SunsetEvaluation | undefined,
  schedule: ItineraryNode[] | undefined,
): SunsetEvaluation | undefined {
  if (!sunset) return sunset
  return {
    ...sunset,
    recommendedSpot: bindActiveVenues(sunset.recommendedSpot, schedule),
    recommendationReason: bindActiveVenues(sunset.recommendationReason, schedule),
    photoTip: bindActiveVenues(sunset.photoTip, schedule),
  }
}

/** 复制 / 导出 PDF 时用：双语字段都换成当前店名，避免和卡片打架。 */
export function withLiveNarrative(plan: ItineraryPlan): ItineraryPlan {
  const schedule = plan.schedule ?? []
  const bind = (value?: string | null) => (value == null ? value : bindActiveVenues(value, schedule))
  return {
    ...plan,
    title: bindActiveVenues(plan.title, schedule) || plan.title,
    titleZh: bind(plan.titleZh) ?? undefined,
    titleEn: bind(plan.titleEn) ?? undefined,
    summary: bindActiveVenues(plan.summary, schedule),
    summaryZh: bind(plan.summaryZh) ?? undefined,
    summaryEn: bind(plan.summaryEn) ?? undefined,
    detailedDescription: bind(plan.detailedDescription) ?? undefined,
    detailedDescriptionZh: bind(plan.detailedDescriptionZh) ?? undefined,
    detailedDescriptionEn: bind(plan.detailedDescriptionEn) ?? undefined,
    overview: bind(plan.overview) ?? undefined,
    backupSuggestions: (plan.backupSuggestions ?? []).map((item) => bindActiveVenues(item, schedule)),
    backupSuggestionsZh: plan.backupSuggestionsZh?.map((item) => bindActiveVenues(item, schedule)),
    backupSuggestionsEn: plan.backupSuggestionsEn?.map((item) => bindActiveVenues(item, schedule)),
    sunsetEvaluation: bindSunset(plan.sunsetEvaluation, schedule),
  }
}

export function collectReplacements(schedule: ItineraryNode[]): Array<{ from: string; to: string }> {
  const pairs: Array<{ from: string; to: string }> = []
  for (const node of schedule) {
    const to = node.poiName?.trim()
    if (!to) continue
    const active = currentSpot(node)
    for (const from of collectAliases(node)) {
      if (from.toLowerCase() === to.toLowerCase()) continue
      if (to.toLowerCase().includes(from.toLowerCase())) continue
      pairs.push({ from, to })
    }
    const nextSnippet = active?.highlightDesc?.trim()
    if (nextSnippet && nextSnippet.length >= MIN_ALIAS_LEN) {
      for (const candidate of node.candidates ?? []) {
        const snippet = candidate.highlightDesc?.trim()
        if (!snippet || snippet.toLowerCase() === nextSnippet.toLowerCase()) continue
        if (nextSnippet.toLowerCase().includes(snippet.toLowerCase())) continue
        pairs.push({ from: snippet, to: nextSnippet })
      }
    }
  }
  pairs.sort((left, right) => right.from.length - left.from.length || left.from.localeCompare(right.from))
  const seen = new Set<string>()
  const unique: Array<{ from: string; to: string }> = []
  for (const pair of pairs) {
    const key = pair.from.toLowerCase()
    if (seen.has(key)) continue
    seen.add(key)
    unique.push(pair)
  }
  return unique
}

function collectAliases(node: ItineraryNode): string[] {
  const names = new Set<string>()
  remember(names, node.poiName)
  for (const candidate of node.candidates ?? node.sunsetCandidates ?? []) {
    remember(names, candidate.name)
  }
  return [...names]
}

function remember(bucket: Set<string>, value?: string) {
  const trimmed = value?.trim()
  if (trimmed && trimmed.length >= MIN_ALIAS_LEN) bucket.add(trimmed)
}

function uniqueNames(values: string[]): string[] {
  const seen = new Set<string>()
  const names: string[] = []
  for (const value of values) {
    const trimmed = value.trim()
    const key = trimmed.toLowerCase()
    if (!trimmed || seen.has(key)) continue
    seen.add(key)
    names.push(trimmed)
  }
  return names.sort((left, right) => right.length - left.length)
}

function protectNames(text: string, names: string[], tokens: string[]): string {
  let result = text
  for (const name of names) {
    result = replaceIgnoringCase(result, name, stash(name, tokens))
  }
  return result
}

function stash(value: string, tokens: string[]): string {
  const index = tokens.length
  tokens.push(value)
  return `${TOKEN}${index}${TOKEN}`
}

function restoreTokens(text: string, tokens: string[]): string {
  return text.replace(/\u0000(\d+)\u0000/g, (_whole, index: string) => tokens[Number(index)] ?? '')
}

function replaceIgnoringCase(text: string, from: string, to: string): string {
  if (!from || from.toLowerCase() === to.toLowerCase()) return text
  return text.replace(new RegExp(escapeRegExp(from), 'gi'), to)
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
