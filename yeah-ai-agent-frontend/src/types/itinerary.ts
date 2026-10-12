export interface ParkingInfo {
  hasParkingRecommendation: boolean
  parkingName?: string
  address?: string
  rating?: number
  walkingEstimate?: string
  tip?: string
  tips?: string
  parkingSearchKeyword?: string
}

export interface SunsetEvaluation {
  recommended: boolean
  sunsetTime: string
  goldenHourWindow: string
  cloudCover: number
  rainProb: number
  recommendationReason: string
  recommendedSpot?: string
  photoTip: string
}

export type TravelMode = 'WALKING' | 'DRIVING' | 'TRANSIT'

export type OpenStatus = 'OPEN' | 'CLOSING_SOON' | 'CLOSED' | 'UNKNOWN'

export interface SpotCandidateDto {
  name: string
  categoryTag?: string
  rating?: number
  ratingCount?: number
  latitude?: number
  longitude?: number
  openingHoursText?: string
  address?: string
  /** 短亮点，换点时若综述里出现旧短语则一并替换 */
  highlightDesc?: string
}

export interface TransitModeOption {
  durationText: string
  distanceText: string
  durationMinutes: number
  distanceMeters?: number
}

export interface TransitOptions {
  walking: TransitModeOption
  driving: TransitModeOption
  transit: TransitModeOption
  drivingAllowed?: boolean
}

export interface ItineraryNode {
  nodeId: string
  poiName: string
  category: string
  categoryZh?: string
  categoryEn?: string
  arrivalTime: string
  stayDuration: string
  transitToNext: string
  selectedMode?: TravelMode
  transitOptions?: TransitOptions
  latitude?: number
  longitude?: number
  estimatedArrival?: string
  openStatus?: OpenStatus
  statusWarning?: string
  opensAt?: string
  closesAt?: string
  openingHoursText?: string
  /** 主规划预置的同类备选，前端原地轮播 */
  candidates?: SpotCandidateDto[]
  /** 落日专属候选，通常与 candidates 同步 */
  sunsetCandidates?: SpotCandidateDto[]

  /** 小红书 / RedNote：英文店名 + 拍照 机位 避坑 */
  searchKeyword?: string
  /** Reddit：纯英文店名与评价检索词 */
  redditKeyword?: string
  /** 官方网站，或 Google 官方直达；空则不展示 */
  websiteUrl?: string
  parkingTips?: string
  parkingSearchKeyword?: string
  parkingInfo?: ParkingInfo
  /** WALK / SCENIC / PARK / ATTRACTION / BAR / PUB / DESSERT / SHOPPING / KOREAN_RESTAURANT / DINING / CAFE / TEA / SUNSET */
  spotCategory?: string
  drivingForbidden?: boolean
  alcoholInvolved?: boolean
  safetyWarning?: string
}

export interface ItineraryPlan {
  title: string
  titleZh?: string
  titleEn?: string
  summary: string
  summaryZh?: string
  summaryEn?: string
  /** 整体规划思路与体验亮点，语言跟随 lang */
  detailedDescription?: string
  detailedDescriptionZh?: string
  detailedDescriptionEn?: string
  overview?: string
  schedule: ItineraryNode[]
  backupSuggestions: string[]
  backupSuggestionsZh?: string[]
  backupSuggestionsEn?: string[]
  sunsetEvaluation?: SunsetEvaluation
  /** 多轮局部修改会话，首次规划由后端签发 */
  sessionId?: string
}

export type RefineAction = 'REPLACE' | 'APPEND' | 'INSERT_BEFORE' | 'DELETE'

export interface ItineraryRefineResponse {
  plan: ItineraryPlan
  reason?: string
  /** REPLACE 为被替换下标；APPEND 为新插入节点下标，用于高亮 */
  targetIndex?: number
  searchQuery?: string
  action?: RefineAction
}

/** 对应后端 LanguageEnum */
export type Lang = 'zh' | 'en'

export type TastePace = 'relaxed' | 'moderate' | 'active'

export interface TasteProfile {
  aesthetic: string
  photoStyle: string
  pace: TastePace
  customNotes: string
}

export interface RagContext {
  tasteProfile: TasteProfile
  customPois: PoiItem[]
}

/**
 * 私域打卡点，字段对齐后端 PoiRagService.PoiItem。
 * 后端目前是 @PostConstruct 硬编码、没有 CRUD 接口，
 * 所以前端先落在 localStorage，等后端补接口后换成远程存储即可。
 */
export interface PoiItem {
  id: string
  poiName: string
  category: string
  tags: string[]
  vibes: string[]
  visualStyle: string[]
  personalTip: string
  description: string
}
