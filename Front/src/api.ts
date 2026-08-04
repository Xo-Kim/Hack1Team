import type { AnalyzeResponse, HealthResponse, RecommendResponse } from './types'

/** vite.config.ts 의 proxy 가 /api 를 8080 으로 넘긴다. */
const BASE = '/api'

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`
    try {
      const body = await res.json()
      if (body?.message) message = body.message
    } catch {
      /* 본문이 JSON 이 아니면 상태 코드만 쓴다 */
    }
    throw new Error(message)
  }
  return res.json() as Promise<T>
}

export async function health(): Promise<HealthResponse> {
  return json<HealthResponse>(await fetch(`${BASE}/health`))
}

/** 1차 — 촬영 이미지를 보내 조명·음악 스펙을 받는다. */
export async function analyze(dataUrl: string): Promise<AnalyzeResponse> {
  const res = await fetch(`${BASE}/analyze`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ image: dataUrl }),
  })
  return json<AnalyzeResponse>(res)
}

/**
 * 2차 — 제품 추천.
 * 실서비스에서는 직원 단말만 이 엔드포인트를 호출한다 (PRD §1.1).
 * 지금은 검증을 위해 미러 화면의 STAFF VIEW 패널이 대신 호출한다.
 */
export async function recommend(sessionId: string): Promise<RecommendResponse> {
  return json<RecommendResponse>(await fetch(`${BASE}/recommend/${sessionId}`))
}
