import { useCallback, useEffect, useRef, useState } from 'react'
import { HttpError, isSessionGone, staff } from '../api'
import { useStaffNotifications } from '../hooks/useStaffNotifications'
import type { StaffCard, StaffNotification, StaffSessionSummary } from '../types'
import { AssistCard, SessionList, StaffHeader, Toast } from './screens'

/**
 * 직원 단말 화면. 와이어프레임 n28·n29·n33·n35 에 대응한다.
 * <p>
 * 고객 미러(`MirrorApp`)와 완전히 분리된 앱이다. 라우트가 다르고 데이터도 다르다.
 * 이 화면만 제품 추천을 본다.
 * <p>
 * <b>직원 식별은 현재 로컬 저장 값이다.</b> 서버에 인증이 없어서 `staffId` 가
 * 자기 신고값이며, 그래서 중복 응대 방지도 신뢰 기반이다. 매장 도입 전에 로그인이
 * 붙어야 하고, 그때 이 부분은 통째로 교체된다.
 * <p>
 * 이름은 다루지 않는다. 단말을 서로 구분하는 데 필요한 것은 식별자뿐이고, 고객
 * 화면 문구는 상태 기반으로 바뀌었다.
 */

const STAFF_KEY = 'mcm.staff'

interface Me {
  staffId: string
}

function loadMe(): Me {
  try {
    const raw = localStorage.getItem(STAFF_KEY)
    // 이름을 쓰던 시절의 저장값이 남아 있어도 staffId 만 꺼내 쓴다.
    const saved = raw ? (JSON.parse(raw) as Partial<Me>) : null
    if (saved?.staffId) return { staffId: saved.staffId }
  } catch {
    /* 저장값이 깨졌으면 새로 만든다 */
  }
  const me = { staffId: `staff-${Math.random().toString(36).slice(2, 7)}` }
  localStorage.setItem(STAFF_KEY, JSON.stringify(me))
  return me
}

export function StaffApp() {
  const [me] = useState<Me>(loadMe)
  const [sessions, setSessions] = useState<StaffSessionSummary[]>([])
  const [openId, setOpenId] = useState<string | null>(null)
  const [card, setCard] = useState<StaffCard | null>(null)
  const [cardLoading, setCardLoading] = useState(false)
  const [toast, setToast] = useState<StaffNotification | null>(null)
  const [error, setError] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    try {
      setSessions(await staff.list())
      setError(null)
    } catch {
      setError('서버에 연결하지 못했습니다.')
    }
  }, [])

  const { connection, last } = useStaffNotifications({
    onChange: refresh,
    onAssistRequest: (n) => setToast(n),
  })

  useEffect(() => {
    void refresh()
  }, [refresh])

  // 열어 둔 카드는 알림이 올 때 같이 갱신한다. 다른 직원이 점유하면 즉시 잠겨야 한다.
  // openCard 를 의존성에 넣으면 카드를 열 때마다 이 효과가 다시 돌아 무한 갱신이 된다.
  // 최신 함수만 참조하도록 ref 로 우회한다.
  const openCardRef = useRef<(id: string) => void>(() => {})
  useEffect(() => {
    if (!openId || !last || last.sessionId !== openId) return
    openCardRef.current(openId)
  }, [last, openId])

  useEffect(() => {
    if (!toast) return
    const timer = window.setTimeout(() => setToast(null), 6000)
    return () => window.clearTimeout(timer)
  }, [toast])

  /**
   * 추천이 아직 계산 중이면 짧게 다시 조회한다.
   * <p>
   * 카드는 무드·팔레트만으로 먼저 뜨고 추천은 뒤따라 붙는다. 서버가 요청 시점에
   * 계산을 다시 걸어주므로, 이 폴링은 대기일 뿐 아니라 예열이 실패했을 때의
   * 재시도이기도 하다.
   */
  useEffect(() => {
    if (!card || card.recommendationsReady || !openId) return
    const timer = window.setTimeout(() => {
      staff.card(openId).then(setCard).catch(() => {
        /* 다음 주기가 다시 시도한다 */
      })
    }, 1200)
    return () => window.clearTimeout(timer)
  }, [card, openId])

  // ---------------------------------------------------------------- 카드

  const openCard = useCallback(async (id: string) => {
    setOpenId(id)
    setCardLoading(true)
    try {
      setCard(await staff.card(id))
      setError(null)
    } catch (e) {
      if (isSessionGone(e)) {
        setOpenId(null)
        setCard(null)
        void refresh()
        return
      }
      setError(e instanceof Error ? e.message : '카드를 불러오지 못했습니다.')
    } finally {
      setCardLoading(false)
    }
  }, [refresh])

  openCardRef.current = openCard

  const closeCard = useCallback(() => {
    setOpenId(null)
    setCard(null)
  }, [])

  /** 서버가 409 로 막은 경우를 화면 문구로 옮긴다. 조용히 실패하면 원인을 알 수 없다. */
  const runOnCard = useCallback(
    async (action: () => Promise<StaffCard>) => {
      try {
        setCard(await action())
        setError(null)
      } catch (e) {
        if (isSessionGone(e)) {
          closeCard()
          void refresh()
          return
        }
        setError(
          e instanceof HttpError ? e.message : '요청을 처리하지 못했습니다.',
        )
        if (openId) void openCard(openId)
      } finally {
        void refresh()
      }
    },
    [openId, openCard, closeCard, refresh],
  )

  const accept = useCallback(
    (id: string) => void runOnCard(() => staff.accept(id, me.staffId)),
    [me, runOnCard],
  )

  const release = useCallback(
    (id: string) => void runOnCard(() => staff.release(id, me.staffId)),
    [me, runOnCard],
  )

  const complete = useCallback(
    async (id: string) => {
      await runOnCard(() => staff.complete(id, me.staffId))
      closeCard()
    },
    [me, runOnCard, closeCard],
  )

  // ---------------------------------------------------------------- 렌더

  const waiting = sessions.filter((s) => s.needsAssist).length

  return (
    <div className="staff">
      <StaffHeader me={me} connection={connection} waiting={waiting} />

      {error && <p className="staff__error">{error}</p>}
      {toast && <Toast notification={toast} onOpen={() => openCard(toast.sessionId)} />}

      <div className="staff__body">
        <SessionList
          sessions={sessions}
          openId={openId}
          myStaffId={me.staffId}
          onOpen={openCard}
        />

        {card ? (
          <AssistCard
            card={card}
            loading={cardLoading}
            myStaffId={me.staffId}
            onAccept={() => accept(card.sessionId)}
            onRelease={() => release(card.sessionId)}
            onComplete={() => complete(card.sessionId)}
            onClose={closeCard}
          />
        ) : (
          <section className="panel panel--empty">
            <h2>
              {waiting > 0 ? `${waiting}건의 도움 요청이 있습니다` : '현재 배정된 도움 요청 없음'}
            </h2>
            <p className="muted">
              고객의 도움 요청이 발생하면 자동으로 알림을 받습니다. 목록에서 세션을 선택하면
              응대 카드가 열립니다.
            </p>
          </section>
        )}
      </div>
    </div>
  )
}
