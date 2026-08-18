import type {
  AnalyzeResponse,
  HealthResponse,
  SessionStateResponse,
  StaffCard,
  StaffSessionSummary,
  StartSessionResponse,
} from './types'

/**
 * 백엔드 클라이언트.
 * <p>
 * <b>고객용과 직원용이 파일 안에서도 갈라져 있다.</b> 서버가 경로를 나눠 둔 이유가
 * "고객 화면 코드에서 추천에 닿을 수 없게" 하는 것이므로, 클라이언트도 같은 선을 지킨다.
 * 미러 화면 컴포넌트에서 `staff.*` 를 부르는 코드가 생기면 그건 원칙 위반이다.
 */

const BASE = '/api'

class HttpError extends Error {
  // 생성자 파라미터 프로퍼티는 tsconfig 의 erasableSyntaxOnly 에 걸린다.
  // 타입만 지우면 되는 문법으로 유지해야 하므로 필드를 명시적으로 선언한다.
  status: number
  code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(BASE + path, {
    ...init,
    headers: init?.body ? { 'Content-Type': 'application/json' } : undefined,
  })

  if (!res.ok) {
    // 서버는 모든 오류를 {error, message} 로 통일해 내려준다.
    let code = 'unknown'
    let message = `요청에 실패했습니다 (${res.status})`
    try {
      const body = await res.json()
      code = body.error ?? code
      message = body.message ?? message
    } catch {
      /* JSON 이 아닌 응답 — 기본 메시지를 쓴다 */
    }
    throw new HttpError(res.status, code, message)
  }

  if (res.status === 204) {
    return undefined as T
  }
  return res.json() as Promise<T>
}

export { HttpError }

/** 세션이 만료·소멸했는지. 이 경우 화면은 대기 상태로 돌아가야 한다. */
export function isSessionGone(e: unknown): boolean {
  return e instanceof HttpError && e.code === 'session_not_found'
}

/** 서버 상태와 화면 상태가 어긋난 경우. 상태를 다시 조회해 맞춘다. */
export function isIllegalState(e: unknown): boolean {
  return e instanceof HttpError && e.status === 409
}

export const health = () => request<HealthResponse>('/health')

// ------------------------------------------------------------- 고객 미러

export const mirror = {
  start: (mirrorId: string, storeId: string, mirrorLabel: string) =>
    request<StartSessionResponse>('/mirror/sessions', {
      method: 'POST',
      body: JSON.stringify({ mirrorId, storeId, mirrorLabel }),
    }),

  state: (id: string) => request<SessionStateResponse>(`/mirror/sessions/${id}`),

  consent: (id: string) =>
    request<SessionStateResponse>(`/mirror/sessions/${id}/consent`, { method: 'POST' }),

  /** 분석은 실측 3~7초 걸린다. 호출부에서 진행 표시를 띄울 것. */
  analyze: (id: string, image: string) =>
    request<AnalyzeResponse>(`/mirror/sessions/${id}/analyze`, {
      method: 'POST',
      body: JSON.stringify({ image }),
    }),

  requestAssist: (id: string) =>
    request<SessionStateResponse>(`/mirror/sessions/${id}/assist-request`, { method: 'POST' }),

  cancelAssist: (id: string) =>
    request<SessionStateResponse>(`/mirror/sessions/${id}/assist-cancel`, { method: 'POST' }),

  selfBrowse: (id: string) =>
    request<SessionStateResponse>(`/mirror/sessions/${id}/self-browse`, { method: 'POST' }),

  /**
   * 고객이 경험을 마친다. 완주(ENDED)로 기록된다.
   * 직원의 '응대 완료'는 세션을 끝내지 않는다 — 끝내는 건 고객뿐이다.
   */
  end: (id: string) =>
    request<SessionStateResponse>(`/mirror/sessions/${id}/end`, { method: 'POST' }),

  /**
   * 대기 화면으로 되돌린다.
   * 무입력 타임아웃에서도 반드시 불러야 한다 — 화면만 초기화하면 서버 세션이 남아
   * 직원 대기 목록에 계속 떠 있다.
   */
  reset: (id: string) =>
    request<SessionStateResponse>(`/mirror/sessions/${id}/reset`, { method: 'POST' }),
}

// ------------------------------------------------------------- 직원 단말

export const staff = {
  list: () => request<StaffSessionSummary[]>('/staff/sessions'),

  card: (id: string) => request<StaffCard>(`/staff/sessions/${id}`),

  /** 이름은 보내지 않는다. 서버가 점유 판정에 쓰는 것은 staffId 뿐이다. */
  accept: (id: string, staffId: string) =>
    request<StaffCard>(`/staff/sessions/${id}/accept`, {
      method: 'POST',
      body: JSON.stringify({ staffId }),
    }),

  release: (id: string, staffId: string) =>
    request<StaffCard>(`/staff/sessions/${id}/release`, {
      method: 'POST',
      body: JSON.stringify({ staffId }),
    }),

  complete: (id: string, staffId: string) =>
    request<StaffCard>(`/staff/sessions/${id}/complete`, {
      method: 'POST',
      body: JSON.stringify({ staffId }),
    }),

  /** SSE 스트림 주소. 구독은 useStaffNotifications 가 맡는다. */
  notificationsUrl: `${BASE}/staff/notifications`,
}
