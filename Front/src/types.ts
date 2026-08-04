/** 백엔드 DTO 미러. Back/src/main/java/com/example/back/dto 와 대응한다. */

export interface Outfit {
  palette: string[]
  style: string
  formality: number
  moodTags: string[]
}

/**
 * 하드웨어 중립 조명 스펙 (PRD §14.3).
 * 웹 렌더러는 이 스펙의 소비자 중 하나일 뿐이다 — CSS 전용 값을 여기 넣지 말 것.
 */
export interface Lighting {
  sceneName: string
  primaryColor: string
  accentColor: string
  colorTemperatureK: number
  brightness: number
  transitionMs: number
  effect: 'static' | 'breathe' | 'sweep'
}

export interface MusicSpec {
  queryTags: string[]
  energy: number
  genreHint: string
  key: string
  scale: string
  bpm: number
}

export interface MoodAnalysis {
  outfit: Outfit
  mood: string
  conceptName: string
  lighting: Lighting
  music: MusicSpec
  /** 고객 화면에 노출되는 유일한 LLM 텍스트. 제품 언급이 있으면 서버가 null 로 만든다. */
  styleComment: string | null
}

/**
 * 실제 재생할 음원. null 이면 절차적 앰비언스로 폴백한다.
 * artist / shareUrl 은 CC 라이선스의 저작자 표시 요건이라 화면에 반드시 노출한다.
 */
export interface MusicTrack {
  id: string
  title: string
  artist: string
  audioUrl: string
  durationSec: number
  shareUrl: string
  license: string
  source: string
  /** GPT 가 후보 중 이 곡을 고른 이유. null 이면 AI 선곡이 아니라 검색 1위 폴백. */
  reason: string | null
  /** 상업적 이용 가능 여부. false 면 실매장 영업에는 못 쓴다. */
  commercialOk: boolean
}

export interface AnalyzeResponse {
  sessionId: string
  analysis: MoodAnalysis
  track: MusicTrack | null
  fallback: boolean
  note: string | null
}

export interface Product {
  id: string
  category: 'bag' | 'wallet' | 'belt'
  name: string
  line: string
  colors: string[]
  material: string
  priceKrw: number
  styleTags: string[]
  formality: number
  size: string
  imageUrl: string | null
  productUrl: string | null
  storeLocation: string
}

export interface RecommendedItem {
  rank: number
  productId: string
  reason: string
  product: Product
}

export interface CategoryRecommendation {
  category: 'bag' | 'wallet' | 'belt'
  items: RecommendedItem[]
}

export interface RecommendResponse {
  sessionId: string
  recommendations: CategoryRecommendation[]
  stylingNote: string
  fallback: boolean
  note: string | null
}

export interface HealthResponse {
  status: string
  llmMode: 'mock' | 'live'
  musicMode: 'jamendo' | 'synth'
  activeSessions: number
}

export const CATEGORY_LABEL: Record<string, string> = {
  bag: '가방',
  wallet: '지갑',
  belt: '벨트',
}
