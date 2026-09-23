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
  recommendedSpot: string
  photoTip: string
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
  /** 小红书 / RedNote：英文店名 + 拍照 机位 避坑 */
  searchKeyword?: string
  /** Reddit：纯英文店名与评价检索词 */
  redditKeyword?: string
  /** 官方网站，或 Google 官方直达；空则不展示 */
  websiteUrl?: string
  parkingTips?: string
  parkingSearchKeyword?: string
  parkingInfo?: ParkingInfo
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
