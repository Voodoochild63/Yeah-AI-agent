import type { ItineraryNode, Lang, OpenStatus } from '@/types/itinerary'
import { parseClock } from '@/utils/transitMode'

const CLOSING_SOON_MINUTES = 30

export function refreshOpenStatus(node: ItineraryNode, lang: Lang) {
  const arrivalText = node.arrivalTime?.trim()
  if (arrivalText) {
    node.estimatedArrival = arrivalText
  }
  hydrateHoursFromText(node)
  const arrival = parseClock(arrivalText)
  if (arrival == null) return

  if (node.closesAt === '24:00' || (node.opensAt === '00:00' && node.closesAt === '24:00')) {
    node.openStatus = 'OPEN'
    node.statusWarning = node.openingHoursText || (lang === 'en' ? 'Open · 24 hours' : '营业中 · 全天开放')
    return
  }

  const opens = parseClock(node.opensAt)
  const closes = parseClock(node.closesAt)
  if (opens == null && closes == null) {
    if (!node.openStatus || node.openStatus === 'UNKNOWN') {
      node.openStatus = 'UNKNOWN'
      node.statusWarning = lang === 'en' ? 'Opening hours unknown' : '营业时间未知'
    }
    return
  }

  const status = evaluateWindow(arrival, opens, closes)
  node.openStatus = status
  node.statusWarning = warningText(status, arrivalText ?? '', node.closesAt, lang)
}

export function evaluateWindow(
  arrival: number,
  opens: number | null,
  closes: number | null,
): OpenStatus {
  if (opens == null || closes == null) return 'UNKNOWN'
  const overnight = closes <= opens
  const inWindow = overnight
    ? arrival >= opens || arrival < closes
    : arrival >= opens && arrival < closes
  if (!inWindow) return 'CLOSED'
  let closeMin = closes
  let arr = arrival
  if (overnight) {
    closeMin += 24 * 60
    if (arr < opens) arr += 24 * 60
  }
  return closeMin - arr < CLOSING_SOON_MINUTES ? 'CLOSING_SOON' : 'OPEN'
}

function warningText(status: OpenStatus, arrival: string, closesAt: string | undefined, lang: Lang) {
  const close = closesAt?.trim()
  if (lang === 'en') {
    if (status === 'CLOSED') {
      return `Arrive ${arrival}, but the venue closes${close ? ` at ${close}` : ''} (closed-door risk)`
    }
    if (status === 'CLOSING_SOON') {
      return `Arrive ${arrival}; closes at ${close ?? ''} (under 30 min left)`
    }
    if (status === 'OPEN') {
      return close ? `Open · usually closes at ${close}` : 'Open'
    }
    return 'Opening hours unknown'
  }
  if (status === 'CLOSED') {
    return `预计 ${arrival} 抵达，该店 ${close ?? '当天'} 打烊（闭店风险）`
  }
  if (status === 'CLOSING_SOON') {
    return `预计 ${arrival} 抵达，该店 ${close ?? ''} 打烊（不足30分钟）`
  }
  if (status === 'OPEN') {
    return close ? `营业中 · 通常 ${close} 打烊` : '营业中'
  }
  return '营业时间未知'
}

export function hydrateHoursFromText(node: ItineraryNode) {
  const text = node.openingHoursText?.trim() ?? ''
  if (!text) return
  if (/全天开放|常年开放|24\s*hour|open year-round/i.test(text)) {
    node.opensAt = '00:00'
    node.closesAt = '24:00'
    return
  }
  const match = text.match(/(\d{1,2}:\d{2})\s*[–-]\s*(\d{1,2}:\d{2})/)
  if (!match) return
  node.opensAt = padClock(match[1])
  node.closesAt = padClock(match[2])
}

function padClock(value: string) {
  const [hours, minutes] = value.split(':')
  return `${String(Number(hours)).padStart(2, '0')}:${minutes}`
}
