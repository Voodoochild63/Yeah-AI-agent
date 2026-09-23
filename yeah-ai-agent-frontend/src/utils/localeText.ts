import type { Lang } from '@/types/itinerary'

function modeLabel(text: string): string {
  if (/(驾车|开车|自驾|drive|driving|car)/i.test(text)) return 'Drive'
  if (/(公交|巴士|\bbus\b)/i.test(text)) return 'Bus'
  if (/(地铁|火车|train|metro)/i.test(text)) return 'Train'
  if (/(轻轨|tram|light rail)/i.test(text)) return 'Light rail'
  if (/(渡轮|ferry)/i.test(text)) return 'Ferry'
  if (/(骑行|单车|bike|cycl)/i.test(text)) return 'Bike'
  if (/(步行|walk|foot)/i.test(text)) return 'Walk'
  return ''
}

/** 英文模式下把「步行约5分钟」收成 Walk ~5 mins，驾车则是 Drive ~5 mins。 */
export function localizeTransit(text: string, lang: Lang): string {
  const value = text?.trim()
  if (!value || lang !== 'en' || !/[\u4e00-\u9fff]/.test(value)) return text

  const mode = modeLabel(value)
  const minutes = value.match(/(\d+)\s*分钟/)
  const hours = value.match(/(\d+(?:\.\d+)?)\s*(?:个)?小时/)
  const duration = minutes
    ? `~${minutes[1]} mins`
    : hours
      ? `~${hours[1]} ${hours[1] === '1' ? 'hour' : 'hours'}`
      : ''
  const destination = value.match(/(?:前往|去|到)\s*([^。！!，,]+)/)?.[1]?.trim()

  if (mode && duration && destination) return `${mode} ${duration} to ${destination}`
  if (mode && duration) return `${mode} ${duration}`
  return value
    .replace(/步行约/g, 'Walk ~')
    .replace(/(?:驾车|开车|自驾)约/g, 'Drive ~')
    .replace(/分钟/g, ' mins')
    .replace(/小时/g, ' hours')
}

/** 英文模式下把「2小时」「30分钟」收成 2 hours / 30 mins。 */
export function localizeDuration(text: string | undefined, lang: Lang): string {
  const value = text?.trim()
  if (!value || lang !== 'en' || !/[\u4e00-\u9fff]/.test(value)) return value ?? ''

  return value
    .replace(/约\s*(\d+(?:\.\d+)?)\s*(?:个)?小时/g, '~$1 hours')
    .replace(/(\d+(?:\.\d+)?)\s*(?:个)?小时/g, '$1 hours')
    .replace(/约\s*(\d+)\s*分钟/g, '~$1 mins')
    .replace(/(\d+)\s*分钟/g, '$1 mins')
    .replace(/停留/g, 'stay')
}

/** 英文模式下把「步行约 10 分钟 (814m)」收成 ~10 mins walk (814m)。 */
export function localizeWalkingEstimate(text: string | undefined, lang: Lang): string {
  const value = text?.trim()
  if (!value || lang !== 'en') return text ?? ''

  const zh = value.match(/步行约?\s*(\d+)\s*分钟\s*[（(]\s*(\d+)\s*m\s*[）)]/i)
  if (zh) return `~${zh[1]} mins walk (${zh[2]}m)`

  const legacy = value.match(/About\s+(\d+)\s+min(?:ute)?s?\s+walk\s*[（(]\s*(\d+)\s*m\s*[）)]/i)
  if (legacy) return `~${legacy[1]} mins walk (${legacy[2]}m)`

  return value
}

export function pickLocalized(
  lang: Lang,
  zh?: string | null,
  en?: string | null,
  fallback?: string | null,
): string {
  const primary = lang === 'en' ? en : zh
  const secondary = lang === 'en' ? zh : en
  return primary?.trim() || fallback?.trim() || secondary?.trim() || ''
}

export function pickLocalizedList(
  lang: Lang,
  zh?: string[] | null,
  en?: string[] | null,
  fallback?: string[] | null,
): string[] {
  const primary = (lang === 'en' ? en : zh)?.filter((item) => Boolean(item?.trim()))
  if (primary?.length) return primary
  const base = fallback?.filter((item) => Boolean(item?.trim()))
  if (base?.length) return base
  return (lang === 'en' ? zh : en)?.filter((item) => Boolean(item?.trim())) ?? []
}
