import type { ConnectionState } from '../hooks/useStaffNotifications'
import { swatch } from '../lighting'
import {
  CATEGORY_LABEL,
  type StaffCard as Card,
  type StaffNotification,
  type StaffSessionSummary,
} from '../types'

const STATE_LABEL: Record<string, string> = {
  ASSIST_REQUESTED: '도움 요청',
  ASSIST_ACCEPTED: '응대 중',
  SELF_BROWSING: '혼자 보기',
  MOOD_ACTIVE: '연출 중',
  ENDED: '종료',
  EXPIRED: '만료',
}

// ------------------------------------------------------------------ 헤더

export function StaffHeader({
  me,
  onChangeName,
  connection,
  waiting,
}: {
  me: { staffId: string; staffName: string }
  onChangeName: (name: string) => void
  connection: ConnectionState
  waiting: number
}) {
  return (
    <header className="staff__header">
      <div>
        <h1>MCM 무드 미러 — 직원</h1>
        <p className="muted">
          {waiting > 0 ? `대기 ${waiting}건` : '대기 중 — 응대 가능 상태'}
        </p>
      </div>

      <label className="staff__name">
        <span>내 이름</span>
        <input
          value={me.staffName}
          placeholder="고객 화면에 표시됩니다"
          onChange={(e) => onChangeName(e.target.value)}
        />
      </label>

      {/* 알림이 끊긴 것을 조용히 숨기면 직원은 요청이 없는 것으로 오해한다. */}
      <span className={`conn conn--${connection}`}>
        {connection === 'live' && '실시간 연결됨'}
        {connection === 'connecting' && '연결 중'}
        {connection === 'polling' && '폴링으로 동작 중'}
      </span>
    </header>
  )
}

// ------------------------------------------------------------------ 토스트

export function Toast({
  notification,
  onOpen,
}: {
  notification: StaffNotification
  onOpen: () => void
}) {
  return (
    <button className="toast" onClick={onOpen}>
      <strong>
        {notification.reminder ? '아직 미확인 요청' : '새 도움 요청'}
        {notification.reminder && ` · ${notification.waitingSeconds}초 대기`}
      </strong>
      <span>{notification.mirrorLabel ?? notification.mirrorId}</span>
      <span className="toast__cta">열기</span>
    </button>
  )
}

// -------------------------------------------------------- n35 세션 목록

export function SessionList({
  sessions,
  openId,
  myStaffId,
  onOpen,
}: {
  sessions: StaffSessionSummary[]
  openId: string | null
  myStaffId: string
  onOpen: (id: string) => void
}) {
  return (
    <section className="panel panel--list">
      <h2>세션 목록</h2>

      {sessions.length === 0 && (
        <p className="muted">진행 중인 세션이 없습니다.</p>
      )}

      <ul className="sessions">
        {sessions.map((s) => {
          const mine = s.assignedStaffId === myStaffId
          const lockedByOther = s.assignedStaffId !== null && !mine
          return (
            <li key={s.sessionId}>
              <button
                className={`session ${openId === s.sessionId ? 'is-open' : ''} ${
                  s.needsAssist ? 'is-waiting' : ''
                }`}
                onClick={() => onOpen(s.sessionId)}
              >
                <span className="session__mirror">{s.mirrorLabel ?? s.mirrorId}</span>
                <span className="session__concept">{s.conceptName ?? '분석 전'}</span>

                <span className={`chip chip--${s.state.toLowerCase()}`}>
                  {STATE_LABEL[s.state] ?? s.state}
                </span>

                {s.needsAssist && s.waitingSeconds !== null && (
                  <span className={`wait ${s.waitingSeconds >= 60 ? 'wait--over' : ''}`}>
                    {s.waitingSeconds}초 대기
                  </span>
                )}

                {/* 중복 응대 방지 — 누가 잡고 있는지 목록에서 바로 보여야 헛걸음이 없다 */}
                {lockedByOther && (
                  <span className="lock">{s.assignedStaffName} 응대 중</span>
                )}
                {mine && <span className="lock lock--mine">내 응대</span>}
              </button>
            </li>
          )
        })}
      </ul>
    </section>
  )
}

// -------------------------------------------------------- n33 응대 진행

export function AssistCard({
  card,
  loading,
  myStaffId,
  onAccept,
  onRelease,
  onComplete,
  onClose,
}: {
  card: Card
  loading: boolean
  myStaffId: string
  onAccept: () => void
  onRelease: () => void
  onComplete: () => void
  onClose: () => void
}) {
  const mine = card.assignedStaffId === myStaffId
  const lockedByOther = card.assignedStaffId !== null && !mine
  const canAccept = card.state === 'ASSIST_REQUESTED' && !lockedByOther

  return (
    <section className={`panel panel--card ${loading ? 'is-loading' : ''}`}>
      <div className="card__head">
        <div>
          <h2>{card.mirrorLabel ?? card.mirrorId}</h2>
          <p className="muted">
            {STATE_LABEL[card.state] ?? card.state} · 경과 {card.elapsedSeconds}초
            {card.waitingSeconds !== null && ` · 대기 ${card.waitingSeconds}초`}
          </p>
        </div>
        <button className="btn" onClick={onClose}>
          닫기
        </button>
      </div>

      {/* 응대 상태 */}
      <div className="card__actions">
        {lockedByOther ? (
          <p className="alert">{card.assignedStaffName} 님이 응대 중입니다.</p>
        ) : (
          <>
            <button className="btn btn--primary" disabled={!canAccept} onClick={onAccept}>
              {mine ? '응대 중' : '응대 시작'}
            </button>
            {mine && (
              <button className="btn" onClick={onRelease}>
                다른 직원에게 넘기기
              </button>
            )}
            <button className="btn" onClick={onComplete}>
              응대 완료
            </button>
          </>
        )}
      </div>

      {/* AI 분석 요약 */}
      <div className="card__mood">
        <h3>AI 분석 요약</h3>
        {card.conceptName ? (
          <>
            <p className="card__concept">{card.conceptName}</p>
            <dl className="kv">
              <div>
                <dt>무드</dt>
                <dd>{card.mood}</dd>
              </div>
              <div>
                <dt>스타일</dt>
                <dd>{card.style}</dd>
              </div>
              <div>
                <dt>포멀리티</dt>
                <dd>{card.formality?.toFixed(1)}</dd>
              </div>
            </dl>
            {card.palette && (
              <div className="palette">
                {card.palette.map((c) => (
                  <span key={c} className="palette__dot" style={{ background: swatch(c) }}>
                    <span>{c}</span>
                  </span>
                ))}
              </div>
            )}
            {card.styleComment && <p className="muted">{card.styleComment}</p>}
          </>
        ) : (
          <p className="muted">아직 분석 전입니다.</p>
        )}
        {card.analysisFallback && (
          <p className="tiny">AI 분석 실패 — 기본 프리셋이 적용된 세션입니다.</p>
        )}
      </div>

      {/* 추천 제품 */}
      <div className="card__recs">
        <div className="card__recs-head">
          <h3>추천 제품</h3>
          <span className="tiny">카탈로그 검증을 통과한 제품만 표시됩니다</span>
        </div>

        {card.recommendationFallback && (
          <p className="alert alert--soft">
            AI 랭킹이 실패해 규칙 기반으로 대체됐습니다. <strong>추천 이유가 템플릿
            문장이므로 그대로 읽지 마세요.</strong>
          </p>
        )}

        {card.stylingNote && <p className="styling">{card.stylingNote}</p>}

        {card.recommendations.map((group) => (
          <div key={group.category} className="reccat">
            <h4>{CATEGORY_LABEL[group.category] ?? group.category}</h4>
            <ol className="items">
              {group.items.map((item) => (
                <li key={item.productId} className="item">
                  {item.product.imageUrl && (
                    <img src={item.product.imageUrl} alt="" className="item__img" />
                  )}
                  <div className="item__body">
                    <span className="item__name">
                      <b>{item.rank}</b> {item.product.name}
                    </span>
                    <span className="item__meta">
                      {item.product.priceKrw.toLocaleString()}원 · {item.product.storeLocation}
                    </span>
                    <span className="item__reason">{item.reason}</span>
                  </div>
                </li>
              ))}
            </ol>
          </div>
        ))}

        {/* 재고 미연동이므로 상시 표기 — 직원이 없는 물건을 가지러 가면 신뢰가 깨진다 */}
        <p className="tiny">매장 재고 확인 필요</p>
      </div>
    </section>
  )
}
