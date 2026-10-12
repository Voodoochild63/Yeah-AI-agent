import type { ItineraryNode, Lang, SpotCandidateDto, TransitModeOption, TransitOptions, TravelMode } from '@/types/itinerary'
import { refreshOpenStatus } from '@/utils/openHours'
import { formatModeLabel, optionFor, parseClock, parseStayMinutes } from '@/utils/transitMode'

const EARTH_RADIUS_KM = 6371
const ROAD_FACTOR = 1.25
const WALK_MAX_KM = 1.2
const WALK_KMH = 4.5
const DRIVE_KMH = 25
const TRANSIT_KMH = 20
const DRIVE_LIGHT_BUFFER_MIN = 2
const TRANSIT_WAIT_MIN = 5

const SUBURBS = [
  'Alexandria',
  'Bexley',
  'Marrickville',
  'Newtown',
  'Chippendale',
  'Surry Hills',
  'Haymarket',
  'The Rocks',
  'Bondi',
  'Bronte',
  'Glebe',
  'Paddington',
  'Darlinghurst',
  'Redfern',
  'Waterloo',
  'Zetland',
  'Enmore',
  'Randwick',
  'Coogee',
  'Kensington',
  'Kingsford',
  'Maroubra',
  'Clovelly',
]

export function canShuffleLocally(node: ItineraryNode | undefined): boolean {
  return shufflePool(node).length >= 2
}

export function nextCandidateIndex(node: ItineraryNode): number {
  const candidates = shufflePool(node)
  if (candidates.length === 0) return 0
  const current = node.poiName?.trim().toLowerCase()
  const index = candidates.findIndex((item) => item.name?.trim().toLowerCase() === current)
  const from = index >= 0 ? index : 0
  return (from + 1) % candidates.length
}

function shufflePool(node: ItineraryNode | undefined): SpotCandidateDto[] {
  return node?.candidates ?? node?.sunsetCandidates ?? []
}

export function applySpotCandidate(node: ItineraryNode, candidate: SpotCandidateDto, lang: Lang) {
  const name = candidate.name?.trim()
  if (!name) return
  node.poiName = name
  if (candidate.categoryTag?.trim()) {
    const tag = candidate.categoryTag.trim()
    node.category = tag
    node.categoryZh = tag
    node.categoryEn = tag
  }
  node.latitude = candidate.latitude
  node.longitude = candidate.longitude
  node.openingHoursText = candidate.openingHoursText
  node.searchKeyword = `${name} 拍照 机位 避坑`
  node.redditKeyword = `${name} Sydney review`
  const keyword = parkingKeyword(name, candidate.address)
  node.parkingSearchKeyword = keyword
  node.parkingInfo = {
    ...(node.parkingInfo ?? { hasParkingRecommendation: true }),
    hasParkingRecommendation: true,
    parkingSearchKeyword: keyword,
  }
  refreshOpenStatus(node, lang)
}

export function refreshLegsAround(schedule: ItineraryNode[], index: number, lang: Lang) {
  if (index > 0) {
    bindLeg(schedule[index - 1], schedule[index], lang)
  }
  if (index < schedule.length - 1) {
    bindLeg(schedule[index], schedule[index + 1], lang)
  }
  const from = Math.max(0, index - 1)
  cascadeArrivals(schedule, from, lang)
}

function bindLeg(from: ItineraryNode | undefined, to: ItineraryNode | undefined, lang: Lang) {
  if (!from || !to) return
  if (!isValidCoord(from.latitude, from.longitude) || !isValidCoord(to.latitude, to.longitude)) return
  const km = calculateDistanceKm(from.latitude!, from.longitude!, to.latitude!, to.longitude!)
  const options = estimateTransitOptions(km, lang)
  from.transitOptions = options
  const previous = from.selectedMode
  const nextMode: TravelMode = km <= WALK_MAX_KM ? 'WALKING' : previous === 'TRANSIT' ? 'TRANSIT' : 'DRIVING'
  from.selectedMode = km <= WALK_MAX_KM && previous === 'WALKING' ? 'WALKING' : nextMode
  from.transitToNext = formatModeLabel(from.selectedMode, optionFor(options, from.selectedMode), lang)
}

function cascadeArrivals(schedule: ItineraryNode[], fromIndex: number, lang: Lang) {
  const start = schedule[fromIndex]
  let cursor = parseClock(start?.arrivalTime)
  if (cursor == null) return
  for (let i = fromIndex; i < schedule.length - 1; i++) {
    const current = schedule[i]
    const following = schedule[i + 1]
    if (!current || !following) break
    const stay = parseStayMinutes(current.stayDuration)
    const hop = optionFor(current.transitOptions, current.selectedMode ?? 'WALKING')?.durationMinutes ?? 0
    cursor += stay + hop
    following.arrivalTime = formatClockLocal(cursor)
    following.estimatedArrival = following.arrivalTime
    refreshOpenStatus(following, lang)
  }
}

function formatClockLocal(totalMinutes: number) {
  const normalized = ((totalMinutes % 1440) + 1440) % 1440
  const hours = Math.floor(normalized / 60)
  const minutes = normalized % 60
  return `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}`
}

export function parkingKeyword(name: string, address?: string) {
  const poi = name.trim()
  if (!poi) return 'parking near Sydney'
  const blob = `${poi} ${address ?? ''}`.toLowerCase()
  const suburb = SUBURBS.find((item) => blob.includes(item.toLowerCase())) ?? 'Sydney'
  if (poi.toLowerCase().includes(suburb.toLowerCase())) {
    return `parking near ${poi}`
  }
  return `parking near ${poi} ${suburb}`
}

export function calculateDistanceKm(lat1: number, lon1: number, lat2: number, lon2: number) {
  const latDistance = toRad(lat2 - lat1)
  const lonDistance = toRad(lon2 - lon1)
  const a =
    Math.sin(latDistance / 2) * Math.sin(latDistance / 2) +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2)
  return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

export function isValidCoord(lat?: number, lon?: number) {
  return (
    lat != null &&
    lon != null &&
    lat >= -90 &&
    lat <= 90 &&
    lon >= -180 &&
    lon <= 180 &&
    !(lat === 0 && lon === 0)
  )
}

export function estimateTransitOptions(straightKm: number, lang: Lang): TransitOptions {
  const km = Math.max(0, straightKm)
  const roadKm = km * ROAD_FACTOR
  const roadMeters = Math.round(roadKm * 1000)
  const walkMin = Math.max(1, Math.round((roadKm / WALK_KMH) * 60))
  const driveMin = Math.max(2, Math.round((roadKm / DRIVE_KMH) * 60) + DRIVE_LIGHT_BUFFER_MIN)
  const transitMin = Math.max(6, TRANSIT_WAIT_MIN + Math.round((roadKm / TRANSIT_KMH) * 60))
  const stops = Math.max(1, Math.round(roadKm / 0.8))
  const zh = lang !== 'en'
  return {
    walking: modeOption(walkMin, distanceText(roadMeters), roadMeters, zh),
    driving: modeOption(driveMin, distanceText(roadMeters), roadMeters, zh),
    transit: modeOption(transitMin, zh ? `公交${stops}站` : stops === 1 ? '1 stop' : `${stops} stops`, roadMeters, zh),
  }
}

function modeOption(minutes: number, distance: string, meters: number, zh: boolean): TransitModeOption {
  return {
    durationText: zh ? `${minutes}分钟` : `${minutes} min`,
    distanceText: distance,
    durationMinutes: minutes,
    distanceMeters: meters,
  }
}

function distanceText(meters: number) {
  if (meters < 1000) return `${Math.max(meters, 1)}m`
  return `${(meters / 1000).toFixed(1)}km`
}

function toRad(value: number) {
  return (value * Math.PI) / 180
}
