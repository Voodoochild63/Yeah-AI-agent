import type { ItineraryNode, ItineraryPlan, Lang } from '@/types/itinerary'
import { localizeDuration, pickLocalized } from '@/utils/localeText'
import { getGoogleMapsDirectionsUrl } from '@/utils/maps'

function categoryEmoji(node: ItineraryNode): string {
  const text = `${node.category ?? ''} ${node.poiName ?? ''}`.toLowerCase()
  if (/coffee|cafe|咖啡|brunch/.test(text)) return '☕️'
  if (/dining|restaurant|餐|food|eat|bar|酒/.test(text)) return '🍽️'
  if (/gallery|museum|art|展|美术/.test(text)) return '🖼️'
  if (/beach|海滨|海边/.test(text)) return '🏖️'
  if (/sunset|落日|park|公园/.test(text)) return '🌅'
  if (/record|vinyl|黑胶|music/.test(text)) return '🎵'
  return '📍'
}

/** 把当前行程收成可直接粘贴的纯文本清单。 */
export function formatItineraryPlainText(plan: ItineraryPlan, lang: Lang): string {
  const en = lang === 'en'
  const title =
    pickLocalized(lang, plan.titleZh, plan.titleEn, plan.title) || (en ? 'Sydney itinerary' : '悉尼行程')
  const lines: string[] = [`📍 ${title}`]

  const overview = [
    pickLocalized(lang, plan.summaryZh, plan.summaryEn, plan.summary),
    pickLocalized(lang, plan.detailedDescriptionZh, plan.detailedDescriptionEn, plan.detailedDescription || plan.overview),
  ]
    .filter(Boolean)
    .join('\n')
  if (overview) lines.push('', overview)

  const schedule = plan.schedule ?? []
  if (schedule.length) lines.push('')
  for (const node of schedule) {
    const time = node.arrivalTime?.trim()
    const name = node.poiName?.trim() || (en ? 'Untitled stop' : '未命名地点')
    const stay = localizeDuration(node.stayDuration, lang)
    const stayLabel = stay ? (en ? ` (stay ${stay})` : ` (停留 ${stay})`) : ''
    lines.push(`${time ? `${time} ` : ''}${categoryEmoji(node)} ${name}${stayLabel}`)
    const parking = node.parkingInfo
    if (parking?.hasParkingRecommendation === true) {
      const tip = parking?.tip?.trim() || node.parkingTips?.trim()
      if (tip) lines.push(`   🅿️ ${tip}`)
    }
  }

  const route = getGoogleMapsDirectionsUrl(schedule.map((node) => node.poiName))
  if (route) {
    lines.push('', en ? 'Full route' : '完整路线', route)
  }

  return `${lines.join('\n').trim()}\n`
}
