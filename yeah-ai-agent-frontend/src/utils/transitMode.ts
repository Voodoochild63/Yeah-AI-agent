import type { ItineraryNode, Lang, TransitModeOption, TransitOptions, TravelMode } from '@/types/itinerary'
import { refreshOpenStatus } from '@/utils/openHours'

const INNOCENT_CATEGORIES = new Set([
  'SUNSET', 'WALK', 'SCENIC', 'PARK', 'CAFE', 'TEA', 'DINING', 'KOREAN_RESTAURANT', 'DESSERT', 'ATTRACTION', 'SHOPPING',
])

export function isAlcoholStop(node: ItineraryNode | undefined): boolean {
  if (!node) return false
  const cat = (node.spotCategory ?? '').toUpperCase()
  if (cat === 'BAR' || cat === 'PUB') return true
  if (INNOCENT_CATEGORIES.has(cat)) return false
  const type = `${node.category ?? ''} ${node.categoryZh ?? ''} ${node.categoryEn ?? ''} ${node.spotCategory ?? ''}`
  if (/barangaroo|barista|barbecue|\bbbq\b|barber|coffee bar|juice bar/i.test(type)) return false
  return /\b(bars?|pubs?)\b|酒吧|清吧|酒馆/.test(type)
}

export function drivingForbiddenOn(node: ItineraryNode | undefined): boolean {
  if (!node) return false
  return Boolean(node.drivingForbidden || node.transitOptions?.drivingAllowed === false)
}

export function optionFor(options: TransitOptions | undefined, mode: TravelMode): TransitModeOption | undefined {
  if (!options) return undefined
  if (mode === 'DRIVING') return options.driving
  if (mode === 'TRANSIT') return options.transit
  return options.walking
}

export function hopMinutes(option: TransitModeOption | undefined): number {
  const raw = option?.durationMinutes
  if (raw == null || !Number.isFinite(raw) || raw < 0 || raw > 120) return 12
  return raw
}

export function formatModeLabel(mode: TravelMode, option: TransitModeOption | undefined, lang: Lang): string {
  const verb =
    mode === 'DRIVING' ? (lang === 'en' ? 'Drive' : '驾车') : mode === 'TRANSIT' ? (lang === 'en' ? 'Transit' : '公交') : lang === 'en' ? 'Walk' : '步行'
  if (!option) return verb
  const safeMinutes = hopMinutes(option)
  const dirtyText = option.durationText?.trim() ?? ''
  const minutes =
    dirtyText && !/\d{4,}/.test(dirtyText) && safeMinutes === option.durationMinutes
      ? dirtyText
      : lang === 'en'
        ? `${safeMinutes} min`
        : `${safeMinutes}分钟`
  const rawDistance = option.distanceText?.trim() ?? ''
  const distance = rawDistance && !/\d{4,}/.test(rawDistance) ? rawDistance : ''
  if (minutes && distance) return `${verb} ${minutes} · ${distance}`
  if (minutes) return `${verb} ${minutes}`
  if (distance) return `${verb} ${distance}`
  return verb
}

export function parseClock(value: string | undefined): number | null {
  const match = value?.trim().match(/^(\d{1,2}):(\d{2})$/)
  if (!match) return null
  const hours = Number(match[1])
  const minutes = Number(match[2])
  if (hours > 23 || minutes > 59) return null
  return hours * 60 + minutes
}

export function formatClock(totalMinutes: number): string {
  const capped = Math.max(0, Math.min(23 * 60 + 59, totalMinutes))
  const hours = Math.floor(capped / 60)
  const minutes = capped % 60
  return `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}`
}

export function parseStayMinutes(value: string | undefined): number {
  if (!value?.trim()) return 0
  const hours = value.match(/(\d+(?:\.\d+)?)\s*(?:个)?(?:小时|hours?)/i)
  const minutes = value.match(/(\d+)\s*(?:分钟|mins?|minutes?)/i)
  let total = 0
  if (hours) total += Math.round(Number(hours[1]) * 60)
  if (minutes) total += Number(minutes[1])
  if (total < 0 || total > 240) return 45
  return total
}

export function applyTravelMode(schedule: ItineraryNode[], fromIndex: number, mode: TravelMode, lang: Lang) {
  const from = schedule[fromIndex]
  const next = schedule[fromIndex + 1]
  if (!from?.transitOptions) return
  if (mode === 'DRIVING' && (from.drivingForbidden || from.transitOptions.drivingAllowed === false || isAlcoholStop(next))) {
    return
  }

  const option = optionFor(from.transitOptions, mode)
  from.selectedMode = mode
  from.transitToNext = formatModeLabel(mode, option, lang)

  if (next) {
    if (mode === 'DRIVING') {
      const keyword =
        next.parkingSearchKeyword?.trim() ||
        (next.poiName?.trim() ? `parking near ${next.poiName.trim()} Sydney` : 'parking near Sydney')
      next.parkingSearchKeyword = keyword
      next.parkingInfo = {
        ...(next.parkingInfo ?? { hasParkingRecommendation: true }),
        hasParkingRecommendation: true,
        parkingSearchKeyword: keyword,
      }
    } else {
      next.parkingInfo = {
        ...(next.parkingInfo ?? { hasParkingRecommendation: false }),
        hasParkingRecommendation: false,
      }
    }
  }

  let cursor = parseClock(from.arrivalTime)
  if (cursor == null) return
  for (let i = fromIndex; i < schedule.length - 1; i++) {
    const current = schedule[i]
    const following = schedule[i + 1]
    if (!current || !following) break
    const stay = parseStayMinutes(current.stayDuration)
    const hop = hopMinutes(optionFor(current.transitOptions, current.selectedMode ?? 'WALKING'))
    cursor += stay + hop
    following.arrivalTime = formatClock(cursor)
    following.estimatedArrival = following.arrivalTime
    refreshOpenStatus(following, lang)
  }
}

export function parkingVisible(schedule: ItineraryNode[], index: number): boolean {
  const node = schedule[index]
  if (!node?.poiName?.trim()) return false
  if (isAlcoholStop(node)) return false
  if (index === 0) return node.parkingInfo?.hasParkingRecommendation !== false
  const previous = schedule[index - 1]
  if (drivingForbiddenOn(previous) || isAlcoholStop(previous)) return false
  if (previous?.selectedMode === 'DRIVING') return true
  if (previous?.selectedMode === 'WALKING' || previous?.selectedMode === 'TRANSIT') return false
  return node.parkingInfo?.hasParkingRecommendation !== false
}
