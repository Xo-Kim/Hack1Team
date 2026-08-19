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

/** 세션 상태. 서버 SessionState enum 과 이름이 같아야 한다. */
export type SessionState =
  | 'IDLE'
  | 'CONSENTED'
  | 'ANALYZING'
  | 'MOOD_ACTIVE'
  | 'ASSIST_REQUESTED'
  | 'ASSIST_ACCEPTED'
  | 'SELF_BROWSING'
  | 'ENDED'
  | 'EXPIRED'

export interface StartSessionResponse {
  sessionId: string
  state: SessionState
}

/**
 * 고객 미러 화면용 세션 상태.
 * <b>여기에 추천 필드는 없다.</b> 서버가 고객 경로로 추천을 내려주지 않는다.
 */
export interface SessionStateResponse {
  sessionId: string
  state: SessionState
  elapsedSeconds: number
  analysis: MoodAnalysis | null
  track: MusicTrack | null
  fallback: boolean
  /** true 면 직원 응대가 시작된 것 — 음악만 줄이고 조명은 유지한다. */
  musicDucked: boolean
}

/** ---- 직원 화면 전용 ---- */

export interface StaffSessionSummary {
  sessionId: string
  mirrorId: string
  mirrorLabel: string | null
  state: SessionState
  elapsedSeconds: number
  waitingSeconds: number | null
  conceptName: string | null
  needsAssist: boolean
  /** 점유 중인 단말. 내 것인지만 비교한다 — 이름은 서버가 보관하지 않는다. */
  assignedStaffId: string | null
}

export interface StaffCard {
  sessionId: string
  mirrorId: string
  mirrorLabel: string | null
  state: SessionState
  elapsedSeconds: number
  waitingSeconds: number | null
  conceptName: string | null
  mood: string | null
  palette: string[] | null
  style: string | null
  formality: number | null
  styleComment: string | null
  recommendations: CategoryRecommendation[]
  stylingNote: string | null
  analysisFallback: boolean
  /** true 면 LLM 랭킹 실패 — 추천 이유가 템플릿이라 그대로 읽으면 안 된다. */
  recommendationFallback: boolean
  note: string | null
  assignedStaffId: string | null
}

export type NotificationType =
  | 'ASSIST_REQUESTED'
  | 'ASSIST_CANCELLED'
  | 'ASSIST_ACCEPTED'
  | 'ASSIST_RELEASED'
  | 'SELF_BROWSING'
  | 'SESSION_CLOSED'

export interface StaffNotification {
  type: NotificationType
  sessionId: string
  mirrorId: string
  mirrorLabel: string | null
  state: SessionState
  waitingSeconds: number
  /** 60초 넘게 미확인이라 다시 보낸 알림. */
  reminder: boolean
  occurredAt: string
}

export interface ApiError {
  error: string
  message: string
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
